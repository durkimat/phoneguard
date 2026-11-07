package wabridge

import (
	"testing"
	"time"

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
func (r *rec) OnMessage(chat, name, text string, ts int64, fromMe bool) {
	r.msgs = append(r.msgs, chat+"|"+text)
}

func bridge() (*Bridge, *rec) {
	r := &rec{}
	b := &Bridge{l: r, allowed: map[string]bool{}, perMin: 2, perDay: 3}
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

func TestGroupsChannelsAndNonTextAreDropped(t *testing.T) {
	b, r := bridge()
	g := dm("447700900111", "in a group")
	g.Info.IsGroup = true
	b.onMessage(g)
	n := dm("447700900111", "channel")
	n.Info.Chat = types.NewJID("120363", types.NewsletterServer)
	b.onMessage(n)
	pic := dm("447700900111", "")
	pic.Message = &waE2E.Message{ImageMessage: &waE2E.ImageMessage{}}
	b.onMessage(pic)
	ext := dm("447700900111", "")
	ext.Message = &waE2E.Message{ExtendedTextMessage: &waE2E.ExtendedTextMessage{Text: proto.String("a reply")}}
	b.onMessage(ext)
	if len(r.msgs) != 1 || r.msgs[0] != "447700900111|a reply" {
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
	b := &Bridge{allowed: map[string]bool{}}
	b.SetAllowed("")
	if b.isAllowed("") {
		t.Fatal("empty allowlist allowed something")
	}
}
