package com.example.silverageassistant.platform.gui

import android.content.Context
import android.content.Intent
import com.example.silverageassistant.domain.gui.GuiTargetApp
import com.example.silverageassistant.domain.gui.GuiTargetAppLauncher
import com.example.silverageassistant.domain.gui.GuiTargetLaunchResult
import com.example.silverageassistant.domain.gui.GuiTargetSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay

class AndroidGuiTargetAppLauncher(
    context: Context,
) : GuiTargetAppLauncher {
    private val applicationContext = context.applicationContext
    private val mutableActiveSession = MutableStateFlow<GuiTargetSession?>(null)
    override val activeSession: StateFlow<GuiTargetSession?> =
        mutableActiveSession.asStateFlow()

    override fun resolve(taskContent: String): GuiTargetApp? =
        SUPPORTED_TARGETS.mapNotNull { target ->
            target.aliases
                .map { alias -> taskContent.indexOf(alias, ignoreCase = true) }
                .filter { index -> index >= 0 }
                .minOrNull()
                ?.let { firstMentionIndex -> target to firstMentionIndex }
        }.minByOrNull { (_, firstMentionIndex) -> firstMentionIndex }?.first?.app

    override fun launch(
        todoId: String,
        targetApp: GuiTargetApp,
    ): GuiTargetLaunchResult = launchWithFlags(
        todoId = todoId,
        targetApp = targetApp,
        flags = Intent.FLAG_ACTIVITY_NEW_TASK,
    )

    override suspend fun restart(
        todoId: String,
        targetApp: GuiTargetApp,
    ): GuiTargetLaunchResult {
        mutableActiveSession.value = null
        runCatching {
            applicationContext.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        delay(RESTART_HOME_SETTLE_MILLIS)
        return launchWithFlags(
            todoId = todoId,
            targetApp = targetApp,
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
    }

    private fun launchWithFlags(
        todoId: String,
        targetApp: GuiTargetApp,
        flags: Int,
    ): GuiTargetLaunchResult {
        val launchIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(targetApp.packageName)
            ?.addFlags(flags)
            ?: return GuiTargetLaunchResult.Unavailable(
                "手机上没有找到${targetApp.displayName}，请先确认已经安装。",
            )
        return runCatching {
            applicationContext.startActivity(launchIntent)
            mutableActiveSession.value = GuiTargetSession(
                todoId = todoId,
                targetApp = targetApp,
                isForegroundVerified = false,
            )
            GuiTargetLaunchResult.Launched(targetApp)
        }.getOrElse {
            GuiTargetLaunchResult.Unavailable(
                "暂时无法打开${targetApp.displayName}，请稍后再试。",
            )
        }
    }

    override fun markForegroundVerified(todoId: String): Boolean {
        val session = mutableActiveSession.value?.takeIf { it.todoId == todoId }
            ?: return false
        mutableActiveSession.value = session.copy(isForegroundVerified = true)
        return true
    }

    override fun returnToTask(todoId: String): Boolean {
        val session = mutableActiveSession.value?.takeIf { it.todoId == todoId }
            ?: return false
        return launch(todoId, session.targetApp) is GuiTargetLaunchResult.Launched
    }

    override fun clear(todoId: String) {
        if (mutableActiveSession.value?.todoId == todoId) {
            mutableActiveSession.value = null
        }
    }

    private data class SupportedTarget(
        val app: GuiTargetApp,
        val aliases: Set<String>,
    )

    private companion object {
        const val RESTART_HOME_SETTLE_MILLIS = 400L

        val SUPPORTED_TARGETS = listOf(
            SupportedTarget(
                app = GuiTargetApp(
                    packageName = "com.sankuai.meituan",
                    displayName = "美团",
                ),
                aliases = setOf("美团", "Meituan"),
            ),
            SupportedTarget(
                app = GuiTargetApp(
                    packageName = "com.tencent.mm",
                    displayName = "微信",
                ),
                aliases = setOf("微信", "WeChat"),
            ),
            SupportedTarget(
                app = GuiTargetApp(
                    packageName = "com.taobao.taobao",
                    displayName = "淘宝",
                ),
                aliases = setOf("淘宝", "Taobao"),
            ),
        )
    }
}
