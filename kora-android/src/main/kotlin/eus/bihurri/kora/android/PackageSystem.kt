package eus.bihurri.kora.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build

/** [PeerScanner] over `PackageManager`: services for [KoraAndroid.ACTION_NODE] in packages with our signature. */
internal class AndroidPeerScanner(private val context: Context) : PeerScanner {
    override fun scan(): List<PeerCandidate> {
        val pm = context.packageManager
        val own = context.packageName
        val intent = Intent(KoraAndroid.ACTION_NODE)
        val services = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, 0)
        }
        return services
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
            .filter { it != own && pm.checkSignatures(own, it) == PackageManager.SIGNATURE_MATCH }
            .mapNotNull { pkg ->
                val info = try {
                    if (Build.VERSION.SDK_INT >= 33) {
                        pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(pkg, 0)
                    }
                } catch (_: PackageManager.NameNotFoundException) {
                    return@mapNotNull null
                }
                PeerCandidate(pkg, "${info.lastUpdateTime}:${info.longVersionCode}")
            }
    }
}

/**
 * [PackageChangeSource] over a dynamically registered receiver for the package broadcasts. The receiver is
 * exported because those broadcasts come from the system; a forged one only causes a harmless rescan.
 */
internal class BroadcastPackageChangeSource(private val context: Context) : PackageChangeSource {
    override fun register(onChange: () -> Unit): AutoCloseable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onChange()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        return AutoCloseable { context.unregisterReceiver(receiver) }
    }
}
