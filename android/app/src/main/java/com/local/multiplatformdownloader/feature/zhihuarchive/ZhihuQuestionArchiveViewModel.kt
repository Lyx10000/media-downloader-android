package com.local.multiplatformdownloader.feature.zhihuarchive

import com.local.multiplatformdownloader.core.database.DownloadTaskRepository
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.TaskRecord
import com.local.multiplatformdownloader.core.model.TaskStatus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.webkit.CookieManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi

private const val QUESTION_PAGE_SIZE = 20

data class ZhihuQuestionArchiveUiState(
    val parentTaskId: String? = null,
    val parentTask: TaskRecord? = null,
    val archive: ZhihuQuestionArchive? = null,
    val answers: List<ZhihuQuestionAnswer> = emptyList(),
    val childTasks: Map<String, TaskRecord> = emptyMap(),
    val pageNumber: Int = 1,
    val pageSize: Int = QUESTION_PAGE_SIZE,
    val listIndex: Int = 0,
    val listOffset: Int = 0,
    val isLoading: Boolean = false,
    val actionInProgress: Boolean = false,
    val error: String = "",
    val message: String = "",
) {
    val totalPages: Int
        get() = ((answers.size + pageSize - 1) / pageSize).coerceAtLeast(1)

    val visibleAnswers: List<ZhihuQuestionAnswer>
        get() = answers.drop((pageNumber - 1) * pageSize).take(pageSize)

    val hasPreviousPage: Boolean
        get() = pageNumber > 1

    val hasNextPage: Boolean
        get() = pageNumber < totalPages
}

private data class ArchiveObservation(
    val parentTask: TaskRecord? = null,
    val archive: ZhihuQuestionArchive? = null,
    val answers: List<ZhihuQuestionAnswer> = emptyList(),
    val childTasks: Map<String, TaskRecord> = emptyMap(),
    val error: String = "",
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class ZhihuQuestionArchiveViewModel @Inject constructor(
    private val taskRepository: DownloadTaskRepository,
    private val questionRepository: ZhihuQuestionRepository,
    private val coordinator: ZhihuQuestionArchiveCoordinator,
) : ViewModel() {
    private val openedTaskId = MutableStateFlow<String?>(null)
    private val _state = MutableStateFlow(ZhihuQuestionArchiveUiState())
    val state: StateFlow<ZhihuQuestionArchiveUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            openedTaskId
                .flatMapLatest(::observeArchive)
                .collect { observation ->
                    _state.update { current ->
                        val maxPage = ((observation.answers.size + current.pageSize - 1) /
                            current.pageSize).coerceAtLeast(1)
                        current.copy(
                            parentTask = observation.parentTask,
                            archive = observation.archive,
                            answers = observation.answers,
                            childTasks = observation.childTasks,
                            pageNumber = current.pageNumber.coerceIn(1, maxPage),
                            isLoading = false,
                            error = observation.error,
                        )
                    }
                }
        }
    }

    fun open(parentTaskId: String) {
        val normalized = parentTaskId.trim().takeIf(String::isNotBlank) ?: return
        if (openedTaskId.value == normalized && _state.value.parentTask != null) return
        _state.value = ZhihuQuestionArchiveUiState(
            parentTaskId = normalized,
            isLoading = true,
        )
        openedTaskId.value = normalized
    }

    fun close() {
        openedTaskId.value = null
        _state.value = ZhihuQuestionArchiveUiState()
    }

    fun previousPage() {
        _state.update { current ->
            if (current.hasPreviousPage) current.copy(
                pageNumber = current.pageNumber - 1,
                listIndex = 0,
                listOffset = 0,
            )
            else current
        }
    }

    fun nextPage() {
        _state.update { current ->
            if (current.hasNextPage) current.copy(
                pageNumber = current.pageNumber + 1,
                listIndex = 0,
                listOffset = 0,
            )
            else current
        }
    }

    fun rememberListPosition(index: Int, offset: Int) {
        if (_state.value.listIndex == index && _state.value.listOffset == offset) return
        _state.update { it.copy(listIndex = index.coerceAtLeast(0), listOffset = offset.coerceAtLeast(0)) }
    }

    fun resume() {
        val parentTaskId = state.value.parentTaskId ?: return
        runAction { coordinator.resume(parentTaskId) }
    }

    fun continueNextPage() {
        val parentTaskId = state.value.parentTaskId ?: return
        runAction { coordinator.continueNextPage(parentTaskId) }
    }

    fun resumeAnswer(answer: ZhihuQuestionAnswer) {
        val parentTaskId = state.value.parentTaskId ?: return
        val childTask = state.value.childTasks[answer.answerId]
        val answerCanRetry = answer.status in setOf(
                ZhihuQuestionAnswerStatus.FAILED,
                ZhihuQuestionAnswerStatus.PAUSED,
            )
        val childCanRetry = childTask?.status in setOf(TaskStatus.FAILED, TaskStatus.CANCELLED)
        if (!answerCanRetry && !childCanRetry) return
        runAction {
            val cookieHeader = CookieManager.getInstance()
                .getCookie(SourcePlatform.ZHIHU.homeUrl).orEmpty()
            withContext(Dispatchers.IO) {
                coordinator.retryAnswer(parentTaskId, answer.answerId, cookieHeader)
            }
        }
    }

    fun cancel() {
        val parentTaskId = state.value.parentTaskId ?: return
        runAction {
            coordinator.cancel(parentTaskId)
            "已取消知乎问题归档"
        }
    }

    fun deleteAnswer(answer: ZhihuQuestionAnswer) {
        val parentTaskId = state.value.parentTaskId ?: return
        runAction {
            withContext(Dispatchers.IO) {
                coordinator.deleteAnswer(parentTaskId, answer.answerId)
            }
        }
    }

    private fun runAction(action: suspend () -> String) {
        if (_state.value.actionInProgress) return
        viewModelScope.launch {
            _state.update { it.copy(actionInProgress = true, message = "") }
            try {
                val message = action()
                _state.update { it.copy(actionInProgress = false, message = message) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        actionInProgress = false,
                        message = error.message.orEmpty().ifBlank { "操作失败，请稍后重试" },
                    )
                }
            }
        }
    }

    private fun observeArchive(parentTaskId: String?): Flow<ArchiveObservation> {
        if (parentTaskId.isNullOrBlank()) return flowOf(ArchiveObservation())
        return taskRepository.observeAll().flatMapLatest { records ->
            val parent = records.firstOrNull { it.id == parentTaskId }
            val questionId = parent?.questionArchiveId.orEmpty()
            if (parent == null) {
                flowOf(ArchiveObservation(error = "任务不存在或已被删除"))
            } else if (questionId.isBlank()) {
                flowOf(
                    ArchiveObservation(
                        parentTask = parent,
                        error = "该任务不是知乎问题归档任务",
                    ),
                )
            } else {
                combine(
                    questionRepository.observeQuestions().map { archives ->
                        archives.firstOrNull { it.questionId == questionId }
                    },
                    questionRepository.observeAnswers(questionId),
                ) { archive, answers ->
                    ArchiveObservation(
                        parentTask = parent,
                        archive = archive,
                        answers = answers,
                        childTasks = answers.mapNotNull { answer ->
                            records.firstOrNull { it.id == answer.taskId }
                                ?.let { answer.answerId to it }
                        }.toMap(),
                        error = if (archive == null) "归档记录不存在" else "",
                    )
                }
            }
        }
    }
}
