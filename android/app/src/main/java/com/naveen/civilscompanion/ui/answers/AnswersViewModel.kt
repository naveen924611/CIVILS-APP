package com.naveen.civilscompanion.ui.answers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.model.AnswerSubmission
import com.naveen.civilscompanion.data.records.Order
import com.naveen.civilscompanion.data.records.RecordJson
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.records.TimeUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException

@HiltViewModel
class AnswersViewModel @Inject constructor(
    private val store: RecordStore,
    private val api: AnswersApi,
) : ViewModel() {

    val answers: StateFlow<List<AnswerSubmission>> =
        store.observe(Tables.Answers, RecordQuery(order = Order.NewestFirst, limit = 300))
            .map { list -> list.sortedByDescending { TimeUtil.parse(it.createdAt) ?: 0L } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _opened = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** The id of a question that was just made; the screen opens it. */
    val opened: SharedFlow<String> = _opened.asSharedFlow()

    fun clearMessage() {
        _message.value = null
    }

    /** Asks the server for a new practice question (needs the internet). kind: short, mains or essay. */
    fun generate(kind: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val row = api.generate(JsonObject(mapOf("kind" to JsonPrimitive(kind))))
                val answer = RecordJson.decodeFromJsonElement(AnswerSubmission.serializer(), row)
                store.save(Tables.Answers, answer)
                _opened.tryEmit(answer.id)
            } catch (e: IOException) {
                _message.value = "You need the internet to get a new question. You can write your own question instead."
            } catch (e: HttpException) {
                _message.value = if (e.code() == 503) "The AI is busy right now. Please try again later." else "Could not get a question. Please try again later."
            } catch (e: SerializationException) {
                _message.value = "Could not read the new question. Please try again."
            } catch (e: IllegalArgumentException) {
                _message.value = "Could not read the new question. Please try again."
            } finally {
                _busy.value = false
            }
        }
    }

    /** The owner types a question of his own. */
    fun createOwn(question: String, wordLimit: Int) {
        val q = question.trim()
        if (q.length < 10) {
            _message.value = "Please write the whole question (at least a few words)."
            return
        }
        viewModelScope.launch {
            val id = TimeUtil.newId()
            store.save(
                Tables.Answers,
                AnswerSubmission(
                    id = id, question = q, wordLimit = wordLimit, kind = AnswerLogic.kindForLimit(wordLimit),
                    status = "draft", createdAt = TimeUtil.nowIso(),
                ),
            )
            _opened.tryEmit(id)
        }
    }

    fun remove(id: String) {
        viewModelScope.launch { store.delete(Tables.Answers, id) }
    }
}
