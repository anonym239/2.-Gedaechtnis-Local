package de.gedaechtnis.local.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.gedaechtnis.local.GedaechtnisApp
import de.gedaechtnis.local.ai.LlmState
import de.gedaechtnis.local.data.SearchResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val searching: Boolean = false,
    val result: SearchResult? = null,
    /** Vom lokalen LLM formulierte Antwort; ersetzt die regelbasierte Antwort aus [SearchResult.composed]. */
    val generated: String? = null,
    val answering: Boolean = false,
    val llmInstalled: Boolean = false,
)

class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as GedaechtnisApp).container
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private val store = container.uiState
    private var job: Job? = null

    init {
        viewModelScope.launch {
            container.ai.llmState.collect { s -> _state.update { it.copy(llmInstalled = s != LlmState.NOT_INSTALLED) } }
        }
        // Letzte Suche wiederherstellen: Treffer und ausformulierte Antwort kommen schnell aus der lokalen DB,
        // eine gespeicherte LLM-Antwort wird übernommen, statt das Modell erneut rechnen zu lassen.
        val lastQuery = store.lastQuery
        if (lastQuery.isNotBlank()) {
            _state.update { it.copy(query = lastQuery, searching = true) }
            job = viewModelScope.launch {
                val result = container.repository.search(lastQuery)
                _state.update {
                    it.copy(searching = false, result = result, generated = store.lastAnswer.takeIf { store.lastAnswerGenerated })
                }
            }
        }
    }

    fun onQueryChange(q: String) = _state.update { it.copy(query = q) }

    fun search(q: String = _state.value.query) {
        if (q.isBlank()) return
        job?.cancel()
        store.lastQuery = q
        store.lastAnswer = null
        store.lastAnswerGenerated = false
        _state.update { it.copy(query = q, searching = true, result = null, generated = null, answering = false) }
        job = viewModelScope.launch {
            val result = container.repository.search(q)
            // Regelbasierte Antwort ist sofort da; das LLM (falls installiert) formuliert danach nach.
            _state.update { it.copy(searching = false, result = result, answering = it.llmInstalled && result.hits.isNotEmpty()) }
            if (result.hits.isEmpty() || !_state.value.llmInstalled) return@launch

            val generated = container.ai.answer(
                question = q,
                contexts = result.hits.map { it.snippet },
                summary = result.query.isSummary,
                periodLabel = result.query.timeLabel,
            )?.takeIf { it.isNotBlank() }
            _state.update { it.copy(generated = generated, answering = false) }
            if (generated != null) {
                store.lastAnswer = generated
                store.lastAnswerGenerated = true
            }
        }
    }

    fun clear() {
        job?.cancel()
        store.clearSearch()
        _state.update { SearchUiState(llmInstalled = it.llmInstalled) }
    }
}
