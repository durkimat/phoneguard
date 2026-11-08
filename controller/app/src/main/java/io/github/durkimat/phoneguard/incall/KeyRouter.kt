package io.github.durkimat.phoneguard.incall

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.durkimat.phoneguard.launcher.LauncherActivity

/** Hands the left key to the call screen while a call is on, and otherwise on to the launcher (see HardKeys). */
object LeftKey {
    @Volatile var listener: (() -> Unit)? = null
    /** Set while the camera screen has the focus: the left key is its Shoot key. */
    @Volatile var camera: (() -> Unit)? = null
}

/**
 * DumberOS turns the left key under the screen into a "search" request (VOICE_ASSIST or WEB_SEARCH) once
 * we remap it. This invisible activity catches that request: during a call it is the call screen's left key.
 */
class KeyRouter : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val l = LeftKey.listener
        val cam = LeftKey.camera
        if (CallState.info != null && l != null) l()
        else if (cam != null) cam()
        else startActivity(Intent(this, LauncherActivity::class.java).setAction(intent?.action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
