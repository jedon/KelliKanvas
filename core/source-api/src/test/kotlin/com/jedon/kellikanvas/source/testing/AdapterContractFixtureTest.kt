package com.jedon.kellikanvas.source.testing

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.Page
import com.jedon.kellikanvas.model.PageCursor
import com.jedon.kellikanvas.model.PhotoMetadata
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceCapabilities
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceFailure
import com.jedon.kellikanvas.model.SourceKind
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.model.SourceStatus
import com.jedon.kellikanvas.source.PhotoByteStream
import com.jedon.kellikanvas.source.SourceAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.Closeable

class AdapterContractFixtureTest : AdapterContract() {
    @Test
    fun `fake harness builds a separate dataset each time`() {
        val first = createHarness()
        val second = createHarness()

        assertThat(second.dataset).isNotSameInstanceAs(first.dataset)
    }

    @Test
    fun `derived dataset canaries catch leaked folder names and provider IDs`() {
        listOf("sensitive-folder", "nested-stable-id").forEach { leak ->
            val contract =
                object : AdapterContract() {
                    override fun createHarness(): AdapterHarness = this@AdapterContractFixtureTest.newHarness(
                        diagnosticLeak = leak,
                    )
                }

            assertThrows(AssertionError::class.java) {
                contract.`diagnostics do not disclose source secrets or sensitive paths`()
            }
        }
    }

    @Test
    fun `adapter-created cancellation is rejected`() {
        val contract =
            object : AdapterContract() {
                override fun createHarness(): AdapterHarness = this@AdapterContractFixtureTest.newHarness(
                    translateCancellation = true,
                )
            }

        assertThrows(AssertionError::class.java) {
            contract.`listing cancellation propagates and closes its resource`()
        }
    }

    @Test
    fun `recovered cancellation wrapper is accepted`() {
        val contract =
            object : AdapterContract() {
                override fun createHarness(): AdapterHarness = this@AdapterContractFixtureTest.newHarness(
                    wrapCancellation = true,
                )
            }

        contract.`listing cancellation propagates and closes its resource`()
    }

    @Test
    fun `stalled listing has a bounded diagnostic timeout`() {
        val contract =
            object : AdapterContract() {
                override fun createHarness(): AdapterHarness = this@AdapterContractFixtureTest.newHarness(
                    signalListingStart = false,
                )
            }

        val failure =
            assertThrows(AssertionError::class.java) {
                contract.`listing cancellation propagates and closes its resource`()
            }

        assertThat(failure).hasMessageThat().contains("listing resource to start")
    }

    @Test
    fun `unclosed listing has a bounded diagnostic timeout`() {
        val contract =
            object : AdapterContract() {
                override fun createHarness(): AdapterHarness = this@AdapterContractFixtureTest.newHarness(
                    signalListingClose = false,
                )
            }

        val failure =
            assertThrows(AssertionError::class.java) {
                contract.`listing cancellation propagates and closes its resource`()
            }

        assertThat(failure).hasMessageThat().contains("listing resource to close")
    }

    @Test
    fun `SAF revoked grant declaration executes its normalized scenario`() {
        val contract =
            object : AdapterContract() {
                override fun createHarness(): AdapterHarness = this@AdapterContractFixtureTest.newHarness(
                    kind = SourceKind.SAF,
                    credentialApplicability = CredentialApplicability.NOT_USED,
                )
            }

        contract.`configured revoked grants normalize to permission revoked`()
    }

