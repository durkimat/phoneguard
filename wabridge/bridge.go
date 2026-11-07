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
	"strings"
	"sync"
	"time"

	_ "golang.org/x/crypto/x509roots/fallback" // Android has no CA bundle where Go looks for one
	"google.golang.org/protobuf/proto"
	_ "modernc.org/sqlite"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
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
	// OnMessage: chat is the other person's phone number (digits, with country code). fromMe is true for what this
	// account sent from any device. text is plain text.
	OnMessage(chat string, name string, text string, ts int64, fromMe bool)
}

type Bridge struct {
	cli *whatsmeow.Client
	l   Listener

	mu      sync.Mutex
	allowed map[string]bool
	perMin  int
	perDay  int
	sent    []time.Time
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
	b := &Bridge{l: l, allowed: map[string]bool{}, perMin: 5, perDay: 200}
	b.cli = whatsmeow.NewClient(device, log)
	b.cli.AddEventHandler(b.onEvent)
	return b, nil
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

// Logout unlinks this device from the account and forgets everything.
func (b *Bridge) Logout() error { return b.cli.Logout(context.Background()) }

// SetAllowed sets the numbers that may be read and written to: separated by commas, semicolons or new lines,
// written any way (spaces and brackets are ignored).
func (b *Bridge) SetAllowed(numbers string) {
	m := map[string]bool{}
	for _, f := range strings.FieldsFunc(numbers, func(r rune) bool { return r == ',' || r == ';' || r == '\n' }) {
		if d := digits(f); d != "" {
			m[d] = true
		}
	}
	b.mu.Lock()
	b.allowed = m
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
	b.mu.Lock()
	ok := b.allowed[n]
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
	_, err := b.cli.SendMessage(context.Background(), types.NewJID(n, types.DefaultUserServer), &waE2E.Message{Conversation: proto.String(text)})
	return err
}

func (b *Bridge) isAllowed(n string) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.allowed[n]
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
	case *events.Message:
		b.onMessage(v)
	}
}

func (b *Bridge) onMessage(v *events.Message) {
	if v.Info.IsGroup || v.Info.Chat.Server == types.BroadcastServer || v.Info.Chat.Server == types.NewsletterServer {
		return // groups, broadcast lists and channels are never read
	}
	msg := v.Message
	text := msg.GetConversation()
	if text == "" {
		text = msg.GetExtendedTextMessage().GetText()
	}
	if text == "" {
		return // only text
	}
	chat := b.phoneOf(v.Info.Chat, v.Info.RecipientAlt, v.Info.SenderAlt, v.Info.IsFromMe)
	if chat == "" || !b.isAllowed(chat) {
		return
	}
	b.l.OnMessage(chat, v.Info.PushName, text, v.Info.Timestamp.Unix(), v.Info.IsFromMe)
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
