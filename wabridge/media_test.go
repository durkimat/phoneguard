package wabridge

import (
	"testing"
	"time"

	"google.golang.org/protobuf/proto"

	"go.mau.fi/whatsmeow/proto/waE2E"
)

func TestDescribeKinds(t *testing.T) {
	cases := []struct {
		name string
		msg  *waE2E.Message
		kind string
	}{
		{"text", &waE2E.Message{Conversation: proto.String("hi")}, "text"},
		{"image", &waE2E.Message{ImageMessage: &waE2E.ImageMessage{Mimetype: proto.String("image/jpeg"), FileLength: proto.Uint64(1000), Width: proto.Uint32(640), Height: proto.Uint32(480)}}, "image"},
		{"sticker", &waE2E.Message{StickerMessage: &waE2E.StickerMessage{Mimetype: proto.String("image/webp"), IsAnimated: proto.Bool(true)}}, "sticker"},
		{"gif", &waE2E.Message{VideoMessage: &waE2E.VideoMessage{GifPlayback: proto.Bool(true), Seconds: proto.Uint32(3)}}, "gif"},
		{"video", &waE2E.Message{VideoMessage: &waE2E.VideoMessage{Seconds: proto.Uint32(24), FileLength: proto.Uint64(4 << 20)}}, "video"},
		{"voice", &waE2E.Message{AudioMessage: &waE2E.AudioMessage{PTT: proto.Bool(true)}}, "audio"},
		{"location", &waE2E.Message{LocationMessage: &waE2E.LocationMessage{}}, "other"},
	}
	for _, c := range cases {
		m := &Message{}
		describe(m, c.msg)
		if m.Kind != c.kind {
			t.Errorf("%s: kind %q, want %q", c.name, m.Kind, c.kind)
		}
	}
	m := &Message{}
	describe(m, cases[2].msg)
	if !m.Animated || m.MediaRef == "" {
		t.Errorf("animated sticker: %+v", m)
	}
	m = &Message{}
	describe(m, cases[4].msg)
	if m.Seconds != 24 || m.MediaSize != 4<<20 || m.MediaRef == "" {
		t.Errorf("video: %+v", m)
	}
}

func TestDownloadRefusesWhatIsNotMedia(t *testing.T) {
	b := &Bridge{dir: t.TempDir()}
	if _, err := b.fetch("not base64!", "id", "video/mp4", MaxVideo); err == nil {
		t.Error("garbage ref accepted")
	}
	ref := refOf(&waE2E.Message{Conversation: proto.String("x")})
	if _, err := b.fetch(ref, "id", "video/mp4", MaxVideo); err == nil {
		t.Error("a text message is not downloadable")
	}
}

func TestFileNames(t *testing.T) {
	if safeID("3EB0/../x y") != "3EB0xy" || safeID("") != "x" || safeID("../..") != "x" {
		t.Errorf("safeID: %q %q", safeID("3EB0/../x y"), safeID("../.."))
	}
	if extFor("image/webp") != ".webp" || extFor("video/mp4; codecs=avc1") != ".mp4" || extFor("application/x") != ".bin" {
		t.Error("extFor")
	}
}

func TestEveryItemOfABurstCountsAsOneSend(t *testing.T) {
	b := &Bridge{perMin: 5, perDay: 200}
	if err := b.slots(2); err != nil { // a label and a sticker
		t.Fatal(err)
	}
	if len(b.sent) != 2 {
		t.Fatalf("a burst of two took %d places", len(b.sent))
	}
	if err := b.slots(2); err != nil {
		t.Fatal(err)
	}
	// 4 used: a burst of two no longer fits in 5 per minute, and is refused whole (nothing half-sent, nothing taken)
	if err := b.Reserve(2); err == nil {
		t.Error("reserve should refuse")
	}
	if err := b.slots(2); err == nil {
		t.Error("slots should refuse")
	}
	if len(b.sent) != 4 {
		t.Errorf("a refused burst took places: %d", len(b.sent))
	}
	if err := b.slots(1); err != nil {
		t.Error("one more fits")
	}
	b.sent = []time.Time{time.Now().Add(-2 * time.Minute)}
	if err := b.Reserve(5); err != nil {
		t.Error("old sends do not count against the minute")
	}
}

func TestSendsNeedAnAllowedChat(t *testing.T) {
	b := &Bridge{perMin: 5, perDay: 200, allowed: map[string]bool{}, gids: map[string]bool{}, groups: map[string]bool{}, gnames: map[string]string{}}
	for _, f := range []func() error{
		func() error { _, e := b.SendText("g123", "x"); return e },
		func() error { _, e := b.SendTagged("g123", "x", "", "", ""); return e },
		func() error { _, e := b.SendSticker("g123", "/nonexistent", false); return e },
		func() error { return b.EditMessage("g123", "id", "x") },
		func() error { return b.DeleteMessage("g123", "id") },
	} {
		if f() == nil {
			t.Error("sent to a group that is not allowed")
		}
	}
	if len(b.sent) != 0 {
		t.Error("a refused send took a place")
	}
}