    @Test
    fun `required source scenarios reject not-applicable declarations`() {
        val invalidHarnesses =
            listOf<() -> AdapterHarness>(
                {
                    newHarness(
                        kind = SourceKind.SAF,
                        credentialApplicability = CredentialApplicability.NOT_USED,
                        transformScenarios = {
                            it.copy(
                                revokedGrant =
                                ScenarioDeclaration.NotApplicable(
                                    "Test omission",
                                ),
                            )
                        },
                    )
                },
                {
                    newHarness(
                        kind = SourceKind.SMB,
                        credentialApplicability = CredentialApplicability.REQUIRED,
                        transformScenarios = {
                            it.copy(
                                invalidCredential =
                                ScenarioDeclaration.NotApplicable(
                                    "Test omission",
                                ),
                            )
                        },
                    )
                },
                {
                    newHarness(
                        kind = SourceKind.HTTP,
                        credentialApplicability = CredentialApplicability.REQUIRED,
                        transformScenarios = {
                            it.copy(
                                invalidCredential =
                                ScenarioDeclaration.NotApplicable(
                                    "Test omission",
                                ),
                            )
                        },
                    )
                },
            ) +
                listOf(SourceKind.HTTP, SourceKind.SMB, SourceKind.DLNA).flatMap { kind ->
                    listOf<() -> AdapterHarness>(
                        {
                            newHarness(
                                kind = kind,
                                credentialApplicability =
                                if (kind == SourceKind.SMB) {
                                    CredentialApplicability.REQUIRED
                                } else {
                                    CredentialApplicability.NOT_USED
                                },
                                transformScenarios = {
                                    it.copy(
                                        timeout =
                                        ScenarioDeclaration.NotApplicable(
                                            "Test omission",
                                        ),
                                    )
                                },
                            )
                        },
                        {
                            newHarness(
                                kind = kind,
                                credentialApplicability =
                                if (kind == SourceKind.SMB) {
                                    CredentialApplicability.REQUIRED
                                } else {
                                    CredentialApplicability.NOT_USED
                                },
                                transformScenarios = {
                                    it.copy(
                                        protocolFailure =
                                        ScenarioDeclaration.NotApplicable(
                                            "Test omission",
                                        ),
                                    )
                                },
                            )
                        },
                    )
                }

        invalidHarnesses.forEach { createInvalidHarness ->
            assertThrows(IllegalArgumentException::class.java) {
                createInvalidHarness()
            }
        }
    }

    override fun createHarness(): AdapterHarness = newHarness()

    private fun newHarness(
        kind: SourceKind = SourceKind.HTTP,
        credentialApplicability: CredentialApplicability = CredentialApplicability.REQUIRED,
        diagnosticLeak: String? = null,
        translateCancellation: Boolean = false,
        wrapCancellation: Boolean = false,
        signalListingStart: Boolean = true,
        signalListingClose: Boolean = true,
        transformScenarios: (AdapterScenarioCapabilities) -> AdapterScenarioCapabilities = { it },
    ): AdapterHarness {
        val profileId = SourceProfileId("fixture-profile")
        val root = FolderRef(profileId, ProviderObjectId("root-stable-id"))
        val nested = FolderRef(profileId, ProviderObjectId("nested-stable-id"))
        val first =
            photo(
                profileId = profileId,
                id = "first-stable-id",
                name = "private-family-photo.jpg",
                bytes = "first-photo-payload",
                width = 640,
                height = 480,
            )
        val second =
            photo(
                profileId = profileId,
                id = "second-stable-id",
                name = "tax-record-photo.png",
                bytes = "second-photo-payload",
                width = 800,
                height = 600,
            )
        val nestedPhoto =
            photo(
                profileId = profileId,
                id = "nested-photo-stable-id",
                name = "medical-photo.jpeg",
                bytes = "nested-photo-payload",
                width = 1024,
                height = 768,
            )
        val dataset =
            ContractDataset(
                root = root,
                childrenByFolder =
                mapOf(
                    root to
                        listOf(
                            first.entry,
                            SourceEntry.Folder(nested, "sensitive-folder"),
                            second.entry,
                        ),
                    nested to
                        listOf(
                            nestedPhoto.entry,
                            SourceEntry.Folder(root, "cycle-to-root"),
                        ),
                ),
                photos = listOf(first, second, nestedPhoto),
            )
        val sensitiveValues =
            setOf(
                "credential-value-123",
                "bearer-value-456",
                "https://user:pass@private.example/photos?token=secret",
                """\\private-server\family\photos""",
                "/private/catalog.xml",
                """C:\private\catalog.json""",
            )
        val adapter =
            InMemoryAdapter(
                profileId = profileId,
                dataset = dataset,
                privateConfiguration = sensitiveValues,
                kind = kind,
                translateCancellation = translateCancellation,
                wrapCancellation = wrapCancellation,
                signalListingStart = signalListingStart,
                signalListingClose = signalListingClose,
            )

        return AdapterHarness(
            adapter = adapter,
            root = root,
            dataset = dataset,
            ioCount = adapter::ioCount,
            makeMissing = adapter::makeMissing,
            removeSource = adapter::removeSource,
            stallNextListing = adapter::stallNextListing,
            stallNextRead = adapter::stallNextRead,
            streamObservation = adapter::streamObservation,
            scenarios =
            transformScenarios(
                adapter.scenarios(credentialApplicability),
            ),
            diagnostics = {
                listOf(
                    adapter,
                    adapter.probe(),
                    root,
                    dataset,
                    dataset.children(root),
                ) + listOfNotNull(diagnosticLeak)
            },
            sensitiveValues = sensitiveValues,
        )
    }

