plugins {
    id("kora.jvm.library")
    id("kora.publishing")
}

description = "Kora phone to watch relay protocol: frames, chunking, remote directory and transport."

kotlin {
    explicitApi()
}

dependencies {
    // The relay serves a KoraNode and exposes PeerDirectory/PeerTransport, so consumers need core (and through it
    // the contract, serialization and coroutines).
    api(project(":kora-core"))
}

dependencies {
    testImplementation(libs.kotlinx.coroutines.test)
    // Golden wire fixtures: the shared verifier of :kora-contract.
    testImplementation(testFixtures(project(":kora-contract")))
}
