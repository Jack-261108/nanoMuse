package io.github.nanomuse.hands

import android.content.AttributionSource
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process

/**
 * Queries Android's settings provider under the shell UID so a wireless-launched helper can verify
 * that wireless debugging is still enabled before every IHandsDisplay operation.
 */
internal class HandsShellBridgeCalls(private val user: Int) {
    private val manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
    private val api = Class.forName("android.app.IActivityManager")
    private val acquire = api.getMethod(
        "getContentProviderExternal",
        String::class.java,
        Integer.TYPE,
        IBinder::class.java,
        String::class.java,
    )
    private val release = api.getMethod(
        "removeContentProviderExternalAsUser",
        String::class.java,
        IBinder::class.java,
        Integer.TYPE,
    )
    private val providerCall = Class.forName("android.content.IContentProvider").getMethod(
        "call",
        AttributionSource::class.java,
        String::class.java,
        String::class.java,
        String::class.java,
        Bundle::class.java,
    )
    private val shell = AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build()

    fun wirelessEnabled(): Boolean = runCatching {
        call("settings", "GET_global", "adb_wifi_enabled", null)?.getString("value") == "1"
    }.getOrDefault(false)

    private fun call(authority: String, method: String, key: String?, extras: Bundle?): Bundle? {
        val token = Binder()
        val holder = acquire.invoke(manager, authority, user, token, "nanoMuse Hands") ?: return null
        return try {
            val provider = holder.javaClass.getField("provider").get(holder) ?: return null
            providerCall.invoke(provider, shell, authority, method, key, extras) as? Bundle
        } finally {
            release.invoke(manager, authority, token, user)
        }
    }
}
