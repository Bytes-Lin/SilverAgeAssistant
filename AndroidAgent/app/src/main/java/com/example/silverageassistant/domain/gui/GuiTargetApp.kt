package com.example.silverageassistant.domain.gui

import kotlinx.coroutines.flow.StateFlow

data class GuiTargetApp(
    val packageName: String,
    val displayName: String,
)

data class GuiTargetSession(
    val todoId: String,
    val targetApp: GuiTargetApp,
    val isForegroundVerified: Boolean = false,
)

sealed interface GuiTargetLaunchResult {
    data class Launched(val targetApp: GuiTargetApp) : GuiTargetLaunchResult
    data class Unavailable(val message: String) : GuiTargetLaunchResult
}

interface GuiTargetAppLauncher {
    val activeSession: StateFlow<GuiTargetSession?>

    fun resolve(taskContent: String): GuiTargetApp?

    fun launch(todoId: String, targetApp: GuiTargetApp): GuiTargetLaunchResult

    /**
     * Starts a new full GUI run from a clean target-app task stack.
     *
     * Platform implementations may reset the visible Android task without force-stopping the
     * target process. The default keeps test doubles and non-Android launchers compatible.
     */
    suspend fun restart(
        todoId: String,
        targetApp: GuiTargetApp,
    ): GuiTargetLaunchResult = launch(todoId, targetApp)

    fun markForegroundVerified(todoId: String): Boolean = false

    fun returnToTask(todoId: String): Boolean

    fun clear(todoId: String)
}
