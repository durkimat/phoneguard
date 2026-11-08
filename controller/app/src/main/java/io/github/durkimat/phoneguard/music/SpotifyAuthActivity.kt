package io.github.durkimat.phoneguard.music

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.launcher.LauncherActivity

/** Spotify's sign-in page sends the browser back here with a code; this finishes the sign-in and returns to the Nokia screens. */
class SpotifyAuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val code = uri?.getQueryParameter("code")
        val app = applicationContext
        if (code == null) { Log.log(app, "spotify sign-in: ${uri?.getQueryParameter("error") ?: "no code"}"); back(); return }
        Thread {
            val ok = Spotify.finishSignIn(app, code)
            Log.log(app, "spotify sign-in ${if (ok) "done" else "failed"}")
            runOnUiThread { back() }
        }.start()
    }

    private fun back() {
        startActivity(Intent(this, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("music", true))
        finish()
    }
}
