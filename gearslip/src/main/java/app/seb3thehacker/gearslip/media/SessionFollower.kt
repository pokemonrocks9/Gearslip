package app.seb3thehacker.gearslip.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.notify.GearslipNotificationListener

/**
 * Follows the music: when an app on the phone starts playing mid-drive, the car's player switches
 * to it, as the phone's own media controls do. Apps with no car library (YouTube, a browser) come
 * in session-only. Whatever was already playing when the car connected is autostart's job; only
 * later starts count here.
 */
class SessionFollower(
    private val context: Context,
    /** Package the car's player is showing now, if any. */
    private val current: () -> String?,
    private val onStarted: (MediaApp) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, GearslipNotificationListener::class.java)
    private val watched = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private val playing = mutableSetOf<String>()
    private var running = false

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { watch(it.orEmpty()) }

    fun start() {
        if (running) return
        val sessions = try {
            manager.getActiveSessions(listener)
        } catch (e: SecurityException) {
            GearslipLog.w("media: can't follow other players without notification access")
            return
        }
        running = true
        manager.addOnActiveSessionsChangedListener(sessionsChanged, listener, main)
        sessions.filter { isPlaying(it.playbackState) }.mapTo(playing) { it.packageName }
        watch(sessions)
    }

    fun stop() {
        if (!running) return
        running = false
        manager.removeOnActiveSessionsChangedListener(sessionsChanged)
        watched.values.forEach { (c, cb) -> c.unregisterCallback(cb) }
        watched.clear()
        playing.clear()
    }

    private fun watch(sessions: List<MediaController>) {
        val live = sessions.map { it.sessionToken }.toSet()
        watched.keys.filterNot { it in live }.forEach { t ->
            watched.remove(t)?.let { (c, cb) -> c.unregisterCallback(cb) }
        }
        for (c in sessions) {
            if (c.sessionToken in watched || c.packageName == context.packageName) continue
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = changed(c.packageName, state)
            }
            c.registerCallback(cb, main)
            watched[c.sessionToken] = c to cb
            // A session that shows up already playing is a start too.
            changed(c.packageName, c.playbackState)
        }
    }

    private fun changed(pkg: String, state: PlaybackState?) {
        if (!running) return
        if (!isPlaying(state)) {
            playing.remove(pkg)
            return
        }
        if (!playing.add(pkg) || pkg == current()) return
        val app = MediaCatalog.forPackage(context, pkg) ?: return
        GearslipLog.i("media: ${app.label} started playing, following it")
        onStarted(app)
    }

    /** Buffering and skipping are still playing: between tracks shouldn't read as a new start. */
    private fun isPlaying(state: PlaybackState?) = when (state?.state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING -> true
        else -> false
    }
}
