package com.jedon.kellikanvas.source.saf

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.model.AssetRef
import com.jedon.kellikanvas.model.FolderRef
import com.jedon.kellikanvas.model.PhotoMetadata
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceEntry
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.source.testing.AccessFailureScenario
import com.jedon.kellikanvas.source.testing.AdapterContract
import com.jedon.kellikanvas.source.testing.AdapterHarness
import com.jedon.kellikanvas.source.testing.AdapterScenarioCapabilities
import com.jedon.kellikanvas.source.testing.ContractDataset
import com.jedon.kellikanvas.source.testing.ContractPhoto
import com.jedon.kellikanvas.source.testing.CredentialApplicability
import com.jedon.kellikanvas.source.testing.ResourceStall
import com.jedon.kellikanvas.source.testing.ScenarioDeclaration
import com.jedon.kellikanvas.source.testing.StreamResourceObservation
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SafSourceAdapterContractTest : AdapterContract() {
    override fun createHarness(): AdapterHarness {
        val provider = TestDocumentsProvider()
        val profile =
            SafProfile(
                id = PROFILE_ID,
                grant = SafTreeGrant(
                    treeUri = TestDocumentsProvider.TREE_URI,
                    documentId = TestDocumentsProvider.ROOT_ID,
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
                ),
            )
        val adapter = SafSourceAdapter(profile, provider)
        val dataset = createDataset(adapter.root)

        return AdapterHarness(
            adapter = adapter,
            root = adapter.root,
            dataset = dataset,
            scenarios = AdapterScenarioCapabilities(
                credentialApplicability = CredentialApplicability.NOT_USED,
                invalidCredential = ScenarioDeclaration.NotApplicable(
                    "SAF uses a platform URI grant instead of credentials",
                ),
                revokedGrant = ScenarioDeclaration.Supported(
                    AccessFailureScenario(
                        arrange = { provider.mode = TestDocumentsProvider.Mode.REVOKED },
                        exercise = { it.adapter.probe() },
                        adapterSpecificAssertions = { failure ->
                            assertThat(failure.diagnosticCode).isEqualTo("permission_revoked")
                            assertThat(failure.recoveryCode).isEqualTo("reauthorize")
                        },
                    ),
                ),
                timeout = ScenarioDeclaration.NotApplicable(
                    "SAF document access has no adapter-managed network timeout",
                ),
                protocolFailure = ScenarioDeclaration.NotApplicable(
                    "SAF delegates provider transport to the Android document API",
                ),
            ),
            ioCount = { provider.ioCount },
            makeMissing = { asset -> provider.remove(asset.objectId.value) },
            removeSource = { provider.mode = TestDocumentsProvider.Mode.REMOVED },
            stallNextListing = {
                provider.stallNextListing().let { ResourceStall(it.started, it.closed) }
            },
            stallNextRead = { asset ->
                provider.stallNextRead(asset.objectId.value).let { ResourceStall(it.started, it.closed) }
            },
            streamObservation = { asset ->
                provider.observation(asset.objectId.value).let {
                    StreamResourceObservation(
                        openedStreams = it.opened,
                        bytesRead = it.bytesRead,
                        closedStreams = it.closed,
                    )
                }
            },
            diagnostics = { listOf(provider) },
        )
    }

    private fun createDataset(root: FolderRef): ContractDataset {
        val landscape =
            SourceEntry.Folder(
                ref = FolderRef(PROFILE_ID, ProviderObjectId(TestDocumentsProvider.LANDSCAPE_ID)),
                name = "Landscape",
            )
        val portrait =
            SourceEntry.Folder(
                ref = FolderRef(PROFILE_ID, ProviderObjectId(TestDocumentsProvider.PORTRAIT_ID)),
                name = "Portrait",
            )
        val cover = photo(TestDocumentsProvider.COVER_ID, "Welcome", "image/jpeg", TestDocumentsProvider.COVER_BYTES)
        val mountain =
            photo(
                TestDocumentsProvider.MOUNTAIN_ID,
                "Mountain",
                "image/jpeg",
                TestDocumentsProvider.MOUNTAIN_BYTES,
            )
        val person =
            photo(
                TestDocumentsProvider.PERSON_ID,
                "Person",
                "image/png",
                TestDocumentsProvider.PERSON_BYTES,
            )
        return ContractDataset(
            root = root,
            childrenByFolder = linkedMapOf(
                root to listOf(landscape, portrait, cover.entry),
                landscape.ref to listOf(mountain.entry),
                portrait.ref to listOf(person.entry),
            ),
            photos = listOf(cover, mountain, person),
        )
    }

    private fun photo(
        id: String,
        name: String,
        mimeType: String,
        bytes: ByteArray,
    ): ContractPhoto {
        val asset =
            AssetRef(
                profileId = PROFILE_ID,
                objectId = ProviderObjectId(id),
                mimeType = mimeType,
                byteLength = bytes.size.toLong(),
                modifiedAtMillis = MODIFIED_AT,
            )
        val entry = SourceEntry.Photo(asset = asset, name = name)
        return ContractPhoto(entry, PhotoMetadata(asset), bytes)
    }

    private companion object {
        val PROFILE_ID = SourceProfileId("saf-contract-profile")
        const val MODIFIED_AT = 1_700_000_000_000L
    }
}
