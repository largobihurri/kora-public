package eus.bihurri.kora.android

import eus.bihurri.kora.core.CallerKind
import kotlinx.coroutines.Job

/**
 * One subscription being served: who opened it and the job that pumps its events to the remote sink.
 * [notifyRevoked] tells the subscriber that access was withdrawn.
 */
internal class ServerSubscription(val kind: CallerKind, private val notifyRevoked: () -> Unit) {
    @Volatile
    private var job: Job? = null

    fun attach(job: Job) {
        this.job = job
    }

    /** Stops pumping events. Idempotent; the sink is not told. */
    fun cancel() {
        job?.cancel()
    }

    /** Stops the subscription and tells the subscriber that its access was withdrawn. */
    fun revoke() {
        cancel()
        notifyRevoked()
    }
}

/** The subscriptions currently served, so that access can be revoked while they are live. */
internal class SubscriptionRegistry {
    private val lock = Any()
    private val active = LinkedHashSet<ServerSubscription>()

    val size: Int get() = synchronized(lock) { active.size }

    fun add(subscription: ServerSubscription) {
        synchronized(lock) { active += subscription }
    }

    fun remove(subscription: ServerSubscription) {
        synchronized(lock) { active -= subscription }
    }

    /** Takes every subscription matching [predicate] out of the registry and returns them. */
    fun removeWhere(predicate: (ServerSubscription) -> Boolean): List<ServerSubscription> = synchronized(lock) {
        val matching = active.filter(predicate)
        active.removeAll(matching.toSet())
        matching
    }
}
