package wabridge

import (
	"context"
	"encoding/base64"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"time"

	"google.golang.org/protobuf/proto"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

// Limits. Pictures, stickers and GIFs arrive by themselves up to MaxAutoMedia; a video is fetched only when asked, up to MaxVideo.
// Anything larger is only described. Nothing is ever downloaded for a chat that is not allowed (deliver has already refused those).
const (
	MaxAutoMedia = 5 << 20
	MaxVideo     = 100 << 20
	maxSendFile  = 100 << 20
)

// SetMedia turns on the automatic download of images, stickers and GIFs.
func (b *Bridge) SetMedia(on bool) {
	b.mu.Lock()
	b.media = on
	b.mu.Unlock()
}

// MediaDir is the private folder downloaded media is written to (created on demand).
func (b *Bridge) MediaDir() string {
	d := filepath.Join(b.dir, "media")
	_ = os.MkdirAll(d, 0o700)
	return d
}

func extFor(mime string) string {
	switch strings.ToLower(strings.SplitN(mime, ";", 2)[0]) {
	case "image/jpeg", "image/jpg":
		return ".jpg"
	case "image/png":
		return ".png"
	case "image/webp":
		return ".webp"
	case "image/gif":
		return ".gif"
	case "video/mp4":
		return ".mp4"
	case "video/3gpp":
		return ".3gp"
	case "video/webm":
		return ".webm"
	}
	return ".bin"
}

func safeID(id string) string {
	var sb strings.Builder
	for _, r := range id {
		if r >= '0' && r <= '9' || r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r == '-' || r == '_' {
			sb.WriteRune(r)
		}
	}
	if sb.Len() == 0 {
		return "x"
	}
	return sb.String()
}

// contextOf is the reply information of whatever kind of message this is.
func contextOf(msg *waE2E.Message) *waE2E.ContextInfo {
	switch {
	case msg.GetExtendedTextMessage() != nil:
		return msg.GetExtendedTextMessage().GetContextInfo()
	case msg.GetImageMessage() != nil:
		return msg.GetImageMessage().GetContextInfo()
	case msg.GetVideoMessage() != nil:
		return msg.GetVideoMessage().GetContextInfo()
	case msg.GetStickerMessage() != nil:
		return msg.GetStickerMessage().GetContextInfo()
	case msg.GetAudioMessage() != nil:
		return msg.GetAudioMessage().GetContextInfo()
	case msg.GetDocumentMessage() != nil:
		return msg.GetDocumentMessage().GetContextInfo()
	}
	return nil
}

func refOf(m *waE2E.Message) string {
	raw, err := proto.Marshal(m)
	if err != nil {
		return ""
	}
	return base64.StdEncoding.EncodeToString(raw)
}

// describe fills the kind, size and the reference for the media in msg (not the download).
func describe(m *Message, msg *waE2E.Message) {
	m.Kind = "text"
	switch {
	case msg.GetImageMessage() != nil:
		i := msg.GetImageMessage()
		m.Kind, m.Mime, m.MediaSize, m.Width, m.Height = "image", i.GetMimetype(), int64(i.GetFileLength()), int(i.GetWidth()), int(i.GetHeight())
		m.MediaRef = refOf(&waE2E.Message{ImageMessage: i})
	case msg.GetStickerMessage() != nil:
		s := msg.GetStickerMessage()
		m.Kind, m.Mime, m.MediaSize, m.Width, m.Height, m.Animated = "sticker", s.GetMimetype(), int64(s.GetFileLength()), int(s.GetWidth()), int(s.GetHeight()), s.GetIsAnimated()
		m.MediaRef = refOf(&waE2E.Message{StickerMessage: s})
	case msg.GetVideoMessage() != nil:
		v := msg.GetVideoMessage()
		m.Kind, m.Mime, m.MediaSize, m.Width, m.Height, m.Seconds = "video", v.GetMimetype(), int64(v.GetFileLength()), int(v.GetWidth()), int(v.GetHeight()), int(v.GetSeconds())
		if v.GetGifPlayback() {
			m.Kind = "gif"
		}
		m.MediaRef = refOf(&waE2E.Message{VideoMessage: v})
	case msg.GetAudioMessage() != nil:
		m.Kind = "audio"
	case textOf(msg) != "" && msg.GetConversation() == "" && msg.GetExtendedTextMessage() == nil:
		m.Kind = "other"
	}
}

// enrich adds what the app needs beyond the text: the kind, the reply it quotes, and (for a small picture, sticker or GIF in a live message,
// with media on) the downloaded file.
func (b *Bridge) enrich(m *Message, msg *waE2E.Message) {
	describe(m, msg)
	if ci := contextOf(msg); ci != nil && ci.GetStanzaID() != "" {
		m.QuoteID = ci.GetStanzaID()
		m.QuoteText = textOf(ci.GetQuotedMessage())
		if len([]rune(m.QuoteText)) > 200 {
			m.QuoteText = string([]rune(m.QuoteText)[:200])
		}
		if p, err := types.ParseJID(ci.GetParticipant()); err == nil && !p.IsEmpty() {
			m.QuoteSender = b.nameOf(p, types.JID{}, "")
		}
	}
	b.mu.Lock()
	on := b.media
	b.mu.Unlock()
	if !on || m.History || m.MediaRef == "" || m.MediaSize <= 0 || m.MediaSize > MaxAutoMedia {
		return
	}
	if m.Kind != "image" && m.Kind != "sticker" && m.Kind != "gif" {
		return
	}
	if path, err := b.fetch(m.MediaRef, m.ID, m.Mime, MaxAutoMedia); err == nil {
		m.MediaPath = path
	}
}

func downloadable(msg *waE2E.Message) whatsmeow.DownloadableMessage {
	switch {
	case msg.GetImageMessage() != nil:
		return msg.GetImageMessage()
	case msg.GetStickerMessage() != nil:
		return msg.GetStickerMessage()
	case msg.GetVideoMessage() != nil:
		return msg.GetVideoMessage()
	}
	return nil
}

func (b *Bridge) fetch(ref, id, mime string, limit int64) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(ref)
	if err != nil {
		return "", err
	}
	var msg waE2E.Message
	if err := proto.Unmarshal(raw, &msg); err != nil {
		return "", err
	}
	d := downloadable(&msg)
	if d == nil {
		return "", errors.New("nothing to download")
	}
	path := filepath.Join(b.MediaDir(), safeID(id)+extFor(mime))
	if st, err := os.Stat(path); err == nil && st.Size() > 0 {
		return path, nil
	}
	f, err := os.OpenFile(path+".part", os.O_RDWR|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	err = b.cli.DownloadToFile(ctx, d, f)
	size := int64(0)
	if st, e := f.Stat(); e == nil {
		size = st.Size()
	}
	_ = f.Close()
	if err == nil && size > limit {
		err = errors.New("too large")
	}
	if err != nil {
		_ = os.Remove(path + ".part")
		return "", err
	}
	return path, os.Rename(path+".part", path)
}

// DownloadMedia fetches the media of a message the app already holds (a video the child tapped), up to MaxVideo, into the private media
// folder, and returns the file's path. ref and mime come from the Message; id names the file.
func (b *Bridge) DownloadMedia(ref, id, mime string) (string, error) {
	return b.fetch(ref, id, mime, MaxVideo)
}

// chatFor says which chat an event belongs to, and whether it may be passed on at all (the admin group and chats that are not allowed never are).
func (b *Bridge) chatFor(v *events.Message) (chat, name string, ok bool) {
	if v.Info.IsGroup {
		b.mu.Lock()
		admin := b.admin
		b.mu.Unlock()
		if admin != "" && v.Info.Chat.User == admin {
			return "", "", false
		}
		name = b.groupName(v.Info.Chat)
		if !b.groupAllowed(v.Info.Chat.User, name) {
			return "", "", false
		}
		if name == "" {
			name = "Group"
		}
		return "g" + v.Info.Chat.User, name, true
	}
	chat = b.phoneOf(v.Info.Chat, v.Info.RecipientAlt, v.Info.SenderAlt, v.Info.IsFromMe)
	if chat == "" || !b.isAllowed(chat) {
		return "", "", false
	}
	return chat, b.nameOf(v.Info.Chat, v.Info.SenderAlt, v.Info.PushName), true
}

func (b *Bridge) who(v *events.Message) string {
	if v.Info.IsFromMe {
		b.mu.Lock()
		defer b.mu.Unlock()
		if b.ownName != "" {
			return b.ownName
		}
		return "Me"
	}
	w := b.nameOf(v.Info.Sender, v.Info.SenderAlt, v.Info.PushName)
	if w == "" {
		w = b.phoneOf(v.Info.Sender, types.JID{}, v.Info.SenderAlt, false)
	}
	return w
}

func (b *Bridge) reaction(v *events.Message, rm *waE2E.ReactionMessage, history bool) {
	if history || rm.GetKey().GetID() == "" {
		return
	}
	chat, _, ok := b.chatFor(v)
	if !ok {
		return
	}
	b.l.OnReaction(chat, rm.GetKey().GetID(), b.senderID(v), b.who(v), rm.GetText(), v.Info.Timestamp.Unix(), v.Info.IsFromMe)
}

func (b *Bridge) protocol(v *events.Message, pm *waE2E.ProtocolMessage, history bool) {
	if history || pm.GetKey().GetID() == "" {
		return
	}
	switch pm.GetType() {
	case waE2E.ProtocolMessage_REVOKE:
		if chat, _, ok := b.chatFor(v); ok {
			b.l.OnRevoke(chat, pm.GetKey().GetID())
		}
	case waE2E.ProtocolMessage_MESSAGE_EDIT:
		if chat, _, ok := b.chatFor(v); ok {
			if t := textOf(pm.GetEditedMessage()); t != "" {
				b.l.OnEdit(chat, pm.GetKey().GetID(), t, v.Info.Timestamp.Unix())
			}
		}
	}
}

// ---- sending

// target checks the chat may be written to and gives its address.
func (b *Bridge) target(chat string) (types.JID, string, error) {
	n := digits(chat)
	isGroup := strings.HasPrefix(chat, "g")
	b.mu.Lock()
	defer b.mu.Unlock()
	ok := b.allowAll || b.allowed[n]
	if isGroup {
		ok = b.groupOKLocked(chat[1:], b.gnames[chat[1:]])
	}
	if !ok {
		return types.JID{}, "", errors.New("that number is not allowed")
	}
	if isGroup {
		return types.NewJID(chat[1:], types.GroupServer), b.tag, nil
	}
	return types.NewJID(n, types.DefaultUserServer), b.tag, nil
}

// slots takes n places in the send limits (one per item sent: a label, a sticker, a reaction, a reply are each one). Nothing is taken if they do not all fit.
func (b *Bridge) slots(n int) error {
	b.mu.Lock()
	defer b.mu.Unlock()
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
	if minute+n > b.perMin || day+n > b.perDay {
		return errors.New("sending too fast: try again later")
	}
	for i := 0; i < n; i++ {
		b.sent = append(b.sent, now)
	}
	return nil
}

// Reserve checks that n items can be sent now without taking the places (so a burst of label and sticker is refused whole, not half-sent).
func (b *Bridge) Reserve(n int) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	now := time.Now()
	minute, day := 0, 0
	for _, t := range b.sent {
		if now.Sub(t) < 24*time.Hour {
			day++
			if now.Sub(t) < time.Minute {
				minute++
			}
		}
	}
	if minute+n > b.perMin || day+n > b.perDay {
		return errors.New("sending too fast: try again later")
	}
	return nil
}

