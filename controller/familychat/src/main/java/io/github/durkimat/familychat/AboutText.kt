package io.github.durkimat.familychat

import android.content.Context

/** The words of the risk panel and the licence text (screens 6 and setup step 1). */
object AboutText {
    const val RISK = "Family Chat uses an unofficial connection to WhatsApp. WhatsApp does not allow this in its terms, so it could limit or ban the WhatsApp account that is linked. That is your own account, so link it only if you accept that risk. Family Chat is not made by or connected to WhatsApp or Meta."
    const val SETUP_RISK = "This uses an unofficial connection. WhatsApp does not allow that in its terms, so it could limit or ban your WhatsApp account. Family Chat is not made by or connected to WhatsApp or Meta."
    const val WHATSMEOW_VERSION = "v0.0.0-20261107160454-79dc2a8c33df"
    const val WHATSMEOW_SOURCE = "https://github.com/tulir/whatsmeow/tree/79dc2a8c33df"
    const val NOTICE_LINK = "https://github.com/durkimat/phoneguard/blob/main/NOTICE.txt"
    const val LICENCES = "The connection uses whatsmeow ($WHATSMEOW_VERSION), licensed under the Mozilla Public License 2.0, unmodified. Source: $WHATSMEOW_SOURCE. Other parts are listed in the NOTICE file: $NOTICE_LINK"

    fun version(c: Context): String = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
}
