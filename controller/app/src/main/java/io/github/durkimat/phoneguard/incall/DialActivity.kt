package io.github.durkimat.phoneguard.incall

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.durkimat.phoneguard.launcher.LauncherActivity

/** Android sends "dial this number" here (a tel: link, a contact's call button). It opens our dial screen with the number typed. */
class DialActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val number = intent?.data?.schemeSpecificPart.orEmpty()
        startActivity(Intent(this, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(LauncherActivity.EXTRA_DIAL, number))
        finish()
    }
}
