package com.jedon.kellikanvas.shell

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.catalog.CatalogCollection
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceProfileId
import org.junit.Test

class ShellStartupTest {
    @Test
    fun needsSetupWhenNoRoots() {
        assertThat(ShellStartup.startRoute(collections = emptyList(), rootsByCollection = emptyMap()))
            .isEqualTo(ShellRoute.Setup)
    }

    @Test
    fun homeWhenAnyCollectionHasRoot() {
        val roots = listOf(
            SelectedRoot(
                collectionId = "c1",
                profileId = SourceProfileId("p1"),
                objectId = ProviderObjectId("doc1"),
                displayLabel = "DCIM",
                includeDescendants = true,
            ),
        )
        assertThat(
            ShellStartup.startRoute(
                collections = listOf(CatalogCollection(id = "c1", label = "Main")),
                rootsByCollection = mapOf("c1" to roots),
            ),
        ).isEqualTo(ShellRoute.Home)
    }
}
