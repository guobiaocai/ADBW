package com.adbw.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 会话 UI 状态（SessionService 更新，MainActivity 收集） */
sealed class SessionUiState {
    object Idle : SessionUiState()
    data class Starting(val message: String = "启动中…") : SessionUiState()
    data class Running(
        val relayHost: String,
        val pairing: String,
        val online: Boolean,
    ) : SessionUiState()

    data class Failed(val message: String) : SessionUiState()
    data class Stopped(val message: String = "已停止") : SessionUiState()
}

object SessionState {
    val state: MutableStateFlow<SessionUiState> = MutableStateFlow(SessionUiState.Idle)
    fun update(s: SessionUiState) {
        state.value = s
    }
}

/** Shizuku 授权结果等一次性 UI 刷新信号 */
object UiTick {
    private val _tick = MutableStateFlow(0)
    val tick: StateFlow<Int> = _tick
    fun bump() {
        _tick.value++
    }
}
