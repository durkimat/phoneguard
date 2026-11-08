package io.github.durkimat.phoneguard.whatsapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.durkimat.phoneguard.launcher.Msg

/** What the phone remembers of WhatsApp chats: the last few messages of each allowed chat, and nothing else. Private to the app. */
class WaStore(c: Context) : SQLiteOpenHelper(c, "whatsapp.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE msg (id INTEGER PRIMARY KEY AUTOINCREMENT, chat TEXT NOT NULL, ts INTEGER NOT NULL, mine INTEGER NOT NULL, text TEXT NOT NULL, unread INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE chat (number TEXT PRIMARY KEY, name TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    data class ChatRow(val number: String, val name: String, val last: Msg?, val unread: Int)

    /**
     * Keeps one message. [chatName] is the person's or group's name; [sender] writes it (shown in front of group
     * messages, so the reader knows who is talking).
     */
    @Synchronized fun add(chat: String, chatName: String, sender: String, text0: String, ts: Long, mine: Boolean, keep: Int = KEEP, history: Boolean = false) {
        val isGroup = chat.startsWith("g")
        val text = if (isGroup && !mine && sender.isNotBlank()) "$sender: $text0" else text0
        val name = chatName
        val db = writableDatabase
        // the same message can arrive twice (queued, then live): ignore an exact repeat
        db.rawQuery("SELECT 1 FROM msg WHERE chat=? AND ts=? AND mine=? AND text=?", arrayOf(chat, ts.toString(), if (mine) "1" else "0", text)).use { if (it.moveToFirst()) return }
        db.insert("msg", null, ContentValues().apply { put("chat", chat); put("ts", ts); put("mine", if (mine) 1 else 0); put("text", text); put("unread", if (mine || history) 0 else 1) })
        if (name.isNotBlank() && (!mine || isGroup)) db.insertWithOnConflict("chat", null, ContentValues().apply { put("number", chat); put("name", name) }, SQLiteDatabase.CONFLICT_REPLACE)
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

    /** One chat, oldest first. */
    @Synchronized fun messages(chat: String): List<Msg> {
        val out = ArrayList<Msg>()
        readableDatabase.rawQuery("SELECT text, ts, mine FROM msg WHERE chat=? ORDER BY ts ASC, id ASC", arrayOf(chat)).use { cur ->
            while (cur.moveToNext()) out.add(Msg(cur.getString(0), cur.getLong(1) * 1000, cur.getInt(2) == 1))
        }
        return out
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

    @Synchronized fun wipe() { writableDatabase.execSQL("DELETE FROM msg"); writableDatabase.execSQL("DELETE FROM chat") }

    companion object { const val KEEP = 20 }
}
