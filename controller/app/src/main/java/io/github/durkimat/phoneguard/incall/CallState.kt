package io.github.durkimat.phoneguard.incall

import android.os.Handler
import android.os.Looper
import io.github.durkimat.phoneguard.launcher.CallInfo
import java.util.concurrent.CopyOnWriteArrayList

/** The call on screen, written by [CallService] and read by [CallActivity]. */
object CallState {
    @Volatile var info: CallInfo? = null
        private set
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun set(i: CallInfo?) { info = i; main.post { listeners.forEach { it() } } }
    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
}