    private fun photo(
        profileId: SourceProfileId,
        id: String,
        name: String,
        bytes: String,
        width: Int,
        height: Int,
    ): ContractPhoto {
        val asset =
            AssetRef(
                profileId = profileId,
                objectId = ProviderObjectId(id),
                mimeType = "image/jpeg",
                byteLength = bytes.length.toLong(),
                modifiedAtMillis = 1_000,
                eTag = "private-etag",
                versionToken = "private-version",
            )
        return ContractPhoto(
            entry = SourceEntry.Photo(asset, name, width, height),
            metadata =
            PhotoMetadata(
                asset = asset,
                width = width,
                height = height,
                captureTimeMillis = 500,
            ),
            bytes = bytes.encodeToByteArray(),
        )
    }
}

private class InMemoryAdapter(
    override val profileId: SourceProfileId,
    private val dataset: ContractDataset,
    private val privateConfiguration: Set<String>,
    override val kind: SourceKind,
    private val translateCancellation: Boolean,
    private val wrapCancellation: Boolean,
    private val signalListingStart: Boolean,
    private val signalListingClose: Boolean,
) : SourceAdapter() {
    override val capabilities =
        SourceCapabilities(
            supportsPaging = true,
            supportsReliableModifiedTime = true,
            supportsETag = true,
            supportsVersionToken = true,
        )

    private var state = State.AVAILABLE
    private val missing = mutableSetOf<AssetRef>()
    private var operations = 0
    private var listingStall: TestResourceStall? = null
    private var readStall: TestResourceStall? = null
    private val streams = mutableMapOf<AssetRef, MutableList<FakePhotoByteStream>>()
    var credentialWasInvalidated = false
        private set
    var permissionWasRevoked = false
        private set

    fun ioCount(): Int = operations

    fun makeMissing(asset: AssetRef) {
        missing += asset
    }

    fun removeSource() {
        state = State.REMOVED
    }

    fun invalidateCredential() {
        credentialWasInvalidated = true
        state = State.INVALID_CREDENTIAL
    }

    fun revokePermission() {
        permissionWasRevoked = true
        state = State.REVOKED_PERMISSION
    }

    fun timeOut() {
        state = State.TIMED_OUT
    }

    fun failProtocol() {
        state = State.PROTOCOL_FAILURE
    }

    fun streamObservation(asset: AssetRef): StreamResourceObservation {
        val opened = streams[asset].orEmpty()
        return StreamResourceObservation(
            openedStreams = opened.size,
            bytesRead = opened.sumOf(FakePhotoByteStream::bytesRead),
            closedStreams = opened.count(FakePhotoByteStream::closed),
        )
    }

    fun scenarios(
        credentialApplicability: CredentialApplicability,
    ): AdapterScenarioCapabilities {
        val invalidCredential =
            if (credentialApplicability == CredentialApplicability.REQUIRED) {
                supportedScenario(
                    arrange = ::invalidateCredential,
                    exercise = { it.adapter.probe() },
                    expectedOperation = "probe",
                    adapterAssertion = { credentialWasInvalidated },
                )
            } else {
                ScenarioDeclaration.NotApplicable("Profile does not use credentials")
            }
        val revokedGrant =
            if (kind == SourceKind.SAF) {
                supportedScenario(
                    arrange = ::revokePermission,
                    exercise = { it.adapter.listChildren(it.root, null) },
                    expectedOperation = "list_children",
                    adapterAssertion = { permissionWasRevoked },
                )
            } else {
                ScenarioDeclaration.NotApplicable("Source has no persisted permission grant")
            }
        val timeout =
            if (kind != SourceKind.SAF) {
                supportedScenario(
                    arrange = ::timeOut,
                    exercise = { it.adapter.probe() },
                    expectedOperation = "probe",
                )
            } else {
                ScenarioDeclaration.NotApplicable("Local document access has no network timeout")
            }
        val protocolFailure =
            if (kind != SourceKind.SAF) {
                supportedScenario(
                    arrange = ::failProtocol,
                    exercise = { it.adapter.probe() },
                    expectedOperation = "probe",
                )
            } else {
                ScenarioDeclaration.NotApplicable("Local document access has no network protocol")
            }
        return AdapterScenarioCapabilities(
            credentialApplicability = credentialApplicability,
            invalidCredential = invalidCredential,
            revokedGrant = revokedGrant,
            timeout = timeout,
            protocolFailure = protocolFailure,
        )
    }

    fun stallNextListing(): ResourceStall {
        check(listingStall == null)
        return TestResourceStall(signalListingClose).also { listingStall = it }.probe
    }

    fun stallNextRead(asset: AssetRef): ResourceStall {
        check(dataset.photo(asset) != null)
        check(readStall == null)
        return TestResourceStall().also { readStall = it }.probe
    }

    override suspend fun probe(): SourceStatus {
        operations += 1
        checkAvailable("probe")
        return SourceStatus(available = true, summary = "Connected")
    }

    override suspend fun listChildrenPage(
        folder: FolderRef,
        cursor: PageCursor?,
        limit: Int,
    ): Page<SourceEntry> {
        operations += 1
        checkAvailable("list_children")
        listingStall?.also { listingStall = null }?.use {
            if (signalListingStart) it.started.complete(Unit)
            awaitConfiguredCancellation()
        }
        val children = dataset.children(folder)
        val offset = cursor?.value?.toInt() ?: 0
        val end = minOf(offset + limit, children.size)
        return Page(
            items = children.subList(offset, end),
            nextCursor = if (end < children.size) PageCursor(end.toString()) else null,
        )
    }

    override suspend fun metadataFor(asset: AssetRef): PhotoMetadata {
        operations += 1
        checkAvailable("metadata")
        if (asset in missing) throw SourceFailure.NotFound(profileId, "metadata")
        return dataset.photo(asset)?.metadata
            ?: throw SourceFailure.NotFound(profileId, "metadata")
    }

    override suspend fun openStream(asset: AssetRef): PhotoByteStream {
        operations += 1
        checkAvailable("open")
        if (asset in missing) throw SourceFailure.NotFound(profileId, "open")
        val expected =
            dataset.photo(asset)
                ?: throw SourceFailure.NotFound(profileId, "open")
        val stall = readStall?.also { readStall = null }
        return FakePhotoByteStream(
            bytes = expected.bytes,
            maxChunkSize = 3,
            beforeRead = {
                stall?.started?.complete(Unit)
                if (stall != null) awaitConfiguredCancellation()
            },
            onClose = { stall?.close() },
        ).also { stream ->
            streams.getOrPut(asset, ::mutableListOf).add(stream)
        }
    }

    private fun checkAvailable(operation: String) {
        when (state) {
            State.AVAILABLE -> Unit
            State.INVALID_CREDENTIAL ->
                throw SourceFailure.AuthenticationRequired(profileId, operation)
            State.REMOVED -> throw SourceFailure.SourceUnavailable(profileId, operation)
            State.REVOKED_PERMISSION ->
                throw SourceFailure.PermissionRevoked(profileId, operation)
            State.TIMED_OUT -> throw SourceFailure.Timeout(profileId, operation)
            State.PROTOCOL_FAILURE -> throw SourceFailure.ProtocolFailure(profileId, operation)
        }
    }

    private fun supportedScenario(
        arrange: suspend () -> Unit,
        exercise: suspend (AdapterHarness) -> Unit,
        expectedOperation: String,
        adapterAssertion: () -> Boolean = { true },
    ): ScenarioDeclaration.Supported = ScenarioDeclaration.Supported(
        AccessFailureScenario(
            arrange = arrange,
            exercise = exercise,
            adapterSpecificAssertions = { failure ->
                assertThat(failure.operation).isEqualTo(expectedOperation)
                assertThat(adapterAssertion()).isTrue()
            },
        ),
    )

    private suspend fun awaitConfiguredCancellation(): Nothing {
        try {
            awaitCancellation()
        } catch (failure: CancellationException) {
            if (translateCancellation) {
                throw CancellationException("adapter-created-cancellation")
            }
            if (wrapCancellation) {
                throw CancellationException("recovered-cancellation").also {
                    it.initCause(failure)
                }
            }
            throw failure
        }
    }

    override fun toString(): String = "InMemoryAdapter(profile=<redacted>, state=$state, privateValues=${privateConfiguration.size})"

    private enum class State {
        AVAILABLE,
        INVALID_CREDENTIAL,
        REMOVED,
        REVOKED_PERMISSION,
        TIMED_OUT,
        PROTOCOL_FAILURE,
    }
}

private class TestResourceStall(
    private val signalClose: Boolean = true,
) : Closeable {
    val started = CompletableDeferred<Unit>()
    private val closed = CompletableDeferred<Unit>()
    val probe = ResourceStall(started = started, closed = closed)

    override fun close() {
        if (signalClose) closed.complete(Unit)
    }
}
