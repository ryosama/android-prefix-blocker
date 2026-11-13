package com.prefixblocker.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteDatabase
import com.prefixblocker.app.core.Decision
import com.prefixblocker.app.core.PhoneNumbers
import com.prefixblocker.app.core.Reason

data class Rule(val id: Long, val canonical: String, val allowed: Boolean)
data class ScreeningEvent(val id: Long, val number: String, val time: Long, val decision: Decision)

class AppStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "prefix_blocker.db", null, 1) {
    // L'interrupteur est indépendant des règles : une pause ne les efface jamais.
    private val preferences = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean("enabled", true)
        set(value) { preferences.edit().putBoolean("enabled", value).apply() }

    override fun onCreate(db: SQLiteDatabase) {
        // L'unicité porte sur le numéro normalisé ET la liste : une exception
        // peut autoriser un numéro également présent dans la liste de blocage.
        db.execSQL("CREATE TABLE rules (_id INTEGER PRIMARY KEY AUTOINCREMENT, canonical TEXT NOT NULL, allowed INTEGER NOT NULL DEFAULT 0, UNIQUE(canonical, allowed))")
        db.execSQL("CREATE TABLE events (_id INTEGER PRIMARY KEY AUTOINCREMENT, number TEXT NOT NULL, time INTEGER NOT NULL, reason TEXT NOT NULL, rule TEXT)")
        DEFAULT_RULES.forEach { raw ->
            db.insertOrThrow("rules", null, ContentValues().apply {
                put("canonical", PhoneNumbers.parseRule(raw))
                put("allowed", 0)
            })
        }
    }

    // Premier schéma uniquement. Une future version devra conserver les données
    // avec une migration explicite plutôt que recréer les tables.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun rules(allowed: Boolean): List<Rule> = readableDatabase.query(
        "rules", arrayOf("_id", "canonical"), "allowed=?", arrayOf(if (allowed) "1" else "0"),
        null, null, "canonical ASC",
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(Rule(cursor.getLong(0), cursor.getString(1), allowed)) }
    }

    fun saveRule(raw: String, allowed: Boolean, id: Long? = null) {
        val canonical = PhoneNumbers.parseRule(raw, allowPrefix = !allowed)
        val values = ContentValues().apply {
            put("canonical", canonical)
            put("allowed", if (allowed) 1 else 0)
        }
        if (id == null) writableDatabase.insertOrThrow("rules", null, values)
        else check(writableDatabase.update("rules", values, "_id=? AND allowed=?", arrayOf(id.toString(), if (allowed) "1" else "0")) == 1)
    }

    fun deleteRule(rule: Rule) {
        writableDatabase.delete("rules", "_id=?", arrayOf(rule.id.toString()))
    }

    fun record(number: String, time: Long, decision: Decision) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("events", null, ContentValues().apply {
                put("number", number)
                put("time", time)
                put("reason", decision.reason.name)
                put("rule", decision.rule)
            })
            db.execSQL("DELETE FROM events WHERE _id NOT IN (SELECT _id FROM events ORDER BY time DESC, _id DESC LIMIT 50)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun events(): List<ScreeningEvent> = readableDatabase.query(
        "events", arrayOf("_id", "number", "time", "reason", "rule"), null, null,
        null, null, "time DESC, _id DESC", "50",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(ScreeningEvent(
                cursor.getLong(0), cursor.getString(1), cursor.getLong(2),
                Decision(Reason.valueOf(cursor.getString(3)), cursor.getString(4)),
            ))
        }
    }

    fun clearEvents() { writableDatabase.delete("events", null, null) }

    companion object {
        // Amorçage à la création de la base seulement ; ne pas rétablir une
        // règle que l'utilisateur aurait supprimée lors d'un lancement suivant.
        val DEFAULT_RULES = listOf("0948*", "0270*", "0424*", "0162*", "0377*")
        // Une seule connexion logique partagée entre l'interface et le service.
        @Volatile private var instance: AppStore? = null
        fun get(context: Context): AppStore = instance ?: synchronized(this) {
            instance ?: AppStore(context).also { instance = it }
        }
    }
}