func (b *Bridge) send(to types.JID, m *waE2E.Message) (string, error) {
	r, err := b.cli.SendMessage(context.Background(), to, m)
	return r.ID, err
}

// SendText sends text as written (no name in front: the caller has already made the text what it should be) and returns the message id.
// It is for the labels in front of stickers and the like; everything else goes through SendTagged.
func (b *Bridge) SendText(chat, text string) (string, error) {
	text = strings.TrimSpace(text)
	if text == "" || len([]rune(text)) > 1000 {
		return "", errors.New("empty or too long")
	}
	to, _, err := b.target(chat)
	if err != nil {
		return "", err
	}
	if err := b.slots(1); err != nil {
		return "", err
	}
	return b.send(to, &waE2E.Message{Conversation: proto.String(text)})
}

func (b *Bridge) tagText(tag, text string) (string, error) {
	text = strings.TrimSpace(text)
	if text == "" {
		return "", errors.New("empty message")
	}
	if tag != "" {
		text = tag + ": " + text
	}
	if len([]rune(text)) > 1000 {
		return "", errors.New("message too long")
	}
	return text, nil
}

// SendTagged sends a message with the child's name in front, optionally quoting another message (quoteID "" for none), and returns its id.
// A reaction by the child is sent this way too: the emoji as the text, quoting the message it is about.
func (b *Bridge) SendTagged(chat, text, quoteID, quoteSender, quoteText string) (string, error) {
	to, tag, err := b.target(chat)
	if err != nil {
		return "", err
	}
	text, err = b.tagText(tag, text)
	if err != nil {
		return "", err
	}
	if err := b.slots(1); err != nil {
		return "", err
	}
	if quoteID == "" {
		return b.send(to, &waE2E.Message{Conversation: proto.String(text)})
	}
	ci := &waE2E.ContextInfo{StanzaID: proto.String(quoteID), QuotedMessage: &waE2E.Message{Conversation: proto.String(quoteText)}}
	if p, err := types.ParseJID(quoteSender); err == nil && !p.IsEmpty() {
		ci.Participant = proto.String(p.String())
	} else if d := digits(quoteSender); d != "" {
		ci.Participant = proto.String(types.NewJID(d, types.DefaultUserServer).String())
	}
	return b.send(to, &waE2E.Message{ExtendedTextMessage: &waE2E.ExtendedTextMessage{Text: proto.String(text), ContextInfo: ci}})
}

