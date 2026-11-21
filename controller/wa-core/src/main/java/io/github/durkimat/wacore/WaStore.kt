package io.github.durkimat.wacore

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** What the phone remembers of WhatsApp chats: the last few messages of each allowed chat, and nothing else. Private to the app. */
class WaStore(c: Context) : SQLiteOpenHelper(c, "whatsapp.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE msg (id INTEGER PRIMARY KEY AUTOINCREMENT, chat TEXT NOT NULL, ts INTEGER NOT NULL, mine INTEGER NOT NULL, text TEXT NOT NULL, unread INTEGER NOT NULL, sender_id TEXT, sender_name TEXT, mid TEXT)")
        db.execSQL("CREATE TABLE chat (number TEXT PRIMARY KEY, name TEXT)")
        v3(db, columns = true)
    }

    // v3: what a message carries beyond its text (kind, media, the message it replies to, edited/deleted) and reactions
    private fun v3(db: SQLiteDatabase, columns: Boolean) {
        if (columns) for (c in listOf("kind TEXT", "media_path TEXT", "media_ref TEXT", "media_size INTEGER", "mime TEXT", "seconds INTEGER", "w INTEGER", "h INTEGER",
            "animated INTEGER", "quote_id TEXT", "quote_text TEXT", "quote_sender TEXT", "edited INTEGER", "deleted INTEGER")) db.execSQL("ALTER TABLE msg ADD COLUMN $c")
        db.execSQL("CREATE TABLE IF NOT EXISTS react (chat TEXT NOT NULL, target TEXT NOT NULL, sender_id TEXT NOT NULL, sender TEXT, emoji TEXT NOT NULL, ts INTEGER, PRIMARY KEY (chat, target, sender_id))")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) { // v2 keeps who wrote a message and WhatsApp's id for it, apart from the text
            db.execSQL("ALTER TABLE msg ADD COLUMN sender_id TEXT"); db.execSQL("ALTER TABLE msg ADD COLUMN sender_name TEXT"); db.execSQL("ALTER TABLE msg ADD COLUMN mid TEXT")
        }
        if (o < 3) v3(db, columns = true)
    }

    data class ChatRow(val number: String, val name: String, val last: WaMsg?, val unread: Int)

    /**
     * Keeps one message. [chatName] is the person's or group's name; [sender]/[senderId] say who wrote it (shown in front of
     * group messages, and looked up again after a re-sync); [mid] is WhatsApp's id for the message.
     */
    @Synchronized fun add(
        chat: String, chatName: String, sender: String, senderId: String, mid: String, text: String, ts: Long, mine: Boolean,
        keep: Int = KEEP, history: Boolean = false, read: Boolean = false, extra: WaExtra = WaExtra(),
    ) {
        val db = writableDatabase
        // the same message can arrive twice (queued, then live, or in history): ignore a repeat
        // by id when there is one, and also by time and text, because messages kept before ids were stored have none
        val dup = if (mid.isNotEmpty()) db.rawQuery("SELECT 1 FROM msg WHERE chat=? AND (mid=? OR (ts=? AND mine=? AND (text=? OR text LIKE ?)))", arrayOf(chat, mid, ts.toString(), if (mine) "1" else "0", text, "%: $text"))
        else db.rawQuery("SELECT 1 FROM msg WHERE chat=? AND ts=? AND mine=? AND (text=? OR text LIKE ?)", arrayOf(chat, ts.toString(), if (mine) "1" else "0", text, "%: $text"))
        dup.use { if (it.moveToFirst()) return }
        db.insert("msg", null, ContentValues().apply {
            put("chat", chat); put("ts", ts); put("mine", if (mine) 1 else 0); put("text", text); put("unread", if (mine || history || read) 0 else 1)
            put("sender_id", senderId); put("sender_name", if (mine) "" else sender); put("mid", mid)
            put("kind", extra.kind); put("media_path", extra.mediaPath); put("media_ref", extra.mediaRef); put("media_size", extra.mediaSize); put("mime", extra.mime)
            put("seconds", extra.seconds); put("w", extra.width); put("h", extra.height); put("animated", if (extra.animated) 1 else 0)
            put("quote_id", extra.quoteId); put("quote_text", extra.quoteText); put("quote_sender", extra.quoteSender)
        })
        if (chatName.isNotBlank() && (!mine || chat.startsWith("g"))) db.insertWithOnConflict("chat", null, ContentValues().apply { put("number", chat); put("name", chatName) }, SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL("DELETE FROM msg WHERE chat=? AND id NOT IN (SELECT id FROM msg WHERE chat=? ORDER BY ts DESC, id DESC LIMIT ?)", arrayOf(chat, chat, keep))
        db.execSQL("DELETE FROM react WHERE chat=? AND target NOT IN (SELECT mid FROM msg WHERE chat=? AND mid IS NOT NULL)", arrayOf(chat, chat))
    }

    /** Chats with their latest message, newest first. */
    @Synchronized fun chats(): List<ChatRow> {
        val out = ArrayList<ChatRow>()
        val db = readableDatabase
        db.rawQuery("SELECT chat, MAX(ts), SUM(unread) FROM msg GROUP BY chat ORDER BY MAX(ts) DESC", null).use { cur ->
            while (cur.moveToNext()) {
                val n = cur.getString(0)
                val last = messages(n).lastOrNull()
                val name = db.rawQuery("SELECT name FROM chat WHERE number=?", arrayOf(n)).use { if (it.moveToFirst()) it.getString(0) else null } ?: n
                out.add(ChatRow(n, name, last, cur.getInt(2)))
            }
        }
        return out
    }

    /** One chat, oldest first. Group messages from other people carry who wrote them in [WaMsg.sender]. */
    @Synchronized fun messages(chat: String): List<WaMsg> {
        val out = ArrayList<WaMsg>()
        val group = chat.startsWith("g")
        val db = readableDatabase
        val reacts = HashMap<String, MutableList<WaReaction>>()
        db.rawQuery("SELECT target, sender_id, COALESCE(sender,''), emoji FROM react WHERE chat=? ORDER BY ts", arrayOf(chat)).use { cur ->
            while (cur.moveToNext()) reacts.getOrPut(cur.getString(0)) { ArrayList() }.add(WaReaction(cur.getString(1), cur.getString(2), cur.getString(3)))
        }
        db.rawQuery("SELECT text, ts, mine, sender_name, sender_id, COALESCE(mid,''), COALESCE(kind,'text'), COALESCE(media_path,''), COALESCE(media_ref,''), COALESCE(media_size,0), COALESCE(mime,''), " +
            "COALESCE(seconds,0), COALESCE(w,0), COALESCE(h,0), COALESCE(animated,0), COALESCE(quote_id,''), COALESCE(quote_text,''), COALESCE(quote_sender,''), COALESCE(edited,0), COALESCE(deleted,0) " +
            "FROM msg WHERE chat=? ORDER BY ts ASC, id ASC", arrayOf(chat)).use { cur ->
            while (cur.moveToNext()) {
                val mine = cur.getInt(2) == 1
                val who = (cur.getString(3) ?: "").ifBlank { cur.getString(4) ?: "" }
                val mid = cur.getString(5)
                out.add(WaMsg(cur.getString(0), cur.getLong(1) * 1000, mine, if (group && !mine) who else "", mid, cur.getString(6), cur.getString(7), cur.getString(8), cur.getLong(9),
                    cur.getString(10), cur.getInt(11), cur.getInt(12), cur.getInt(13), cur.getInt(14) == 1, cur.getString(15), cur.getString(16), cur.getString(17),
                    cur.getInt(18) == 1, cur.getInt(19) == 1, reacts[mid].orEmpty(), cur.getString(4) ?: ""))
            }
        }
        return out
    }

    /** Looks senders up again (after a re-sync): [nameFor] gets a name for a stored sender id, or null for none. */
    @Synchronized fun renameSenders(nameFor: (String) -> String?): Int {
        val db = writableDatabase
        val ids = db.rawQuery("SELECT DISTINCT sender_id FROM msg WHERE sender_id IS NOT NULL AND sender_id != '' AND chat LIKE 'g%' AND mine=0", null)
            .use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
        var changed = 0
        for (id in ids) {
            val n = nameFor(id)?.trim().orEmpty()
            if (n.isEmpty()) continue
            db.execSQL("UPDATE msg SET sender_name=? WHERE sender_id=? AND mine=0 AND IFNULL(sender_name,'') != ?", arrayOf(n, id, n))
            changed++
        }
        return changed
    }

    data class Anchor(val chat: String, val mid: String, val ts: Long, val mine: Boolean)

    /** For the most recently active chats, the oldest message we hold that has a WhatsApp id: where to ask for older ones from. */
    @Synchronized fun anchors(maxChats: Int = 20): List<Anchor> {
        val out = ArrayList<Anchor>()
        val db = readableDatabase
        val chats = db.rawQuery("SELECT chat FROM msg GROUP BY chat ORDER BY MAX(ts) DESC LIMIT ?", arrayOf(maxChats.toString())).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
        for (chat in chats) {
            db.rawQuery("SELECT mid, ts, mine FROM msg WHERE chat=? AND mid IS NOT NULL AND mid != '' ORDER BY ts ASC LIMIT 1", arrayOf(chat)).use { c ->
                if (c.moveToFirst()) out.add(Anchor(chat, c.getString(0), c.getLong(1), c.getInt(2) == 1))
            }
        }
        return out
    }

    /** What WhatsApp needs to hear when the chat is read here: the ids and senders of the incoming messages still unread, and the newest message. */
    class ReadInfo(val ids: String, val senders: String, val lastId: String, val lastSender: String, val lastFromMe: Boolean, val lastTs: Long)

    @Synchronized fun readInfo(chat: String): ReadInfo? {
        val db = readableDatabase
        val ids = StringBuilder(); val senders = StringBuilder()
        db.rawQuery("SELECT mid, COALESCE(sender_id,'') FROM msg WHERE chat=? AND mine=0 AND unread=1 AND mid<>'' ORDER BY ts", arrayOf(chat)).use { c ->
            while (c.moveToNext()) { ids.append(c.getString(0)).append('\n'); senders.append(c.getString(1)).append('\n') }
        }
        db.rawQuery("SELECT COALESCE(mid,''), COALESCE(sender_id,''), mine, ts FROM msg WHERE chat=? ORDER BY ts DESC, id DESC LIMIT 1", arrayOf(chat)).use { c ->
            if (!c.moveToFirst()) return null
            return ReadInfo(ids.toString(), senders.toString(), c.getString(0), c.getString(1), c.getInt(2) == 1, c.getLong(3))
        }
    }

    /** Messages in the chat up to [ts] (unix seconds) were read elsewhere: clear their unread marks. True when something changed. */
    @Synchronized fun markReadUpTo(chat: String, ts: Long): Boolean {
        val db = writableDatabase
        db.execSQL("UPDATE msg SET unread=0 WHERE chat=? AND unread=1 AND ts<=?", arrayOf(chat, ts))
        return db.rawQuery("SELECT changes()", null).use { it.moveToFirst() && it.getInt(0) > 0 }
    }

    @Synchronized fun markRead(chat: String) { writableDatabase.execSQL("UPDATE msg SET unread=0 WHERE chat=?", arrayOf(chat)) }

    @Synchronized fun unreadTotal(): Int = readableDatabase.rawQuery("SELECT COALESCE(SUM(unread),0) FROM msg", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Forget every chat that is no longer allowed: people by number, groups by name (lower case), or keep all. */
    @Synchronized fun keepOnly(numbers: Set<String>, groups: Set<String>, all: Boolean, groupIds: Set<String> = emptySet()) {
        if (all) return
        val db = writableDatabase
        val have = db.rawQuery("SELECT DISTINCT chat FROM msg", null).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
        for (n in have) {
            val keep = if (n.startsWith("g")) {
                val name = db.rawQuery("SELECT name FROM chat WHERE number=?", arrayOf(n)).use { if (it.moveToFirst()) it.getString(0) else null }
                n.substring(1).lowercase() in groupIds || (name != null && name.lowercase() in groups)
            } else n in numbers
            if (!keep) { db.execSQL("DELETE FROM msg WHERE chat=?", arrayOf(n)); db.execSQL("DELETE FROM chat WHERE number=?", arrayOf(n)) }
        }
    }

    /** With "allow everyone" the phone could end up holding every chat: keep only the [max] most recently active. */
    @Synchronized fun pruneChats(max: Int = MAX_CHATS) {
        val db = writableDatabase
        val n = db.rawQuery("SELECT COUNT(DISTINCT chat) FROM msg", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (n <= max) return
        db.execSQL("DELETE FROM msg WHERE chat NOT IN (SELECT chat FROM msg GROUP BY chat ORDER BY MAX(ts) DESC LIMIT ?)", arrayOf(max))
        db.execSQL("DELETE FROM chat WHERE number NOT IN (SELECT DISTINCT chat FROM msg)")
    }

    @Synchronized fun wipe() { writableDatabase.execSQL("DELETE FROM msg"); writableDatabase.execSQL("DELETE FROM chat"); writableDatabase.execSQL("DELETE FROM react") }

    // ---- richer messages (Family Chat)

    /** The file a downloaded media message now has (a video the child tapped). */
    @Synchronized fun setMediaPath(chat: String, mid: String, path: String) { writableDatabase.execSQL("UPDATE msg SET media_path=? WHERE chat=? AND mid=?", arrayOf(path, chat, mid)) }

    /** One message by WhatsApp's id (for the one a reply or reaction is about). */
    @Synchronized fun find(chat: String, mid: String): WaMsg? = if (mid.isEmpty()) null else messages(chat).firstOrNull { it.mid == mid }

    /** The message was edited: new text and the "edited" mark. True if we hold that message. */
    @Synchronized fun applyEdit(chat: String, mid: String, text: String): Boolean {
        val db = writableDatabase
        db.execSQL("UPDATE msg SET text=?, edited=1 WHERE chat=? AND mid=?", arrayOf(text, chat, mid))
        return db.rawQuery("SELECT changes()", null).use { it.moveToFirst() && it.getInt(0) > 0 }
    }

    /** The message was deleted for everyone: nothing of it is kept (text, media reference, reactions); returns the media file it had, so the caller can remove it. */
    @Synchronized fun applyRevoke(chat: String, mid: String): String? {
        val db = writableDatabase
        val path = db.rawQuery("SELECT media_path FROM msg WHERE chat=? AND mid=?", arrayOf(chat, mid)).use { if (it.moveToFirst()) it.getString(0) else null }
        db.execSQL("UPDATE msg SET text='', deleted=1, media_path='', media_ref='', quote_text='' WHERE chat=? AND mid=?", arrayOf(chat, mid))
        db.execSQL("DELETE FROM react WHERE chat=? AND target=?", arrayOf(chat, mid))
        return path
    }

    /** A reaction arrived (or was taken back: emoji empty). One reaction per person per message. */
    @Synchronized fun setReaction(chat: String, target: String, senderId: String, sender: String, emoji: String, ts: Long) {
        val db = writableDatabase
        if (emoji.isEmpty()) { db.execSQL("DELETE FROM react WHERE chat=? AND target=? AND sender_id=?", arrayOf(chat, target, senderId)); return }
        db.insertWithOnConflict("react", null, ContentValues().apply { put("chat", chat); put("target", target); put("sender_id", senderId); put("sender", sender); put("emoji", emoji); put("ts", ts) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** When the newest reaction in a chat arrived (milliseconds), or 0. */
    @Synchronized fun lastReactionMs(chat: String): Long =
        readableDatabase.rawQuery("SELECT COALESCE(MAX(ts),0) FROM react WHERE chat=?", arrayOf(chat)).use { if (it.moveToFirst()) it.getLong(0) * 1000 else 0L }

    /** Every media file the messages still point at (the cache cleaner removes the others). */
    @Synchronized fun mediaPathsInUse(): Set<String> =
        readableDatabase.rawQuery("SELECT media_path FROM msg WHERE media_path IS NOT NULL AND media_path != ''", null).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet() }

    companion object { const val KEEP = 20; const val MAX_CHATS = 60 }
}
