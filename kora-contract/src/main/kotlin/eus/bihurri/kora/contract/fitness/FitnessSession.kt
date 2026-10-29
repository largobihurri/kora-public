package eus.bihurri.kora.contract.fitness

import eus.bihurri.kora.contract.Capability
import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.CapabilityVersion
import eus.bihurri.kora.contract.EventSpec
import eus.bihurri.kora.contract.NoInput
import eus.bihurri.kora.contract.OperationSpec
import eus.bihurri.kora.contract.Scope
import eus.bihurri.kora.contract.TolerantEnumSerializer
import eus.bihurri.kora.contract.WireEnum
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable

/** Phase of a workout. A newer provider may report a phase this version lacks, which reads as [UNKNOWN]. */
@Serializable(with = SessionPhase.Serializer::class)
public enum class SessionPhase(override val wireName: String) : WireEnum {
    RECORDING("recording"),
    PAUSED("paused"),
    ENDED("ended"),

    /** A phase this version does not know. */
    UNKNOWN("unknown"),
    ;

    internal object Serializer : TolerantEnumSerializer<SessionPhase>(
        "eus.bihurri.kora.contract.fitness.SessionPhase",
        entries,
        UNKNOWN,
    )
}

/** A workout in progress, or just ended when [phase] is [SessionPhase.ENDED]. */
@Serializable
public data class ActiveSession(
    public val sessionId: String,
    /** Free-form activity type chosen by the provider, e.g. `hiking`. */
    public val activityType: String,
    public val phase: SessionPhase,
    public val startedAtEpochMs: Long,
    public val elapsedMs: Long = 0,
    public val distanceMeters: Double = 0.0,
)

/** Read access to the workout session of the provider app. */
public object FitnessSession : Capability(CapabilityId("fitness.session"), CapabilityVersion(1, 0)) {
    public val ReadScope: Scope = Scope("fitness.session.read")

    /** Reserved for the future control operations (start, pause, stop); no operation uses it yet. */
    public val ControlScope: Scope = Scope("fitness.session.control")

    public val GetActiveSession: OperationSpec<NoInput, ActiveSession?> = operation(
        name = "get_active_session",
        input = NoInput.serializer(),
        output = ActiveSession.serializer().nullable,
        scope = ReadScope,
        description = "Get the session in progress, or null when none is active.",
    )

    public val SessionStarted: EventSpec<ActiveSession> = event(
        name = "session_started",
        serializer = ActiveSession.serializer(),
        scope = ReadScope,
        description = "A session started.",
    )

    public val SessionEnded: EventSpec<ActiveSession> = event(
        name = "session_ended",
        serializer = ActiveSession.serializer(),
        scope = ReadScope,
        description = "A session ended; the payload holds its final values with phase ended.",
    )

    public val SessionStateChanged: EventSpec<ActiveSession> = event(
        name = "session_state_changed",
        serializer = ActiveSession.serializer(),
        scope = ReadScope,
        description = "The phase of the session changed (for example paused or resumed).",
    )
}
