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
	"go.mau.fi/whatsmeow/appstate"
	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/proto/waHistorySync"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
)

// Message is one text message handed to the app. Chat is the other person's phone number (digits, with country code),
// or "g" followed by the group's id. ChatName is the person's or the group's name, Sender who wrote it (a name, else a
// number) and SenderID their number or id so names can be looked up again later, ID WhatsApp's id for the message
// (needed to ask for older ones), FromMe is true for what this account sent from any device. Text is plain text; other
// kinds arrive as "[photo]" style markers. History is true for messages from the history WhatsApp sends (on linking or
// when asked): they are old, so the app should not count them as unread.
type Message struct {
	Chat     string
	ChatName string
	Sender   string
	SenderID string
	ID       string
	Text     string
	TS       int64
	FromMe   bool
	History  bool
}

// Listener is implemented by the app. Calls come from background threads.
type Listener interface {
	// OnStatus: "connected", "disconnected", "paired", "logged_out" or "error: ...".
	OnStatus(state string)
	// OnPairCode delivers the 8-character code to type under WhatsApp > Linked devices > Link with phone number.
	OnPairCode(code string)
	// OnMessage delivers one message (see Message).
	OnMessage(m *Message)
	// OnCall: someone is calling on WhatsApp (chat as above). rejected says the bridge has already declined it.
	// Voice itself is not carried by this library: calls are only noticed, and declined or left to ring out.
	OnCall(chat string, chatName string, ts int64, rejected bool)
	// OnRead: this account read the chat on another device (the main phone, say), up to ts (unix seconds). The app should
	// clear its unread marks for that chat's messages up to then. chat is as in Message.
	OnRead(chat string, ts int64)
	// OnLog: a short diagnostic line (which read-related events arrived), with numbers masked. Only for the phone's own log.
	OnLog(line string)
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
	if err := b.ensureConnected(); err != nil {
		return err
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

// bySender splits message ids by the sender who wrote them (ids and senders are lists with one entry per line, side by side),
// keeping the order senders first appear in. WhatsApp wants one read notice per sender.
func bySender(ids, senders string) ([]string, map[string][]types.MessageID) {
	var order []string
	groups := map[string][]types.MessageID{}
	idl, sl := strings.Split(ids, "\n"), strings.Split(senders, "\n")
	for i, id := range idl {
		id = strings.TrimSpace(id)
		if id == "" {
			continue
		}
		s := ""
		if i < len(sl) {
			s = strings.TrimSpace(sl[i])
		}
		if _, seen := groups[s]; !seen {
			order = append(order, s)
		}
		groups[s] = append(groups[s], types.MessageID(id))
	}
	return order, groups
}

// MarkRead tells WhatsApp the chat was read on this phone: the main phone then shows it read too. ids and senders are what the app still
// held as unread (one per line, side by side; the sender matters only in groups). lastID, lastSender, lastFromMe and lastTS describe the
// newest message in the chat. Read notices go to the senders only when the account has read receipts on; otherwise only the account's
// other devices are told.
func (b *Bridge) MarkRead(chat, ids, senders string, lastID, lastSender string, lastFromMe bool, lastTS int64) error {
	if b.cli == nil {
		return errors.New("not connected")
	}
	ctx := context.Background()
	isGroup := strings.HasPrefix(chat, "g")
	to := types.NewJID(digits(chat), types.DefaultUserServer)
	if isGroup {
		to = types.NewJID(chat[1:], types.GroupServer)
	}
	at := time.Now()
	if lastTS > 0 {
		at = time.Unix(lastTS, 0)
	}
	var extra []types.ReceiptType
	if b.cli.GetPrivacySettings(ctx).ReadReceipts != types.PrivacySettingAll {
		extra = []types.ReceiptType{types.ReceiptTypeReadSelf}
	}
	order, groups := bySender(ids, senders)
	for _, s := range order {
		sender := types.EmptyJID
		if isGroup && s != "" {
			sender = types.NewJID(digits(s), types.DefaultUserServer)
		}
		if err := b.cli.MarkRead(ctx, groups[s], at, to, sender, extra...); err != nil {
			return err
		}
	}
	if lastID == "" {
		return nil
	}
	key := &waCommon.MessageKey{RemoteJID: proto.String(to.String()), FromMe: proto.Bool(lastFromMe), ID: proto.String(lastID)}
	if isGroup && lastSender != "" && !lastFromMe {
		key.Participant = proto.String(types.NewJID(digits(lastSender), types.DefaultUserServer).String())
	}
	return b.cli.SendAppState(ctx, appstate.BuildMarkChatAsRead(to, true, at, key))
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
	case *events.AppStateSyncComplete:
		b.l.OnLog("app state synced: " + string(v.Name))
	case *events.MarkChatAsRead:
		b.l.OnLog("chat marked " + map[bool]string{true: "read", false: "unread"}[v.Action != nil && v.Action.GetRead()] + " elsewhere: " + masked(v.JID))
		// the main phone marked a whole chat read (or unread: only "read" is acted on)
		if v.Action != nil && v.Action.GetRead() {
			b.onReadElsewhere(v.JID, v.Timestamp)
		}
	case *events.Receipt:
		// our own account read messages on another device (a "read-self" notice, or a read receipt that is from us)
		if v.Type == types.ReceiptTypeReadSelf || (v.Type == types.ReceiptTypeRead && v.IsFromMe) {
			b.l.OnLog("read notice of ours (" + string(v.Type) + "): " + masked(v.Chat))
			b.onReadElsewhere(v.Chat, v.Timestamp)
		}
	}
}

// masked shows a chat for the phone's own diagnostic log without its number: group or person, and the last three digits.
func masked(j types.JID) string {
	kind := "person"
	if j.Server == types.GroupServer {
		kind = "group"
	}
	u := j.User
	if len(u) > 3 {
		u = "..." + u[len(u)-3:]
	}
	return kind + " " + u
}

// onReadElsewhere tells the app a chat was read on another device, by the app's name for the chat.
func (b *Bridge) onReadElsewhere(chat types.JID, at time.Time) {
	if chat.IsEmpty() || chat.Server == types.BroadcastServer || chat.Server == types.NewsletterServer {
		return
	}
	var key string
	if chat.Server == types.GroupServer {
		key = "g" + chat.User
	} else {
		key = b.phoneOf(chat, types.JID{}, types.JID{}, false)
	}
	if key == "" {
		return
	}
	if at.IsZero() {
		at = time.Now()
	}
	b.l.OnRead(key, at.Unix())
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
		who := b.nameOf(v.Info.Sender, v.Info.SenderAlt, v.Info.PushName)
		if who == "" { // nobody knows a name: fall back to the number, which the app can match to a contact
			who = b.phoneOf(v.Info.Sender, types.JID{}, v.Info.SenderAlt, false)
		}
		if v.Info.IsFromMe {
			who = "Me"
		}
		if name == "" {
			name = "Group"
		}
		b.l.OnMessage(&Message{Chat: "g" + v.Info.Chat.User, ChatName: name, Sender: who, SenderID: b.senderID(v), ID: v.Info.ID, Text: text, TS: v.Info.Timestamp.Unix(), FromMe: v.Info.IsFromMe, History: history})
		return
	}
	chat := b.phoneOf(v.Info.Chat, v.Info.RecipientAlt, v.Info.SenderAlt, v.Info.IsFromMe)
	if chat == "" || !b.isAllowed(chat) {
		return
	}
	name := b.nameOf(v.Info.Chat, v.Info.SenderAlt, v.Info.PushName)
	b.l.OnMessage(&Message{Chat: chat, ChatName: name, Sender: name, SenderID: chat, ID: v.Info.ID, Text: text, TS: v.Info.Timestamp.Unix(), FromMe: v.Info.IsFromMe, History: history})
}

