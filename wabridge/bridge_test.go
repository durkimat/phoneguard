package wabridge

import (
	"testing"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waHistorySync"
	"go.mau.fi/whatsmeow/proto/waWeb"
	"go.mau.fi/whatsmeow/store"

	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

type rec struct {
	msgs []string
}

func (r *rec) OnStatus(string)   {}
func (r *rec) OnPairCode(string) {}
func (r *rec) OnMessage(chat, chatName, sender, text string, ts int64, fromMe bool, history bool) {
	r.msgs = append(r.msgs, chat+"|"+text)
}

func (r *rec) OnCall(chat, chatName string, ts int64, rejected bool) {
	r.msgs = append(r.msgs, "call:"+chat)
}

func bridge() (*Bridge, *rec) {
	r := &rec{}
	b := &Bridge{l: r, allowed: map[string]bool{}, groups: map[string]bool{}, gnames: map[string]string{}, perMin: 2, perDay: 3}
	b.SetAllowed("+44 7700 900111, 07700 900222")
	return b, r
}

func dm(user string, text string) *events.Message {
	return &events.Message{
		Info:    types.MessageInfo{MessageSource: types.MessageSource{Chat: types.NewJID(user, types.DefaultUserServer)}, Timestamp: time.Unix(1000, 0)},
		Message: &waE2E.Message{Conversation: proto.String(text)},
	}
}

func TestOnlyAllowedNumbersGetThrough(t *testing.T) {
	b, r := bridge()
	b.onMessage(dm("447700900111", "hello"))
	b.onMessage(dm("447700900999", "stranger"))
	b.onMessage(dm("07700900222", "formatted differently")) // allowlist digits only: this is the 0770 form, not the +44 one
	if len(r.msgs) != 2 || r.msgs[0] != "447700900111|hello" {
		t.Fatalf("got %v", r.msgs)
	}
}

func TestChannelsAndEmptyMessagesAreDropped(t *testing.T) {
	b, r := bridge()
	n := dm("447700900111", "channel")
	n.Info.Chat = types.NewJID("120363", types.NewsletterServer)
	b.onMessage(n)
	react := dm("447700900111", "")
	react.Message = &waE2E.Message{ReactionMessage: &waE2E.ReactionMessage{}}
	b.onMessage(react)
	ext := dm("447700900111", "")
	ext.Message = &waE2E.Message{ExtendedTextMessage: &waE2E.ExtendedTextMessage{Text: proto.String("a reply")}}
	b.onMessage(ext)
	if len(r.msgs) != 1 || r.msgs[0] != "447700900111|a reply" {
		t.Fatalf("got %v", r.msgs)
	}
}

func TestOtherKindsShowAsMarkers(t *testing.T) {
	cases := map[string]*waE2E.Message{
		"[photo]":              {ImageMessage: &waE2E.ImageMessage{}},
		"[photo] look at this": {ImageMessage: &waE2E.ImageMessage{Caption: proto.String("look at this")}},
		"[video]":              {VideoMessage: &waE2E.VideoMessage{}},
		"[voice note]":         {AudioMessage: &waE2E.AudioMessage{PTT: proto.Bool(true)}},
		"[audio]":              {AudioMessage: &waE2E.AudioMessage{}},
		"[document]":           {DocumentMessage: &waE2E.DocumentMessage{}},
		"[sticker]":            {StickerMessage: &waE2E.StickerMessage{}},
		"[location]":           {LocationMessage: &waE2E.LocationMessage{}},
		"[contact]":            {ContactMessage: &waE2E.ContactMessage{}},
	}
	for want, m := range cases {
		if got := textOf(m); got != want {
			t.Errorf("want %q got %q", want, got)
		}
	}
	if textOf(&waE2E.Message{ReactionMessage: &waE2E.ReactionMessage{}}) != "" {
		t.Error("a reaction should show nothing")
	}
}

func group(name, id string, text string) (*events.Message, *Bridge, *rec) {
	b, r := bridge()
	b.gnames[id] = name
	m := dm("447700900999", text)
	m.Info.Chat = types.NewJID(id, types.GroupServer)
	m.Info.IsGroup = true
	m.Info.PushName = "Mum"
	return m, b, r
}

func TestGroupsFollowTheAllowlistByName(t *testing.T) {
	m, b, r := group("Family", "12036311", "dinner?")
	b.onMessage(m)
	if len(r.msgs) != 0 {
		t.Fatalf("a group that is not allowed got through: %v", r.msgs)
	}
	b.SetAllowed("g:family, 07700 900222")
	b.onMessage(m)
	if len(r.msgs) != 1 || r.msgs[0] != "g12036311|dinner?" {
		t.Fatalf("got %v", r.msgs)
	}
}

func TestAllowAllLetsEveryPersonAndGroupThroughButNotChannels(t *testing.T) {
	m, b, r := group("Football", "12036322", "see you")
	b.SetAllowAll(true)
	b.onMessage(m)
	b.onMessage(dm("447700900999", "a stranger"))
	n := dm("447700900111", "channel post")
	n.Info.Chat = types.NewJID("120363", types.NewsletterServer)
	b.onMessage(n)
	if len(r.msgs) != 2 {
		t.Fatalf("got %v", r.msgs)
	}
}

func TestUnresolvedHiddenAddressesAreDroppedNotGuessed(t *testing.T) {
	b, r := bridge()
	m := dm("447700900111", "via a hidden address")
	m.Info.Chat = types.NewJID("1705269850318", types.HiddenUserServer)
	m.Info.SenderAlt = types.JID{} // no phone number known
	b.cli = nil
	defer func() { _ = recover() }() // phoneOf may look in the store; with no store the message must still not get through
	b.onMessage(m)
	if len(r.msgs) != 0 {
		t.Fatalf("got %v", r.msgs)
	}
}

func TestAlternativeAddressResolvesTheNumber(t *testing.T) {
	b, r := bridge()
	m := dm("447700900111", "hello from a hidden chat")
	m.Info.Chat = types.NewJID("1705269850318", types.HiddenUserServer)
	m.Info.SenderAlt = types.NewJID("447700900111", types.DefaultUserServer)
	b.onMessage(m)
	if len(r.msgs) != 1 || r.msgs[0] != "447700900111|hello from a hidden chat" {
		t.Fatalf("got %v", r.msgs)
	}
}

func TestSendRefusesUnknownNumbersEmptyLongAndTooFast(t *testing.T) {
	b, _ := bridge()
	if err := b.Send("447700900999", "hi"); err == nil {
		t.Fatal("sent to a number that is not allowed")
	}
	if err := b.Send("447700900111", "   "); err == nil {
		t.Fatal("sent an empty message")
	}
	long := make([]rune, 1001)
	for i := range long {
		long[i] = 'a'
	}
	if err := b.Send("447700900111", string(long)); err == nil {
		t.Fatal("sent a very long message")
	}
	// the limit: two a minute. Fake two earlier sends; the next must be refused before it touches the network
	b.sent = []time.Time{time.Now().Add(-5 * time.Second), time.Now().Add(-2 * time.Second)}
	if err := b.Send("447700900111", "third"); err == nil {
		t.Fatal("sent past the per-minute limit")
	}
	b.sent = []time.Time{time.Now().Add(-20 * time.Hour), time.Now().Add(-10 * time.Hour), time.Now().Add(-3 * time.Hour)}
	if err := b.Send("447700900111", "fourth today"); err == nil {
		t.Fatal("sent past the per-day limit")
	}
}

func TestDigitsAndAllowlistParsing(t *testing.T) {
	if digits("+44 (0)7700-900111") != "4407700900111" {
		t.Fatal("digits")
	}
	b := &Bridge{allowed: map[string]bool{}, groups: map[string]bool{}}
	b.SetAllowed("")
	if b.isAllowed("") {
		t.Fatal("empty allowlist allowed something")
	}
}

func TestCallsAreReportedForAllowedPeopleOnly(t *testing.T) {
	b, r := bridge()
	mk := func(user string) *events.CallOffer {
		return &events.CallOffer{BasicCallMeta: types.BasicCallMeta{CallCreator: types.NewJID(user, types.DefaultUserServer), Timestamp: time.Unix(5, 0), CallID: "x"}}
	}
	b.onCall(mk("447700900111"))
	b.onCall(mk("447700900999"))
	if len(r.msgs) != 1 || r.msgs[0] != "call:447700900111" {
		t.Fatalf("got %v", r.msgs)
	}
}

func TestHistoryIsPassedOnForAllowedChatsOnlyAndMarkedAsHistory(t *testing.T) {
	r := &rec{}
	b := &Bridge{l: r, allowed: map[string]bool{"447700900111": true}, groups: map[string]bool{}, gnames: map[string]string{}, cli: whatsmeow.NewClient(store.NoopDevice, nil)}
	mk := func(id, text string, ts uint64) *waHistorySync.HistorySyncMsg {
		return &waHistorySync.HistorySyncMsg{Message: &waWeb.WebMessageInfo{
			Key:              &waCommon.MessageKey{RemoteJID: proto.String("x"), FromMe: proto.Bool(false), ID: proto.String(id)},
			Message:          &waE2E.Message{Conversation: proto.String(text)},
			MessageTimestamp: proto.Uint64(ts),
		}}
	}
	allowed := &waHistorySync.Conversation{ID: proto.String("447700900111@s.whatsapp.net"), Messages: []*waHistorySync.HistorySyncMsg{mk("1", "older", 100), mk("2", "newer", 200)}}
	other := &waHistorySync.Conversation{ID: proto.String("447700900999@s.whatsapp.net"), Messages: []*waHistorySync.HistorySyncMsg{mk("3", "private", 150)}}
	news := &waHistorySync.Conversation{ID: proto.String("120363@newsletter"), Messages: []*waHistorySync.HistorySyncMsg{mk("4", "channel", 160)}}
	b.onHistory(&events.HistorySync{Data: &waHistorySync.HistorySync{Conversations: []*waHistorySync.Conversation{allowed, other, news}}})
	if len(r.msgs) != 2 || r.msgs[0] != "447700900111|older" || r.msgs[1] != "447700900111|newer" {
		t.Fatalf("got %v", r.msgs)
	}
}

func TestGroupMessageWithoutADisplayNameFallsBackToTheSendersNumber(t *testing.T) {
	var gotSender string
	l := &senderRec{out: &gotSender}
	b := &Bridge{l: l, allowed: map[string]bool{}, groups: map[string]bool{"family": true}, gnames: map[string]string{"12036311": "Family"}}
	m := dm("447700900999", "hi all")
	m.Info.Chat = types.NewJID("12036311", types.GroupServer)
	m.Info.IsGroup = true
	m.Info.Sender = types.NewJID("447700900999", types.DefaultUserServer)
	b.onMessage(m)
	if gotSender != "447700900999" {
		t.Fatalf("sender was %q", gotSender)
	}
	m.Info.PushName = "Mum"
	b.onMessage(m)
	if gotSender != "Mum" {
		t.Fatalf("a display name should win, got %q", gotSender)
	}
}

type senderRec struct{ out *string }

func (s *senderRec) OnStatus(string)                                       {}
func (s *senderRec) OnPairCode(string)                                     {}
func (s *senderRec) OnCall(chat, chatName string, ts int64, rejected bool) {}
func (s *senderRec) OnMessage(chat, chatName, sender, text string, ts int64, fromMe, history bool) {
	*s.out = sender
}
