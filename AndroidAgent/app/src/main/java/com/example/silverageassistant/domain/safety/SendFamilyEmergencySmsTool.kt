package com.example.silverageassistant.domain.safety

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.example.silverageassistant.data.contacts.FamilyContactStore

data class EmergencySmsResult(
    val sentCount: Int,
    val failureReason: String? = null,
) {
    val succeeded: Boolean get() = sentCount > 0 && failureReason == null
}

interface EmergencySmsSender {
    suspend fun send(observedAt: String, detail: String): EmergencySmsResult
}

class SendFamilyEmergencySmsTool(
    private val context: Context,
    private val contactStore: FamilyContactStore,
) : EmergencySmsSender {
    @Suppress("DEPRECATION")
    override suspend fun send(observedAt: String, detail: String): EmergencySmsResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return EmergencySmsResult(0, "老人手机尚未授予短信权限")
        }
        val recipients = contactStore.load()?.contacts.orEmpty()
            .filter { it.emergencyContact }
            .map { it.mobileNumber.trim() }
            .filter { it.matches(Regex("^1\\d{10}$")) }
            .distinct()
        if (recipients.isEmpty()) return EmergencySmsResult(0, "没有可用的紧急联系人")
        val message = "【银龄助手紧急提醒】$observedAt 连续检测到老人疑似异常：${detail.take(120)}。请尽快联系老人并核实现场情况；本信息不是医疗诊断。"
        return runCatching {
            val manager = SmsManager.getDefault()
            recipients.forEach { number ->
                val parts = manager.divideMessage(message)
                manager.sendMultipartTextMessage(number, null, parts, null, null)
            }
            EmergencySmsResult(recipients.size)
        }.getOrElse { EmergencySmsResult(0, "短信发送失败") }
    }
}
