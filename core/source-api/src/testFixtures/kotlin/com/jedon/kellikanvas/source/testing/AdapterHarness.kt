package com.jedon.kellikanvas.source.testing

import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.SourceFailure
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
 * Controls an optional source-specific access state.
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

/** Optional scenarios supported by a concrete source. Unsupported scenarios remain null. */
data class AdapterScenarioCapabilities(
    val invalidCredential: AccessFailureScenario? = null,
    val revokedGrant: AccessFailureScenario? = null,
) {
    override fun toString(): String = "AdapterScenarioCapabilities(" +
        "invalidCredential=${invalidCredential != null}, " +
        "revokedGrant=${revokedGrant != null})"
}

/**
 * Supplies one fresh adapter and deterministic controls to [AdapterContract].
 *
 * [ioCount] counts attempts that reach source I/O, not public adapter method calls. Values passed
 * through [sensitiveValues] should be deterministic test canaries held by the fake source.
 *
 * [stallNextListing] and [stallNextRead] must suspend the next underlying resource operation and
 * complete both signals in [ResourceStall]. In particular, a read performed eagerly by `open`
 * must be stalled so the contract can detect preloading.
 */
class AdapterHarness(
    val adapter: SourceAdapter,
    val root: FolderRef,
    val dataset: ContractDataset,
    val scenarios: AdapterScenarioCapabilities = AdapterScenarioCapabilities(),
    internal val ioCount: () -> Int,
    internal val makeMissing: suspend (AssetRef) -> Unit,
    internal val removeSource: suspend () -> Unit,
    internal val stallNextListing: suspend () -> ResourceStall,
    internal val stallNextRead: suspend (AssetRef) -> ResourceStall,
    internal val diagnostics: suspend () -> List<Any?> = { emptyList() },
    sensitiveValues: Set<String> = emptySet(),
) {
    internal val sensitiveValues: Set<String> =
        Collections.unmodifiableSet(sensitiveValues.toSet())

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
    }

    override fun toString(): String = "AdapterHarness(adapter=${adapter::class.java.simpleName}, dataset=$dataset, " +
        "scenarios=$scenarios)"
}
