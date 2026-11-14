package eus.bihurri.kora.sample.consumer

import android.app.Application
import eus.bihurri.kora.android.KoraAndroid
import eus.bihurri.kora.core.DefaultCallerPolicy

const val TAG = "KoraSample"

/**
 * A node with no providers: it only uses what its peers offer. Its gate is open to the adb shell for the relayed read
 * tools ([ConsumerExternalAccess]), so the Binder path can be checked with `kora-bridge` (README).
 */
class ConsumerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        KoraAndroid.install(this, policy = DefaultCallerPolicy(ConsumerExternalAccess()))
    }
}
