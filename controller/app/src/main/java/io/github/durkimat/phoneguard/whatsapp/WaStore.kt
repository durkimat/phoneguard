package io.github.durkimat.phoneguard.whatsapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.durkimat.phoneguard.launcher.Msg

/** What the phone remembers of WhatsApp chats: the last few messages of each allowed chat, and nothing else. Private to the app. */
class WaStore(c: Context) : SQLiteOpenHelper(c, "whatsapp.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE msg (id INTEGER PRIMARY KEY AUTOINCREMENT, chat TEXT NOT NULL, ts INTEGER NOT NULL, mine INTEGER NOT NULL, text TEXT NOT NULL, unread INTEGER NOT NULL, sender_id TEXT, sender_name TEXT, mid TEXT)")
        db.execSQL("CREATE TABLE chat (number TEXT PRIMARY KEY, name TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) { // v2 keeps who wrote a message and WhatsApp's id for it, apart from the text
            db.execSQL("ALTER TABLE msg ADD COLUMN sender_id TEXT"); db.execSQL("ALTER TABLE msg ADD COLUMN sender_name TEXT"); db.execSQL("ALTER TABLE msg ADD COLUMN mid TEXT")
        }
    }

    data class ChatRow(val number: String, val name: String, val last: Msg?, val unread: Int)

    /**
     * Keeps one message. [chatName] is the person's or group's name; [sender]/[senderId] say who wrote it (shown in front of
     * group messages, and looked up again after a re-sync); [mid] is WhatsApp's id for the message.
     */
    @Synchronized fun add(
        chat: String, chatName: String, sender: String, senderId: String, mid: String, text: String, ts: Long, mine: Boolean,
        keep: Int = KEEP, history: Boolean = false,
    ) {
        val db = writableDatabase
        // the same message can arrive twice (queued, then live, or in history): ignore a repeat
        // by id when there is one, and also by time and text, because messages kept before ids were stored have none
        val dup = if (mid.isNotEmpty()) db.rawQuery("SELECT 1 FROM msg WHERE chat=? AND (mid=? OR (ts=? AND mine=? AND (text=? OR text LIKE ?)))", arrayOf(chat, mid, ts.toString(), if (mine) "1" else "0", text, "%: $text"))
        else db.rawQuery("SELECT 1 FROM msg WHERE chat=? AND ts=? AND mine=? AND (text=? OR text LIKE ?)", arrayOf(chat, ts.toString(), if (mine) "1" else "0", text, "%: $text"))
        dup.use { if (it.moveToFirst()) return }
        db.insert("msg", null, ContentValues().apply {
            put("chat", chat); put("ts", ts); put("mine", if (mine) 1 else 0); put("text", text); put("unread", if (mine || history) 0 else 1)
            put("sender_id", senderId); put("sender_name", if (mine) "" else sender); put("mid", mid)
        })
        if (chatName.isNotBlank() && (!mine || chat.startsWith("g"))) db.insertWithOnConflict("chat", null, ContentValues().apply { put("number", chat); put("name", chatName) }, SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL("DELETE FROM msg WHERE chat=? AND id NOT IN (SELECT id FROM msg WHERE chat=? ORDER BY ts DESC, id DESC LIMIT ?)", arrayOf(chat, chat, keep))
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

    /** One chat, oldest first. Group messages from other people carry who wrote them in [Msg.sender]. */
    @Synchronized fun messages(chat: String): List<Msg> {
        val out = ArrayList<Msg>()
        val group = chat.startsWith("g")
        readableDatabase.rawQuery("SELECT text, ts, mine, sender_name, sender_id FROM msg WHERE chat=? ORDER BY ts ASC, id ASC", arrayOf(chat)).use { cur ->
            while (cur.moveToNext()) {
                val mine = cur.getInt(2) == 1
                val who = (cur.getString(3) ?: "").ifBlank { cur.getString(4) ?: "" }
                out.add(Msg(cur.getString(0), cur.getLong(1) * 1000, mine, if (group && !mine) who else ""))
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

    /** Messages in the chat up to [ts] (unix seconds) were read elsewhere: clear their unread marks. True when something changed. */
    @Synchronized fun markReadUpTo(chat: String, ts: Long): Boolean {
        val db = writableDatabase
        db.execSQL("UPDATE msg SET unread=0 WHERE chat=? AND unread=1 AND ts<=?", arrayOf(chat, ts))
        return db.rawQuery("SELECT changes()", null).use { it.moveToFirst() && it.getInt(0) > 0 }
    }

    @Synchronized fun markRead(chat: String) { writableDatabase.execSQL("UPDATE msg SET unread=0 WHERE chat=?", arrayOf(chat)) }

    @Synchronized fun unreadTotal(): Int = readableDatabase.rawQuery("SELECT COALESCE(SUM(unread),0) FROM msg", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Forget every chat that is no longer allowed: people by number, groups by name (lower case), or keep all. */
    @Synchronized fun keepOnly(numbers: Set<String>, groups: Set<String>, all: Boolean) {
        if (all) return
        val db = writableDatabase
        val have = db.rawQuery("SELECT DISTINCT chat FROM msg", null).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
        for (n in have) {
            val keep = if (n.startsWith("g")) {
                val name = db.rawQuery("SELECT name FROM chat WHERE number=?", arrayOf(n)).use { if (it.moveToFirst()) it.getString(0) else null }
                name != null && name.lowercase() in groups
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

    @Synchronized fun wipe() { writableDatabase.execSQL("DELETE FROM msg"); writableDatabase.execSQL("DELETE FROM chat") }

    companion object { const val KEEP = 20; const val MAX_CHATS = 60 }
}
