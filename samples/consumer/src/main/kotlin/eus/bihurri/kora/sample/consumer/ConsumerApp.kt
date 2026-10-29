package eus.bihurri.kora.sample.consumer

import android.app.Application
import eus.bihurri.kora.android.KoraAndroid

const val TAG = "KoraSample"

/** A node with no providers: it only uses what its peers offer. */
class ConsumerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        KoraAndroid.install(this)
    }
}
