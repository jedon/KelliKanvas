package com.jedon.kellikanvas.feature.collection

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceProfileId
import com.jedon.kellikanvas.source.smb.HouseholdNasDefaults
import org.junit.Test

class HouseholdNasBootstrapTest {
    @Test
    fun emptyRootsNeedReplace() {
        assertThat(HouseholdNasBootstrap.needsHouseholdRootReplace(emptyList())).isTrue()
    }

    @Test
    fun preferredFrameTvRootSkipsReplace() {
        val roots =
            listOf(
                SelectedRoot(
                    collectionId = "c1",
                    profileId = SourceProfileId("smb"),
                    objectId = ProviderObjectId(HouseholdNasDefaults.FRAME_TV_16X9_PATH),
                    displayLabel = "16X9",
                    includeDescendants = true,
                ),
            )
        assertThat(HouseholdNasBootstrap.needsHouseholdRootReplace(roots)).isFalse()
    }

    @Test
    fun digitalPhotosRootNeedsReplace() {
        val roots =
            listOf(
                SelectedRoot(
                    collectionId = "c1",
                    profileId = SourceProfileId("smb"),
                    objectId = ProviderObjectId("Digital Photos"),
                    displayLabel = "Digital Photos",
                    includeDescendants = true,
                ),
            )
        assertThat(HouseholdNasBootstrap.needsHouseholdRootReplace(roots)).isTrue()
    }

    @Test
    fun wholePhotosLabelNeedsReplace() {
        val roots =
            listOf(
                SelectedRoot(
                    collectionId = "c1",
                    profileId = SourceProfileId("dlna"),
                    objectId = ProviderObjectId("photos-oid"),
                    displayLabel = "Photos",
                    includeDescendants = true,
                ),
            )
        assertThat(HouseholdNasBootstrap.needsHouseholdRootReplace(roots)).isTrue()
    }
}
