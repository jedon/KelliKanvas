package com.jedon.kellikanvas.source.testing

import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.source.SourceAdapter
import kotlinx.coroutines.Deferred
import java.util.Collections

/** A deterministic signal for a deliberately suspended underlying source resource. */
class ResourceStall(
    val started: Deferred<Unit>,
    val closed: Deferred<Unit>,
) {
    override fun toString(): String = "ResourceStall(started=${started.isCompleted}, closed=${closed.isCompleted})"
}

/**
 * Controls a supported source-specific failure state.
 *
 * [adapterSpecificAssertions] is deliberately required: a source that declares this capability
 * must verify its own credential or grant behavior in addition to the normalized failure.
 */
class AccessFailureScenario(
    internal val arrange: suspend () -> Unit,
    internal val exercise: suspend (AdapterHarness) -> Unit,
    internal val adapterSpecificAssertions: suspend (SourceFailure) -> Unit,
) {
    override fun toString(): String = "AccessFailureScenario(<redacted>)"
}

enum class CredentialApplicability {
    REQUIRED,
    NOT_USED,
}

sealed interface ScenarioDeclaration {
    data class Supported(
        val scenario: AccessFailureScenario,
    ) : ScenarioDeclaration {
        override fun toString(): String = "Supported"
    }

    data class NotApplicable(
        val reason: String,
    ) : ScenarioDeclaration {
        init {
            require(reason.isNotBlank()) { "Not-applicable scenario reason must not be blank" }
        }
    }
}

/** Explicit scenario support for one configured source profile. */
data class AdapterScenarioCapabilities(
    val credentialApplicability: CredentialApplicability,
    val invalidCredential: ScenarioDeclaration,
    val revokedGrant: ScenarioDeclaration,
    val timeout: ScenarioDeclaration,
    val protocolFailure: ScenarioDeclaration,
) {
    internal fun validateFor(kind: SourceKind) {
        if (kind == SourceKind.SMB) {
            require(credentialApplicability == CredentialApplicability.REQUIRED) {
                "SMB contract profiles must declare credential use"
            }
        }
        when (credentialApplicability) {
            CredentialApplicability.REQUIRED ->
                require(invalidCredential is ScenarioDeclaration.Supported) {
                    "Credential-based profiles must support invalid-credential testing"
                }
            CredentialApplicability.NOT_USED ->
                require(invalidCredential is ScenarioDeclaration.NotApplicable) {
                    "Profiles without credentials must mark invalid credentials not applicable"
                }
        }
        if (kind == SourceKind.SAF) {
            require(revokedGrant is ScenarioDeclaration.Supported) {
                "SAF profiles must support revoked-grant testing"
            }
            require(timeout is ScenarioDeclaration.NotApplicable) {
                "SAF profiles must mark network timeout not applicable"
            }
            require(protocolFailure is ScenarioDeclaration.NotApplicable) {
                "SAF profiles must mark network protocol failure not applicable"
            }
        } else {
            require(timeout is ScenarioDeclaration.Supported) {
                "Network profiles must support timeout testing"
            }
            require(protocolFailure is ScenarioDeclaration.Supported) {
                "Network profiles must support protocol-failure testing"
            }
        }
    }
}

data class StreamResourceObservation(
    val openedStreams: Int,
    val bytesRead: Long,
    val closedStreams: Int,
) {
    init {
        require(openedStreams >= 0) { "Opened stream count must be nonnegative" }
        require(bytesRead >= 0) { "Observed byte count must be nonnegative" }
        require(closedStreams in 0..openedStreams) {
            "Closed stream count must not exceed opened streams"
        }
    }
}

/**
 * Supplies one fresh adapter and deterministic controls to [AdapterContract].
 *
 * [ioCount] counts attempts that reach source I/O, not public adapter method calls.
 * [streamObservation] reports underlying open, read, and close activity without performing I/O.
 * Dataset names and raw identifiers become privacy canaries automatically; values passed through
 * [sensitiveValues] add source-specific credentials, URLs, and paths held by the fake source.
 *
 * [stallNextListing] and [stallNextRead] must suspend the next underlying resource operation and
 * complete both signals in [ResourceStall]. In particular, a read performed eagerly by `open`
 * must be stalled so the contract can detect preloading.
 */
class AdapterHarness(
    val adapter: SourceAdapter,
    val root: FolderRef,
    val dataset: ContractDataset,
    val scenarios: AdapterScenarioCapabilities,
    internal val ioCount: () -> Int,
    internal val makeMissing: suspend (AssetRef) -> Unit,
    internal val removeSource: suspend () -> Unit,
    internal val stallNextListing: suspend () -> ResourceStall,
    internal val stallNextRead: suspend (AssetRef) -> ResourceStall,
    internal val streamObservation: (AssetRef) -> StreamResourceObservation,
    internal val diagnostics: suspend () -> List<Any?> = { emptyList() },
    sensitiveValues: Set<String> = emptySet(),
) {
    internal val sensitiveValues: Set<String> =
        Collections.unmodifiableSet(dataset.sensitiveCanaries + sensitiveValues)

    init {
        require(adapter.profileId == root.profileId) {
            "Adapter and root must belong to the same profile"
        }
        require(dataset.root == root) {
            "Harness root must match the contract dataset root"
        }
        require(sensitiveValues.none(String::isBlank)) {
            "Sensitive diagnostic canaries must not be blank"
        }
        scenarios.validateFor(adapter.kind)
    }

    override fun toString(): String = "AdapterHarness(adapter=${adapter::class.java.simpleName}, dataset=$dataset, " +
        "scenarios=$scenarios)"
}
