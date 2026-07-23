package com.example.silverageassistant.domain.safety

import com.example.silverageassistant.data.middleserver.SafetyEventType
import com.example.silverageassistant.data.safety.SafetyDetectionWindow
import com.example.silverageassistant.data.safety.SafetyDetectionStateRepository
import com.example.silverageassistant.domain.agent.FamilySituationReporter
import java.time.Clock
import java.time.Instant

/**
 * 与聊天完全分离的单次状态检测编排器。
 *
 * 调度周期由前台 Service 管理，本类只执行一次“取图 -> MLLM -> 持久化 -> 告警”。
 * 六小时窗口内连续两次异常只创建一条家属事件，连续三次只发送一条短信；正常结果会清零
 * 连续次数，但不会撤销本窗口已经发出的通知。
 */
class SafetyMonitoringAgent(
    private val imageSource: SafetyImageSource,
    private val analyzer: SafetyVisionAnalyzer,
    private val stateStore: SafetyDetectionStateRepository,
    private val familyReporter: FamilySituationReporter,
    private val smsTool: EmergencySmsSender,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun runOnce(): TimedSafetyAnalysis {
        val now = Instant.now(clock)
        val observedAt = now.toString()
        val current = stateStore.load(now)
        val previous = current?.history?.lastOrNull()
        val image = imageSource.acquireLatestImage()
        val result = analyzer.analyze(
            image = image,
            previousState = previous,
            observedAt = observedAt,
        )
        val timed = TimedSafetyAnalysis(observedAt, result)
        val consecutive = if (result.state == ElderObservedState.ABNORMAL) {
            (current?.consecutiveAbnormalCount ?: 0) + 1
        } else {
            0
        }
        var smsSent = current?.smsSent ?: false
        var notificationSent = current?.notificationSent ?: false
        val detail = result.detail.orEmpty()
        if (consecutive >= NOTIFICATION_THRESHOLD && !notificationSent) {
            runCatching {
                familyReporter.report(
                    eventType = inferEventType(detail),
                    eventSummary = detail,
                )
            }.onSuccess { event ->
                notificationSent = true
                // 先保存“已通知”，再上传可选证据图像；图像慢或失败都不能让下一周期
                // 再创建一条相同的紧急事件。
                stateStore.save(
                    SafetyDetectionWindow(
                        startedAt = current?.startedAt ?: observedAt,
                        history = (current?.history.orEmpty() + timed)
                            .takeLast(MAX_HISTORY_ITEMS),
                        consecutiveAbnormalCount = consecutive,
                        notificationSent = true,
                        smsSent = smsSent,
                    ),
                )
                runCatching {
                    familyReporter.attachEvidence(event.eventId, image)
                }
            }
        }
        if (consecutive >= SMS_THRESHOLD && !smsSent) {
            smsSent = smsTool.send(observedAt, detail).succeeded
        }
        stateStore.save(
            SafetyDetectionWindow(
                startedAt = current?.startedAt ?: observedAt,
                history = (current?.history.orEmpty() + timed).takeLast(MAX_HISTORY_ITEMS),
                consecutiveAbnormalCount = consecutive,
                notificationSent = notificationSent,
                smsSent = smsSent,
            ),
        )
        return timed
    }

    suspend fun clearState() = stateStore.clear()

    private fun inferEventType(detail: String): SafetyEventType = when {
        detail.contains("昏迷") || detail.contains("失去意识") || detail.contains("晕倒") ->
            SafetyEventType.UNCONSCIOUSNESS_SUSPECTED
        detail.contains("跌倒") || detail.contains("倒地") || detail.contains("摔倒") ->
            SafetyEventType.FALL_SUSPECTED
        else -> SafetyEventType.OTHER_ABNORMALITY
    }

    companion object {
        const val NOTIFICATION_THRESHOLD = 2
        const val SMS_THRESHOLD = 3
        private const val MAX_HISTORY_ITEMS = 360
    }
}
