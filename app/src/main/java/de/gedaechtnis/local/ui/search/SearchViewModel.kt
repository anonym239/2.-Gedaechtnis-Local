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
    private var job: Job? = null

    fun onQueryChange(q: String) = _state.update { it.copy(query = q) }

    fun search(q: String = _state.value.query) {
        if (q.isBlank()) return
        job?.cancel()
        _state.update { it.copy(query = q, searching = true, answer = null, answering = false) }
        job = viewModelScope.launch {
            val result = container.repository.search(q)
            _state.update { it.copy(searching = false, result = result) }
            if (result.hits.isEmpty()) return@launch

            // Schnelle extraktive Antwort sofort anzeigen, LLM-Antwort (falls vorhanden) ersetzt sie.
            _state.update { it.copy(answer = extractiveAnswer(result.hits), answerIsGenerated = false, answering = true) }
            val generated = container.ai.answer(q, result.hits.take(6).map { it.snippet })
            _state.update {
                if (generated.isNullOrBlank()) it.copy(answering = false)
                else it.copy(answer = generated, answerIsGenerated = true, answering = false)
            }
        }
    }

    fun clear() {
        job?.cancel()
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
