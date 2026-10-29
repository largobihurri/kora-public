package eus.bihurri.kora.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Entry point of the relay's messages: Google Play services starts this service (and the app, if it is not
 * running) for every message on `/kora/v1` sent by the paired device, and it hands them to [KoraWear].
 *
 * Declared in this library's manifest; apps add nothing. It is bound only while a message is delivered and does
 * no work of its own: the message is queued for the relay, which runs on the app's own scope.
 */
public class KoraWearListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val wear = KoraWear.current
        if (wear == null) {
            Log.w(TAG, "Message received before KoraWear.install; dropped")
            return
        }
        wear.deliver(event.sourceNodeId, event.path, event.data)
    }

    private companion object {
        const val TAG = "KoraWear"
    }
}
