package org.dergigi.feather

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Observed delivery sources only. All callers run on the repository's IO dispatcher. */
internal class EventRelays(context: Context) : SQLiteOpenHelper(context, "event-relays.db", null, 1) {
    private var additions = 0

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sources (event TEXT NOT NULL, relay TEXT NOT NULL, seen INTEGER NOT NULL, PRIMARY KEY(event, relay))")
        db.execSQL("CREATE INDEX sources_seen ON sources(seen)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun read(id: String): List<String> = try {
        readableDatabase.rawQuery("SELECT relay FROM sources WHERE event = ? ORDER BY seen DESC LIMIT 12", arrayOf(id)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    } catch (e: Exception) {
        android.util.Log.w("EventRelays", "Could not read relay provenance", e)
        emptyList()
    }

    @Synchronized
    fun record(id: String, raw: String): Boolean {
        val relay = Protocol.relay(raw) ?: return false
        return try {
            val existing = read(id)
            if (relay in existing || existing.size >= 12) return false
            val db = writableDatabase
            val inserted = db.insertWithOnConflict("sources", null, ContentValues().apply {
                put("event", id); put("relay", relay); put("seen", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
            // Bound disk use without scanning on every arrival. Keep all sources for each retained event.
            if (inserted && ++additions % 256 == 0) db.execSQL(
                "DELETE FROM sources WHERE event IN (SELECT event FROM sources GROUP BY event ORDER BY MAX(seen) DESC, event LIMIT -1 OFFSET 16384)"
            )
            inserted
        } catch (e: Exception) {
            // Optional provenance must never prevent accepting a verified event.
            android.util.Log.w("EventRelays", "Could not save relay provenance", e)
            false
        }
    }
}