func readSend(path string) ([]byte, error) {
	st, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	if st.Size() > maxSendFile {
		return nil, errors.New("file too large")
	}
	return os.ReadFile(path)
}

func mimeOf(path, def string) string {
	switch strings.ToLower(filepath.Ext(path)) {
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".png":
		return "image/png"
	case ".webp":
		return "image/webp"
	case ".mp4":
		return "video/mp4"
	}
	return def
}

// SendImage sends a picture from a file with a caption (already made by the caller, name included) and returns the message id.
func (b *Bridge) SendImage(chat, path, caption string, width, height int) (string, error) {
	to, _, err := b.target(chat)
	if err != nil {
		return "", err
	}
	data, err := readSend(path)
	if err != nil {
		return "", err
	}
	if err := b.slots(1); err != nil {
		return "", err
	}
	up, err := b.cli.Upload(context.Background(), data, whatsmeow.MediaImage)
	if err != nil {
		return "", err
	}
	return b.send(to, &waE2E.Message{ImageMessage: &waE2E.ImageMessage{
		URL: &up.URL, DirectPath: &up.DirectPath, MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: &up.FileLength,
		Mimetype: proto.String(mimeOf(path, "image/jpeg")), Caption: proto.String(caption), Width: proto.Uint32(uint32(width)), Height: proto.Uint32(uint32(height)),
	}})
}

