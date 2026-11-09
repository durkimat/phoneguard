package wabridge

import (
	"context"
	"fmt"
	"testing"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waHistorySync"
	"go.mau.fi/whatsmeow/proto/waSyncAction"
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
func (r *rec) OnMessage(m *Message) {
	r.msgs = append(r.msgs, m.Chat+"|"+m.Text)
}

func (r *rec) OnCall(chat, chatName string, ts int64, rejected bool) {
	r.msgs = append(r.msgs, "call:"+chat)
}

func (r *rec) OnLog(string) {}

func (r *rec) OnRead(chat string, ts int64) {
	r.msgs = append(r.msgs, fmt.Sprintf("read:%s@%d", chat, ts))
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
func (s *senderRec) OnRead(chat string, ts int64)                          {}
func (s *senderRec) OnLog(line string)                                     {}
func (s *senderRec) OnMessage(m *Message) {
	*s.out = m.Sender
}

type fakeContacts struct{ byUser map[string]types.ContactInfo }

func (f fakeContacts) PutPushName(ctx context.Context, u types.JID, n string) (bool, string, error) {
	return false, "", nil
}
func (f fakeContacts) PutBusinessName(ctx context.Context, u types.JID, n string) (bool, string, error) {
	return false, "", nil
}
func (f fakeContacts) PutContactName(ctx context.Context, u types.JID, a, b string) error { return nil }
func (f fakeContacts) PutAllContactNames(ctx context.Context, c []store.ContactEntry) error {
	return nil
}
func (f fakeContacts) PutManyRedactedPhones(ctx context.Context, e []store.RedactedPhoneEntry) error {
	return nil
}
func (f fakeContacts) GetContact(ctx context.Context, u types.JID) (types.ContactInfo, error) {
	if i, ok := f.byUser[u.User]; ok {
		i.Found = true
		return i, nil
	}
	return types.ContactInfo{}, nil
}
func (f fakeContacts) GetAllContacts(ctx context.Context) (map[types.JID]types.ContactInfo, error) {
	return nil, nil
}

func TestSenderNamePrefersTheAddressBookThenTheDisplayNameThenTheMessage(t *testing.T) {
	d := *store.NoopDevice
	d.Contacts = fakeContacts{byUser: map[string]types.ContactInfo{
		"447700900111": {FullName: "Mum Durkin", PushName: "Sarah"},
		"447700900222": {PushName: "Joe from the club"},
		"447700900333": {BusinessName: "Pizza Place"},
	}}
	b := &Bridge{cli: whatsmeow.NewClient(&d, nil)}
	jid := func(u string) types.JID { return types.NewJID(u, types.DefaultUserServer) }
	if got := b.nameOf(jid("447700900111"), types.JID{}, "x"); got != "Mum Durkin" {
		t.Errorf("address book name should win, got %q", got)
	}
	if got := b.nameOf(jid("447700900222"), types.JID{}, "x"); got != "Joe from the club" {
		t.Errorf("display name, got %q", got)
	}
	if got := b.nameOf(jid("447700900333"), types.JID{}, ""); got != "Pizza Place" {
		t.Errorf("business name, got %q", got)
	}
	if got := b.nameOf(jid("447700900999"), types.JID{}, "From message"); got != "From message" {
		t.Errorf("the message's own name when the store knows nothing, got %q", got)
	}
	// the other address WhatsApp may use for the same person is tried too
	hidden := types.NewJID("1705269850318", types.HiddenUserServer)
	if got := b.nameOf(hidden, jid("447700900222"), ""); got != "Joe from the club" {
		t.Errorf("alternative address, got %q", got)
	}
}

type fullRec struct{ got []*Message }

func (f *fullRec) OnStatus(string)                                       {}
func (f *fullRec) OnPairCode(string)                                     {}
func (f *fullRec) OnCall(chat, chatName string, ts int64, rejected bool) {}
func (f *fullRec) OnRead(chat string, ts int64)                          {}
func (f *fullRec) OnLog(line string)                                     {}
func (f *fullRec) OnMessage(m *Message)                                  { f.got = append(f.got, m) }

func TestMessagesCarryTheirIdAndTheSendersNumber(t *testing.T) {
	f := &fullRec{}
	b := &Bridge{l: f, allowed: map[string]bool{"447700900111": true}, groups: map[string]bool{"family": true}, gnames: map[string]string{"12036311": "Family"}}
	d := dm("447700900111", "hello")
	d.Info.ID = "ABC123"
	b.onMessage(d)
	g := dm("447700900999", "in the group")
	g.Info.Chat = types.NewJID("12036311", types.GroupServer)
	g.Info.IsGroup = true
	g.Info.Sender = types.NewJID("447700900999", types.DefaultUserServer)
	g.Info.ID = "GRP9"
	b.onMessage(g)
	if len(f.got) != 2 {
		t.Fatalf("got %d messages", len(f.got))
	}
	if f.got[0].ID != "ABC123" || f.got[0].SenderID != "447700900111" || f.got[0].Chat != "447700900111" {
		t.Errorf("direct message fields: %+v", f.got[0])
	}
	if f.got[1].ID != "GRP9" || f.got[1].SenderID != "447700900999" || f.got[1].Chat != "g12036311" || f.got[1].ChatName != "Family" {
		t.Errorf("group message fields: %+v", f.got[1])
	}
}

func TestNameForTriesBothAddressesAndGivesNothingWhenUnknown(t *testing.T) {
	d := *store.NoopDevice
	d.Contacts = fakeContacts{byUser: map[string]types.ContactInfo{"447700900111": {FullName: "Mum Durkin"}}}
	b := &Bridge{cli: whatsmeow.NewClient(&d, nil)}
	if b.NameFor("+44 7700 900111") != "Mum Durkin" {
		t.Error("a number written with spaces should still find the name")
	}
	if b.NameFor("447700900999") != "" || b.NameFor("") != "" {
		t.Error("an unknown number should give no name")
	}
}

func TestReadOnAnotherDeviceClearsTheChat(t *testing.T) {
	b, r := bridge()
	at := time.Unix(1_700_000_000, 0)
	// the main phone marks a one-to-one chat read
	b.onEvent(&events.MarkChatAsRead{JID: types.NewJID("447700900111", types.DefaultUserServer), Timestamp: at, Action: &waSyncAction.MarkChatAsReadAction{Read: proto.Bool(true)}})
	// ... and a group chat
	b.onEvent(&events.MarkChatAsRead{JID: types.NewJID("12345", types.GroupServer), Timestamp: at, Action: &waSyncAction.MarkChatAsReadAction{Read: proto.Bool(true)}})
	// marking unread does nothing
	b.onEvent(&events.MarkChatAsRead{JID: types.NewJID("447700900111", types.DefaultUserServer), Timestamp: at, Action: &waSyncAction.MarkChatAsReadAction{Read: proto.Bool(false)}})
	// our own read notice from another device, and a read receipt that is not from us (someone reading what we sent)
	b.onEvent(&events.Receipt{MessageSource: types.MessageSource{Chat: types.NewJID("447700900222", types.DefaultUserServer), IsFromMe: false}, Timestamp: at, Type: types.ReceiptTypeReadSelf})
	b.onEvent(&events.Receipt{MessageSource: types.MessageSource{Chat: types.NewJID("447700900333", types.DefaultUserServer), IsFromMe: false}, Timestamp: at, Type: types.ReceiptTypeRead})
	b.onEvent(&events.Receipt{MessageSource: types.MessageSource{Chat: types.NewJID("447700900444", types.DefaultUserServer), IsFromMe: true}, Timestamp: at, Type: types.ReceiptTypeRead})
	want := []string{"read:447700900111@1700000000", "read:g12345@1700000000", "read:447700900222@1700000000", "read:447700900444@1700000000"}
	if len(r.msgs) != len(want) {
		t.Fatalf("got %v, want %v", r.msgs, want)
	}
	for i := range want {
		if r.msgs[i] != want[i] {
			t.Fatalf("got %v, want %v", r.msgs, want)
		}
	}
}

func TestReadNoticesAreGroupedBySender(t *testing.T) {
	order, g := bySender("a\nb\n\nc", "111\n222\n333\n111")
	if len(order) != 2 || order[0] != "111" || order[1] != "222" { // the blank id is skipped
		t.Fatalf("order %v", order)
	}
	if len(g["111"]) != 2 || g["111"][0] != "a" || g["111"][1] != "c" || len(g["222"]) != 1 {
		t.Fatalf("groups %v", g)
	}
	order, g = bySender("x\ny", "")
	if len(order) != 1 || order[0] != "" || len(g[""]) != 2 { // a one-to-one chat has no senders
		t.Fatalf("dm %v %v", order, g)
	}
}
