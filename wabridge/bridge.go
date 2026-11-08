// Package wabridge is the WhatsApp side of the Phone Guard launcher: a linked-device client (whatsmeow)
// that runs inside the app on the phone. Nothing here talks to Home Assistant.
//
// Privacy rules are enforced here, before anything reaches the app: only text, only one-to-one chats,
// and only numbers on the allowlist are passed on; everything else is dropped without being kept.
// The API is deliberately small and uses only simple types so gomobile can expose it to Kotlin.
package wabridge

import (
	"context"
	"database/sql"
	"errors"
	"net"
	"sort"
	"strings"
	"sync"
	"time"

	_ "golang.org/x/crypto/x509roots/fallback" // Android has no CA bundle where Go looks for one
	"google.golang.org/protobuf/proto"
	_ "modernc.org/sqlite"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/proto/waHistorySync"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
)

// Listener is implemented by the app. Calls come from background threads.
type Listener interface {
	// OnStatus: "connected", "disconnected", "paired", "logged_out" or "error: ...".
	OnStatus(state string)
	// OnPairCode delivers the 8-character code to type under WhatsApp > Linked devices > Link with phone number.
	OnPairCode(code string)
	// OnMessage: chat is the other person's phone number (digits, with country code), or "g" followed by the
	// group's id. chatName is the person's or the group's name, sender who wrote it (for a group), fromMe is true
	// for what this account sent from any device. text is plain text; other kinds arrive as "[photo]" style markers.
	// history is true for messages that come from the history WhatsApp sends when a device is linked: they are
	// old, so the app should not count them as unread.
	OnMessage(chat string, chatName string, sender string, text string, ts int64, fromMe bool, history bool)
	// OnCall: someone is calling on WhatsApp (chat as above). rejected says the bridge has already declined it.
	// Voice itself is not carried by this library: calls are only noticed, and declined or left to ring out.
	OnCall(chat string, chatName string, ts int64, rejected bool)
}

type Bridge struct {
	cli *whatsmeow.Client
	db  *sql.DB
	l   Listener

	mu          sync.Mutex
	allowed     map[string]bool // phone numbers (digits)
	groups      map[string]bool // group names, lower case
	allowAll    bool
	rejectCalls bool
	gnames      map[string]string // group id -> name, cached
	perMin      int
	perDay      int
	sent        []time.Time
}

var setupOnce sync.Once

// Open prepares the client with its data in dir (the app's private files directory). It does not connect.
func Open(dir string, l Listener) (*Bridge, error) {
	setupOnce.Do(func() {
		// Android has no /etc/resolv.conf for Go's resolver
		net.DefaultResolver = &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, n, a string) (net.Conn, error) {
			d := net.Dialer{Timeout: 5 * time.Second}
			return d.DialContext(ctx, "udp", "1.1.1.1:53")
		}}
		store.SetOSInfo("PhoneGuard", [3]uint32{0, 1, 0})
		// ask for a month of recent history, not everything: this phone is small and the chats it shows are short
		if h := store.DeviceProps.HistorySyncConfig; h != nil {
			h.RecentSyncDaysLimit = proto.Uint32(30)
			h.FullSyncDaysLimit = proto.Uint32(30)
			h.FullSyncSizeMbLimit = proto.Uint32(20)
			h.StorageQuotaMb = proto.Uint32(50)
		}
	})
	ctx := context.Background()
	log := waLog.Noop
	// one connection, WAL and a long busy timeout: the spike saw "database is locked" while syncing after pairing
	db, err := sql.Open("sqlite", "file:"+dir+"/wa.db?_pragma=foreign_keys(1)&_pragma=busy_timeout(15000)&_pragma=journal_mode(WAL)")
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	container := sqlstore.NewWithDB(db, "sqlite", log)
	if err := container.Upgrade(ctx); err != nil {
		return nil, err
	}
	device, err := container.GetFirstDevice(ctx)
	if err != nil {
		return nil, err
	}
	b := &Bridge{db: db, l: l, allowed: map[string]bool{}, groups: map[string]bool{}, gnames: map[string]string{}, perMin: 5, perDay: 200}
	b.cli = whatsmeow.NewClient(device, log)
	b.cli.AddEventHandler(b.onEvent)
	return b, nil
}

// Close disconnects and releases the data files. The Bridge must not be used afterwards; call Open again.
func (b *Bridge) Close() {
	b.cli.Disconnect()
	if b.db != nil {
		_ = b.db.Close()
	}
}

// IsPaired says whether an account is linked.
func (b *Bridge) IsPaired() bool { return b.cli.Store.ID != nil }