// SendVideo sends a video from a file (H.264/AAC mp4) with a caption and returns the message id.
func (b *Bridge) SendVideo(chat, path, caption string, seconds, width, height int) (string, error) {
	to, _, err := b.target(chat)
	if err != nil {
		return "", err
	}
	data, err := readSend(path)
	if err != nil {
		return "", err
	}
	if err := b.slots(1); err != nil {
		return "", err
	}
	up, err := b.cli.Upload(context.Background(), data, whatsmeow.MediaVideo)
	if err != nil {
		return "", err
	}
	return b.send(to, &waE2E.Message{VideoMessage: &waE2E.VideoMessage{
		URL: &up.URL, DirectPath: &up.DirectPath, MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: &up.FileLength,
		Mimetype: proto.String("video/mp4"), Caption: proto.String(caption), Seconds: proto.Uint32(uint32(seconds)), Width: proto.Uint32(uint32(width)), Height: proto.Uint32(uint32(height)),
	}})
}

// SendSticker sends a WebP sticker (512x512) from a file and returns the message id. A sticker cannot carry text: the caller sends its label first.
func (b *Bridge) SendSticker(chat, path string, animated bool) (string, error) {
	to, _, err := b.target(chat)
	if err != nil {
		return "", err
	}
	data, err := readSend(path)
	if err != nil {
		return "", err
	}
	if err := b.slots(1); err != nil {
		return "", err
	}
	up, err := b.cli.Upload(context.Background(), data, whatsmeow.MediaImage)
	if err != nil {
		return "", err
	}
	return b.send(to, &waE2E.Message{StickerMessage: &waE2E.StickerMessage{
		URL: &up.URL, DirectPath: &up.DirectPath, MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256, FileLength: &up.FileLength,
		Mimetype: proto.String("image/webp"), Width: proto.Uint32(512), Height: proto.Uint32(512), IsAnimated: proto.Bool(animated),
	}})
}

// EditMessage edits a message this account sent (the key says "from me", so it can never touch anyone else's) to new text, with the name in front.
func (b *Bridge) EditMessage(chat, id, text string) error {
	to, tag, err := b.target(chat)
	if err != nil {
		return err
	}
	text, err = b.tagText(tag, text)
	if err != nil {
		return err
	}
	if id == "" {
		return errors.New("no message")
	}
	_, err = b.send(to, b.cli.BuildEdit(to, id, &waE2E.Message{Conversation: proto.String(text)}))
	return err
}

// DeleteMessage deletes, for everyone, a message this account sent. The sender is left empty, which means "from me": it cannot revoke anyone else's.
func (b *Bridge) DeleteMessage(chat, id string) error {
	to, _, err := b.target(chat)
	if err != nil {
		return err
	}
	if id == "" {
		return errors.New("no message")
	}
	_, err = b.send(to, b.cli.BuildRevoke(to, types.EmptyJID, id))
	return err
}
