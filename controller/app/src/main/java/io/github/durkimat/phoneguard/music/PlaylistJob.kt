package io.github.durkimat.phoneguard.music

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.PowerManager
import io.github.durkimat.phoneguard.Log
import io.github.durkimat.phoneguard.core.PlaylistDrive
import io.github.durkimat.phoneguard.incall.CallState
import io.github.durkimat.phoneguard.keys.F2Service
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads a Spotify playlist for offline listening (or removes the download) by itself: opens the playlist in the Spotify app and has the screen helper
 * (F2Service) press its Download button, as a person would. [PlaylistDrive] decides what to press; this does the waiting, the cover panel and the tidying up.
 * Needs Wi-Fi to download (never mobile data), an unlocked phone and the screen helper. A job never queues or retries: [whyNot] says at once what is missing.
 */
object PlaylistJob {
    private val running = AtomicBoolean(false)
    fun busy() = running.get()

    class Result(val ok: Boolean, val detail: String, val firstSeen: PlaylistDrive.State?)

    /** The notice to show when the job cannot start now (null when it can). Removing works without Wi-Fi. */
    fun whyNot(c: Context, removing: Boolean): String? {
        if (running.get()) return "Another job is running"
        if (io.github.durkimat.phoneguard.StoreInstall.running.get()) return "The phone is busy with the app store"
        if (!MusicController.spotifyInstalled(c)) return "Spotify is not installed"
        if (!F2Service.running()) return "Turn on Phone Guard keys in Settings, then try again"
        if (c.getSystemService(KeyguardManager::class.java).isDeviceLocked) return "Unlock the phone first"
        if (CallState.info != null) return "Not during a call"
        if (!removing && !onWifi(c)) return "Connect to Wi-Fi first"
        return null
    }

    private fun onWifi(c: Context) = try {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (_: Exception) { false }

    private fun open(c: Context, uri: String, attempt: Int) {
        val id = PlaylistOffline.idOf(uri) ?: return
        val target = if (attempt == 0) uri else "https://open.spotify.com/playlist/$id"
        try {
            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).setPackage(PlaylistDrive.SPOTIFY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (e: Exception) { Log.log(c, "playlist job: could not open the playlist (${e.javaClass.simpleName})") }
    }

    private fun goHome(c: Context) {
        try { c.startActivity(Intent(c, io.github.durkimat.phoneguard.launcher.LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) } catch (_: Exception) { }
    }

    /** Blocks (call on a worker thread). The caller checks [whyNot] first. */
    fun run(c: Context, uri: String, removing: Boolean): Result {
        if (!running.compareAndSet(false, true)) return Result(false, "Another job is running", null)
        val wake = c.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "phoneguard:playlistjob")
        val drive = PlaylistDrive(removing)
        var result: Result? = null
        try {
            wake.acquire(12 * 60_000L)
            val message = if (removing) "Removing playlist..." else "Getting playlist..."
            F2Service.cover(true, message)
            Thread.sleep(1_500)
            open(c, uri, 0)
            var looks = 0
            while (result == null && looks++ < PlaylistDrive.MAX_DOWNLOAD_STEPS + 5) {
                Thread.sleep(PlaylistDrive.LOOK_EVERY_MS)
                if (CallState.info != null) { result = Result(false, "Stopped: a call came in", drive.firstSeen); break }
                if (!removing && !onWifi(c)) { result = Result(false, "Wi-Fi was lost", drive.firstSeen); break }
                F2Service.cover(true, message)
                val screen = F2Service.readScreen() ?: continue
                when (val s = drive.step(screen.first, screen.second)) {
                    is PlaylistDrive.Step.Tap -> { val ok = F2Service.clickLabel(s.label); Log.log(c, "playlist job: ${s.label} ${if (ok) "pressed" else "not pressable"}") }
                    is PlaylistDrive.Step.Open -> open(c, uri, s.attempt)
                    is PlaylistDrive.Step.Done -> result = Result(true, s.detail, drive.firstSeen)
                    is PlaylistDrive.Step.Fail -> result = Result(false, s.detail, drive.firstSeen)
                    PlaylistDrive.Step.Wait -> {}
                }
            }
            if (result == null) result = Result(false, "it did not finish in time", drive.firstSeen)
        } catch (e: Exception) {
            result = Result(false, "something went wrong (${e.javaClass.simpleName})", drive.firstSeen)
        } finally {
            try { if (wake.isHeld) wake.release() } catch (_: Exception) { }
            try { F2Service.cover(false) } catch (_: Exception) { }
            goHome(c); running.set(false)
        }
        Log.log(c, "playlist job (${if (removing) "remove" else "download"}): ${result!!.detail}")
        return result
    }
}