// PairWithPhone starts linking by phone-number code. number is the account's number with country code (digits only).
// The code arrives through OnPairCode; the owner types it into WhatsApp on their own phone.
func (b *Bridge) PairWithPhone(number string) error {
	if b.IsPaired() {
		return errors.New("already linked")
	}
	n := digits(number)
	if len(n) < 8 {
		return errors.New("number looks too short")
	}
	ctx := context.Background()
	if !b.cli.IsConnected() {
		if err := b.cli.Connect(); err != nil {
			return err
		}
	}
	code, err := b.cli.PairPhone(ctx, n, true, whatsmeow.PairClientChrome, "Chrome (Linux)")
	if err != nil {
		return err
	}
	b.l.OnPairCode(code)
	return nil
}

// Connect opens the connection (when linked). Disconnect closes it. Both are safe to repeat.
func (b *Bridge) Connect() error {
	if !b.IsPaired() || b.cli.IsConnected() {
		return nil
	}
	return b.cli.Connect()
}

func (b *Bridge) Disconnect() { b.cli.Disconnect() }

func (b *Bridge) IsConnected() bool { return b.cli.IsConnected() }

// Logout unlinks this device from the account and forgets everything. WhatsApp must be told over a live connection,
// so it connects first if needed (the bridge is usually disconnected to save battery).
func (b *Bridge) Logout() error {
	if !b.cli.IsConnected() {
		if err := b.cli.Connect(); err != nil {
			return err
		}
		for i := 0; i < 40 && !b.cli.IsConnected(); i++ {
			time.Sleep(250 * time.Millisecond)
		}
		if !b.cli.IsConnected() {
			return errors.New("could not reach WhatsApp")
		}
	}
	return b.cli.Logout(context.Background())
}

// SetAllowed sets who may be read and written to. Entries are separated by commas, semicolons or new lines: a phone
// number written any way (spaces and brackets are ignored) allows that person, and "g:Family" allows the group
// with that name. SetAllowAll(true) lifts the restriction for people and groups; channels, status updates and
// broadcast lists stay out either way.
func (b *Bridge) SetAllowed(entries string) {
	m := map[string]bool{}
	g := map[string]bool{}
	for _, f := range strings.FieldsFunc(entries, func(r rune) bool { return r == ',' || r == ';' || r == '\n' }) {
		f = strings.TrimSpace(f)
		if strings.HasPrefix(strings.ToLower(f), "g:") {
			if name := strings.ToLower(strings.TrimSpace(f[2:])); name != "" {
				g[name] = true
			}
			continue
		}
		if d := digits(f); d != "" {
			m[d] = true
		}
	}
	b.mu.Lock()
	b.allowed, b.groups = m, g
	b.mu.Unlock()
}

// SetRejectCalls makes the bridge decline every incoming WhatsApp call (for modes that block incoming calls).
func (b *Bridge) SetRejectCalls(on bool) {
	b.mu.Lock()
	b.rejectCalls = on
	b.mu.Unlock()
}

// SetAllowAll allows every one-to-one chat and every group.
func (b *Bridge) SetAllowAll(all bool) {
	b.mu.Lock()
	b.allowAll = all
	b.mu.Unlock()
}

// SetLimits caps how many messages this phone may send (per minute and per day).
func (b *Bridge) SetLimits(perMinute, perDay int) {
	b.mu.Lock()
	b.perMin, b.perDay = perMinute, perDay
	b.mu.Unlock()
}

// Send sends a plain text message to an allowed number.
func (b *Bridge) Send(chat string, text string) error {
	n := digits(chat)
	text = strings.TrimSpace(text)
	if text == "" {
		return errors.New("empty message")
	}
	if len([]rune(text)) > 1000 {
		return errors.New("message too long")
	}
	isGroup := strings.HasPrefix(chat, "g")
	b.mu.Lock()
	ok := b.allowAll || b.allowed[n]
	if isGroup {
		ok = b.allowAll || b.groups[strings.ToLower(b.gnames[chat[1:]])]
	}
	now := time.Now()
	keep := b.sent[:0]
	minute, day := 0, 0
	for _, t := range b.sent {
		if now.Sub(t) < 24*time.Hour {
			keep = append(keep, t)
			day++
			if now.Sub(t) < time.Minute {
				minute++
			}
		}
	}
	b.sent = keep
	if !ok {
		b.mu.Unlock()
		return errors.New("that number is not allowed")
	}
	if minute >= b.perMin || day >= b.perDay {
		b.mu.Unlock()
		return errors.New("sending too fast: try again later")
	}
	b.sent = append(b.sent, now)
	b.mu.Unlock()
	to := types.NewJID(n, types.DefaultUserServer)
	if isGroup {
		to = types.NewJID(chat[1:], types.GroupServer)
	}
	_, err := b.cli.SendMessage(context.Background(), to, &waE2E.Message{Conversation: proto.String(text)})
	return err
}

