package app.seb3thehacker.gearslip.media

import app.seb3thehacker.gearslip.host.AndroidAuto
import app.seb3thehacker.gearslip.host.CarDescriptor
import app.seb3thehacker.gearslip.host.KnownApps
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.service.media.MediaBrowserService

/** A media app that has told the system it belongs in a car. */
class MediaApp(
    val component: ComponentName,
    val label: String,
    /** Whose audio to capture: playback capture is scoped to one uid so nothing else leaks in. */
    val uid: Int,
    /**
     * No car library at all (YouTube, a podcast app, a browser tab): found only by its playback
     * session, so the player drives that session and never tries to browse.
     */
    val sessionOnly: Boolean = false,
)

/**
 * Finds media apps that opted in to Android Auto.
 *
 * An app is a car media app when it exports a MediaBrowserService *and* its car descriptor (see
 * [CarDescriptor]) lists `media`. The descriptor is what separates Deezer from a phone-only
 * player that merely exposes a browser service to Bluetooth and Wear - and from messengers like
 * Signal and Molly, which declare only `notification` but keep a browser service for voice notes.
 */
object MediaCatalog {

    fun installed(context: Context): List<MediaApp> {
        val manager = context.packageManager
        return manager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE), PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.serviceInfo.packageName != context.packageName }
            .filterNot { AndroidAuto.matches(it.serviceInfo.packageName, it.loadLabel(manager).toString()) }
            .filterNot { KnownApps.isHidden(it.serviceInfo.packageName) }
            .filter { declaresMedia(manager, it.serviceInfo) }
            .map {
                val info = it.serviceInfo
                MediaApp(
                    component = ComponentName(info.packageName, info.name),
                    label = info.applicationInfo?.loadLabel(manager)?.toString() ?: info.packageName,
                    uid = info.applicationInfo?.uid ?: -1,
                )
            }
            .distinctBy { it.component.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /**
     * The player for one package, as the launcher would list it if it has a car library, or
     * session-only if it doesn't - so anything with a media session can be driven from the car.
     */
    fun forPackage(context: Context, packageName: String): MediaApp? {
        if (packageName == context.packageName || KnownApps.isHidden(packageName)) return null
        val manager = context.packageManager
        val app = runCatching { manager.getApplicationInfo(packageName, 0) }.getOrNull() ?: return null
        val label = app.loadLabel(manager).toString()
        if (AndroidAuto.matches(packageName, label)) return null
        val car = manager.queryIntentServices(
            Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(packageName), PackageManager.GET_META_DATA,
        ).firstOrNull { declaresMedia(manager, it.serviceInfo) }?.serviceInfo
        return if (car != null) {
            MediaApp(ComponentName(car.packageName, car.name), label, app.uid)
        } else {
            MediaApp(ComponentName(packageName, SESSION_ONLY_CLASS), label, app.uid, sessionOnly = true)
        }
    }

    /** Placeholder class for a session-only player's component: nothing ever binds to it. */
    private const val SESSION_ONLY_CLASS = "gearslip.SessionOnly"

    /** A descriptor that can't be read keeps the app, rather than dropping a real player over an odd manifest. */
    private fun declaresMedia(manager: PackageManager, service: android.content.pm.ServiceInfo): Boolean {
        if (CarDescriptor.meta(manager, service.packageName, service) == null) return false
        val uses = CarDescriptor.uses(manager, service.packageName, service)
        return uses.isNullOrEmpty() || "media" in uses
    }
}
