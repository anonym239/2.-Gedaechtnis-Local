package de.gedaechtnis.local.data

import android.content.Context

/**
 * Merkt sich den zuletzt geöffneten Bildschirm und die letzte Suche, damit die App nach dem
 * Schließen dort weitermacht, wo der Nutzer war. Bewusst synchron (SharedPreferences), weil der
 * Wert schon beim ersten Frame für die Start-Route gebraucht wird.
 */
class UiStateStore(context: Context) {

    private val prefs = context.getSharedPreferences("ui_state", Context.MODE_PRIVATE)

    var lastRoute: String?
        get() = prefs.getString(KEY_ROUTE, null)
        set(value) = prefs.edit().putString(KEY_ROUTE, value).apply()

    var lastQuery: String
        get() = prefs.getString(KEY_QUERY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_QUERY, value).apply()

    var lastAnswer: String?
        get() = prefs.getString(KEY_ANSWER, null)
        set(value) = prefs.edit().putString(KEY_ANSWER, value).apply()

    var lastAnswerGenerated: Boolean
        get() = prefs.getBoolean(KEY_GENERATED, false)
        set(value) = prefs.edit().putBoolean(KEY_GENERATED, value).apply()

    fun clearSearch() = prefs.edit().remove(KEY_QUERY).remove(KEY_ANSWER).remove(KEY_GENERATED).apply()

    private companion object {
        const val KEY_ROUTE = "route"
        const val KEY_QUERY = "query"
        const val KEY_ANSWER = "answer"
        const val KEY_GENERATED = "answer_generated"
    }
}