// nameOf finds the best name for a person: the name from the owner's own address book (WhatsApp syncs it from the
// main phone), else the display name they chose for themselves, else a business name, else what the message carried
// (pushName), else "" (the caller falls back to the number). Both addresses WhatsApp may use for a person are tried.
func (b *Bridge) nameOf(jid, alt types.JID, pushName string) string {
	if b.cli != nil && b.cli.Store != nil && b.cli.Store.Contacts != nil {
		cands := []types.JID{jid, alt}
		if jid.Server == types.HiddenUserServer && b.cli.Store.LIDs != nil {
			if pn, err := b.cli.Store.LIDs.GetPNForLID(context.Background(), jid); err == nil {
				cands = append(cands, pn)
			}
		}
		for _, c := range cands {
			if c.IsEmpty() {
				continue
			}
			info, err := b.cli.Store.Contacts.GetContact(context.Background(), c.ToNonAD())
			if err != nil || !info.Found {
				continue
			}
			for _, n := range []string{info.FullName, info.FirstName, info.PushName, info.BusinessName} {
				if strings.TrimSpace(n) != "" {
					return strings.TrimSpace(n)
				}
			}
		}
	}
	return strings.TrimSpace(pushName)
}

// senderID is the number of whoever wrote a group message (digits), or their hidden id when no number is known.
func (b *Bridge) senderID(v *events.Message) string {
	if n := b.phoneOf(v.Info.Sender, types.JID{}, v.Info.SenderAlt, false); n != "" {
		return n
	}
	return v.Info.Sender.User
}