func (b *Bridge) isAllowed(n string) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.allowAll || b.allowed[n]
}

func (b *Bridge) groupAllowed(name string) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.allowAll || b.groups[strings.ToLower(name)]
}

// groupName finds a group's name (asking WhatsApp once, then remembering it).
func (b *Bridge) groupName(j types.JID) string {
	b.mu.Lock()
	n, ok := b.gnames[j.User]
	b.mu.Unlock()
	if ok {
		return n
	}
	name := ""
	if b.cli != nil {
		if info, err := b.cli.GetGroupInfo(context.Background(), j); err == nil {
			name = info.Name
		}
	}
	b.mu.Lock()
	b.gnames[j.User] = name
	b.mu.Unlock()
	return name
}

func (b *Bridge) onEvent(evt interface{}) {
	switch v := evt.(type) {
	case *events.Connected:
		b.l.OnStatus("connected")
	case *events.Disconnected:
		b.l.OnStatus("disconnected")
	case *events.PairSuccess:
		b.l.OnStatus("paired")
	case *events.LoggedOut:
		b.l.OnStatus("logged_out")
	case *events.CallOffer:
		b.onCall(v)
	case *events.Message:
		b.deliver(v, false)
	case *events.HistorySync:
		b.onHistory(v)
	}
}

// textOf turns a message into what the Nokia screen shows: plain text as it is, anything else as a marker such as
// "[photo]" (with its caption), so the reader knows there was more. Reactions, edits and the like give "".
func textOf(msg *waE2E.Message) string {
	if msg == nil {
		return ""
	}
	if t := msg.GetConversation(); t != "" {
		return t
	}
	if t := msg.GetExtendedTextMessage().GetText(); t != "" {
		return t
	}
	marker := func(m, caption string) string {
		if caption != "" {
			return m + " " + caption
		}
		return m
	}
	switch {
	case msg.GetImageMessage() != nil:
		return marker("[photo]", msg.GetImageMessage().GetCaption())
	case msg.GetVideoMessage() != nil:
		return marker("[video]", msg.GetVideoMessage().GetCaption())
	case msg.GetAudioMessage() != nil:
		if msg.GetAudioMessage().GetPTT() {
			return "[voice note]"
		}
		return "[audio]"
	case msg.GetDocumentMessage() != nil:
		return marker("[document]", msg.GetDocumentMessage().GetCaption())
	case msg.GetStickerMessage() != nil:
		return "[sticker]"
	case msg.GetLocationMessage() != nil || msg.GetLiveLocationMessage() != nil:
		return "[location]"
	case msg.GetContactMessage() != nil || msg.GetContactsArrayMessage() != nil:
		return "[contact]"
	}
	return ""
}

func (b *Bridge) onCall(v *events.CallOffer) {
	b.mu.Lock()
	reject := b.rejectCalls
	b.mu.Unlock()
	rejected := false
	if reject && b.cli != nil {
		rejected = b.cli.RejectCall(context.Background(), v.CallCreator, v.CallID) == nil
	}
	chat := ""
	name := ""
	if !v.GroupJID.IsEmpty() {
		name = b.groupName(v.GroupJID)
		if !b.groupAllowed(name) {
			return
		}
		chat = "g" + v.GroupJID.User
		if name == "" {
			name = "Group"
		}
	} else {
		chat = b.phoneOf(v.CallCreator, types.JID{}, v.CallCreatorAlt, false)
		if chat == "" || !b.isAllowed(chat) {
			return
		}
	}
	b.l.OnCall(chat, name, v.Timestamp.Unix(), rejected)
}

// onMessage handles a live message.
func (b *Bridge) onMessage(v *events.Message) { b.deliver(v, false) }

