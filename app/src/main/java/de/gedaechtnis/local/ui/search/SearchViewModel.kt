package de.gedaechtnis.local.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.gedaechtnis.local.GedaechtnisApp
import de.gedaechtnis.local.data.SearchHit
import de.gedaechtnis.local.data.SearchResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SearchUiState(
    val query: String = "",
    val searching: Boolean = false,
    val result: SearchResult? = null,
    val answer: String? = null,
    val answerIsGenerated: Boolean = false,
    val answering: Boolean = false,
)

class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as GedaechtnisApp).container
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private val store = container.uiState
    private var job: Job? = null

    init {
        // Letzte Suche wiederherstellen: Treffer kommen schnell aus der lokalen DB, die gespeicherte
        // Antwort wird übernommen, statt das LLM erneut rechnen zu lassen.
        val lastQuery = store.lastQuery
        if (lastQuery.isNotBlank()) restore(lastQuery, store.lastAnswer, store.lastAnswerGenerated)
    }

    fun onQueryChange(q: String) = _state.update { it.copy(query = q) }

    fun search(q: String = _state.value.query) {
        if (q.isBlank()) return
        job?.cancel()
        store.lastQuery = q
        store.lastAnswer = null
        _state.update { it.copy(query = q, searching = true, answer = null, answering = false) }
        job = viewModelScope.launch {
            val result = container.repository.search(q)
            _state.update { it.copy(searching = false, result = result) }
            if (result.hits.isEmpty()) return@launch

            // Schnelle extraktive Antwort sofort anzeigen, LLM-Antwort (falls vorhanden) ersetzt sie.
            val extractive = extractiveAnswer(result.hits)
            _state.update { it.copy(answer = extractive, answerIsGenerated = false, answering = true) }
            val generated = container.ai.answer(q, result.hits.take(6).map { it.snippet })
            val final = generated?.takeIf { it.isNotBlank() }
            _state.update {
                if (final == null) it.copy(answering = false)
                else it.copy(answer = final, answerIsGenerated = true, answering = false)
            }
            store.lastAnswer = final ?: extractive
            store.lastAnswerGenerated = final != null
        }
    }

    private fun restore(q: String, answer: String?, generated: Boolean) {
        _state.update { it.copy(query = q, searching = true) }
        job = viewModelScope.launch {
            val result = container.repository.search(q)
            _state.update {
                it.copy(
                    searching = false,
                    result = result,
                    answer = answer ?: result.hits.takeIf { h -> h.isNotEmpty() }?.let { h -> extractiveAnswer(h) },
                    answerIsGenerated = answer != null && generated,
                )
            }
        }
    }

    fun clear() {
        job?.cancel()
        store.clearSearch()
        _state.value = SearchUiState()
    }

    private fun extractiveAnswer(hits: List<SearchHit>): String {
        val best = hits.first().snippet
        val fmt = SimpleDateFormat("EEEE, d. MMMM 'um' HH:mm", Locale.GERMANY)
        val flat = best.text.replace(Regex("\\s+"), " ")
        val excerpt = if (flat.length > 220) flat.take(220) + "…" else flat
        return "Bester Treffer: ${best.appLabel}, ${fmt.format(Date(best.timestamp))}.\n„$excerpt“" +
            if (hits.size > 1) "\n\n${hits.size - 1} weitere Treffer in der Timeline." else ""
    }
}