// NameFor looks a person up again by the number the app stored for them: their name from the address book or the display
// name they chose, or "" when WhatsApp knows none. Used after a re-sync to give old messages their names.
func (b *Bridge) NameFor(id string) string {
	id = digits(id)
	if id == "" {
		return ""
	}
	if n := b.nameOf(types.NewJID(id, types.DefaultUserServer), types.JID{}, ""); n != "" {
		return n
	}
	return b.nameOf(types.NewJID(id, types.HiddenUserServer), types.JID{}, "")
}

// Resync asks WhatsApp again for the address-book names and the list of groups, without unlinking. Connects first if needed.
func (b *Bridge) Resync() error {
	if err := b.ensureConnected(); err != nil {
		return err
	}
	ctx := context.Background()
	err := b.cli.FetchAppState(ctx, appstate.WAPatchCriticalUnblockLow, true, false)
	if groups, gerr := b.cli.GetJoinedGroups(ctx); gerr == nil {
		b.mu.Lock()
		for _, g := range groups {
			b.gnames[g.JID.User] = g.Name
		}
		b.mu.Unlock()
	}
	return err
}

// RequestHistory asks the owner's main phone for up to count messages older than the one given (the oldest the app has
// for that chat). They arrive afterwards as history messages. The main phone must be online.
func (b *Bridge) RequestHistory(chat string, msgID string, ts int64, fromMe bool, count int) error {
	if msgID == "" {
		return errors.New("no message to start from")
	}
	if err := b.ensureConnected(); err != nil {
		return err
	}
	to := types.NewJID(digits(chat), types.DefaultUserServer)
	if strings.HasPrefix(chat, "g") {
		to = types.NewJID(chat[1:], types.GroupServer)
	}
	info := &types.MessageInfo{MessageSource: types.MessageSource{Chat: to, IsFromMe: fromMe}, ID: msgID, Timestamp: time.Unix(ts, 0)}
	_, err := b.cli.SendPeerMessage(context.Background(), b.cli.BuildHistorySyncRequest(info, count))
	return err
}

// ensureConnected connects if the bridge is not connected and waits a few seconds for it.
func (b *Bridge) ensureConnected() error {
	if b.cli.IsConnected() {
		return nil
	}
	if !b.IsPaired() {
		return errors.New("not linked")
	}
	if err := b.cli.Connect(); err != nil {
		return err
	}
	for i := 0; i < 40 && !b.cli.IsConnected(); i++ {
		time.Sleep(250 * time.Millisecond)
	}
	if !b.cli.IsConnected() {
		return errors.New("could not reach WhatsApp")
	}
	return nil
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
	// WhatsApp sends names (and more messages) in further bundles that can arrive after this one: tell the app each
	// time one has been dealt with, so it can look the senders' names up again
	defer b.l.OnStatus("history")
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
