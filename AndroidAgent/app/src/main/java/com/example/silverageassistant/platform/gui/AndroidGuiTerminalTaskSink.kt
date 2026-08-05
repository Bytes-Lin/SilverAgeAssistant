package com.example.silverageassistant.platform.gui

import android.content.Context
import android.content.Intent
import com.example.silverageassistant.domain.gui.GuiDebugTrace
import com.example.silverageassistant.domain.gui.GuiTerminalTaskSink
import com.example.silverageassistant.domain.gui.GuiTodo
import com.example.silverageassistant.service.GuiTaskNavigationBridge
import kotlinx.coroutines.delay

/**
 * Removes the failed target app from the foreground and brings the existing SilverAgeAssistant
 * task back. Android does not allow a normal app to force-stop another package; this deliberately
 * performs a visible task exit without requesting privileged permissions.
 */
class AndroidGuiTerminalTaskSink(
    context: Context,
) : GuiTerminalTaskSink {
    private val applicationContext = context.applicationContext

    override suspend fun onFinalFailure(todo: GuiTodo) {
        GuiDebugTrace.record(
            source = "terminal_navigation",
            stage = "leaving_target_app",
            message = "正在退出目标 App 前台并返回银龄助手",
            details = "todo=${todo.id}",
        )
        applicationContext.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        delay(HOME_SETTLE_MILLIS)
        GuiTaskNavigationBridge.requestConversation()
        val assistantIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?: error("无法找到银龄助手启动页面")
        assistantIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        applicationContext.startActivity(assistantIntent)
    }

    private companion object {
        const val HOME_SETTLE_MILLIS = 300L
    }
}
