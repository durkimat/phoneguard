package io.github.durkimat.phoneguard.keys

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.camera.CameraActivity
import io.github.durkimat.phoneguard.core.F2Gesture
import io.github.durkimat.phoneguard.incall.CallState
import io.github.durkimat.phoneguard.launcher.LauncherData

/** What the side-button gestures do and when they are allowed; shared by the accessibility service and the launcher (which sees the key when the service is off). */
object F2Actions {
    /**
     * Read when an action would fire. The camera needs: no call, not locked, the mode offers it, the front not covered (a knock in a pocket). The torch works
     * locked or not (screen on), but not while our camera has the flash. Speak needs voice on in the mode (see [F2Speak]).
     */
    fun policy(c: Context): F2Gesture.Policy {
        val locked = try { c.getSystemService(KeyguardManager::class.java).isKeyguardLocked } catch (_: Exception) { false }
        return F2Gesture.Policy(
            camera = !locked && CallState.info == null && LauncherData.cameraAllowed(c) && !Proximity.covered,
            torch = !CameraActivity.isFront(),
            speak = F2Speak.allowed(c),
        )
    }

    fun run(c: Context, out: F2Gesture.Out) {
        when (out) {
            F2Gesture.Out.Camera -> try { io.github.durkimat.phoneguard.launcher.LauncherActivity.openingOwnScreen(); c.startActivity(Intent(c, CameraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { Log.log(c, "side button: ${e.javaClass.simpleName}") }
            F2Gesture.Out.Torch -> Torch.toggle(c)
            F2Gesture.Out.SpeakStart -> F2Speak.start(c)
            F2Gesture.Out.SpeakStop -> F2Speak.stop()
        }
    }
}
