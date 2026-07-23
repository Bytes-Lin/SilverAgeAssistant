package com.example.silverageassistant.platform.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.PhoneNumberUtils
import com.example.silverageassistant.domain.agent.PhoneCallLauncher

class AndroidPhoneCallLauncher(
    context: Context,
) : PhoneCallLauncher {
    private val applicationContext = context.applicationContext

    override fun launch(phoneNumber: String, direct: Boolean) {
        require(PhoneNumberUtils.isGlobalPhoneNumber(phoneNumber.trim())) {
            "Invalid stored phone number"
        }
        val intent = Intent(
            if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL,
            Uri.fromParts("tel", phoneNumber.trim(), null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        require(intent.resolveActivity(applicationContext.packageManager) != null) {
            "No phone application available"
        }
        applicationContext.startActivity(intent)
    }
}
