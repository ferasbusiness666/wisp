package com.wisp.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Contact list persisted as a JSON array in SharedPreferences. Deliberately not
 * a database — the list is a handful of entries and never queried.
 */
object ContactStore {

    private const val KEY = "contacts"

    fun all(context: Context): List<Contact> {
        val raw = Prefs.of(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val code = obj.optString("code").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Contact(code, obj.optString("name", code))
            }
        }.getOrDefault(emptyList())
    }

    fun codes(context: Context): List<String> = all(context).map { it.code }

    fun find(context: Context, code: String): Contact? = all(context).firstOrNull { it.code == code }

    /** Adds or renames a contact. Returns false if the code is already saved. */
    fun add(context: Context, contact: Contact): Boolean {
        val current = all(context).toMutableList()
        if (current.any { it.code == contact.code }) return false
        current += contact
        save(context, current)
        return true
    }

    fun remove(context: Context, code: String) {
        save(context, all(context).filterNot { it.code == code })
    }

    private fun save(context: Context, contacts: List<Contact>) {
        val array = JSONArray()
        contacts.forEach { contact ->
            array.put(JSONObject().put("code", contact.code).put("name", contact.name))
        }
        Prefs.of(context).edit().putString(KEY, array.toString()).apply()
    }
}