func (b *Bridge) deliver(v *events.Message, history bool) {
	srv := v.Info.Chat.Server
	if srv == types.BroadcastServer || srv == types.NewsletterServer {
		return // status updates, broadcast lists and channels are never read
	}
	text := textOf(v.Message)
	if text == "" {
		return
	}
	if v.Info.IsGroup {
		name := b.groupName(v.Info.Chat)
		if !b.groupAllowed(name) {
			return
		}
		who := v.Info.PushName
		if who == "" { // history often has no display name: fall back to the number, which the app can match to a contact
			who = b.phoneOf(v.Info.Sender, types.JID{}, v.Info.SenderAlt, false)
		}
		if v.Info.IsFromMe {
			who = "Me"
		}
		if name == "" {
			name = "Group"
		}
		b.l.OnMessage("g"+v.Info.Chat.User, name, who, text, v.Info.Timestamp.Unix(), v.Info.IsFromMe, history)
		return
	}
	chat := b.phoneOf(v.Info.Chat, v.Info.RecipientAlt, v.Info.SenderAlt, v.Info.IsFromMe)
	if chat == "" || !b.isAllowed(chat) {
		return
	}
	b.l.OnMessage(chat, v.Info.PushName, v.Info.PushName, text, v.Info.Timestamp.Unix(), v.Info.IsFromMe, history)
}

// phoneOf finds the phone number of the other person in a one-to-one chat, whichever way WhatsApp addressed it.
func (b *Bridge) phoneOf(chat, recipientAlt, senderAlt types.JID, fromMe bool) string {
	if chat.Server == types.DefaultUserServer {
		return chat.User
	}
	alt := senderAlt
	if fromMe {
		alt = recipientAlt
	}
	if alt.Server == types.DefaultUserServer {
		return alt.User
	}
	if chat.Server == types.HiddenUserServer {
		if pn, err := b.cli.Store.LIDs.GetPNForLID(context.Background(), chat); err == nil && pn.Server == types.DefaultUserServer {
			return pn.User
		}
	}
	return ""
}

func digits(s string) string {
	var sb strings.Builder
	for _, r := range s {
		if r >= '0' && r <= '9' {
			sb.WriteRune(r)
		}
	}
	return sb.String()
}

// maxHistoryPerChat is how many of each chat's most recent history messages are passed on (the app keeps 20), and
// maxHistoryChats how many chats are looked at: with "allow everyone" the bundle can hold hundreds of chats.
const (
	maxHistoryPerChat = 20
	maxHistoryChats   = 60
)

// onHistory passes on the recent messages WhatsApp sends when a device is linked, for allowed chats only, newest few
// per chat. Everything else in the bundle is dropped without being looked at further.
func (b *Bridge) onHistory(v *events.HistorySync) {
	if v == nil || v.Data == nil || b.cli == nil {
		return
	}
	convs := append([]*waHistorySync.Conversation(nil), v.Data.GetConversations()...)
	sort.SliceStable(convs, func(i, j int) bool { return convs[i].GetConversationTimestamp() > convs[j].GetConversationTimestamp() })
	looked := 0
	for _, conv := range convs {
		if looked >= maxHistoryChats {
			break
		}
		jid, err := types.ParseJID(conv.GetID())
		if err != nil || jid.Server == types.BroadcastServer || jid.Server == types.NewsletterServer {
			continue
		}
		looked++
		if !b.historyChatAllowed(jid, conv.GetPnJID()) {
			continue
		}
		msgs := conv.GetMessages()
		if len(msgs) > maxHistoryPerChat { // the bundle may list oldest or newest first: keep the newest by time
			sorted := append([]*waHistorySync.HistorySyncMsg(nil), msgs...)
			sort.Slice(sorted, func(i, j int) bool {
				return sorted[i].GetMessage().GetMessageTimestamp() > sorted[j].GetMessage().GetMessageTimestamp()
			})
			msgs = sorted[:maxHistoryPerChat]
		}
		for _, m := range msgs {
			evt, err := b.cli.ParseWebMessage(jid, m.GetMessage())
			if err != nil || evt == nil {
				continue
			}
			if jid.Server == types.HiddenUserServer && conv.GetPnJID() != "" { // let the number resolve from the bundle itself
				if pn, err := types.ParseJID(conv.GetPnJID()); err == nil {
					evt.Info.SenderAlt, evt.Info.RecipientAlt = pn, pn
				}
			}
			b.deliver(evt, true)
		}
	}
}

// historyChatAllowed is the cheap check made before a chat's messages are even parsed.
func (b *Bridge) historyChatAllowed(jid types.JID, pn string) bool {
	if jid.Server == types.GroupServer {
		return b.groupAllowed(b.groupName(jid))
	}
	alt := types.JID{}
	if pn != "" {
		alt, _ = types.ParseJID(pn)
	}
	chat := b.phoneOf(jid, alt, alt, false)
	return chat != "" && b.isAllowed(chat)
}
