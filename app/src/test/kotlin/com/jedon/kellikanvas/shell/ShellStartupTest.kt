package com.jedon.kellikanvas.shell

import com.google.common.truth.Truth.assertThat
import com.jedon.kellikanvas.catalog.CatalogCollection
import com.jedon.kellikanvas.catalog.SelectedRoot
import com.jedon.kellikanvas.model.ProviderObjectId
import com.jedon.kellikanvas.model.SourceProfileId
import org.junit.Test

class ShellStartupTest {
    @Test
    fun opensHomeMenuEvenWithoutRoots() {
        assertThat(ShellStartup.startRoute(collections = emptyList(), rootsByCollection = emptyMap()))
            .isEqualTo(ShellRoute.Home)
        assertThat(ShellStartup.hasPlayableRoots(emptyList(), emptyMap())).isFalse()
    }

    @Test
    fun detectsPlayableRootsForSlideshow() {
        val roots = listOf(
            SelectedRoot(
                collectionId = "c1",
                profileId = SourceProfileId("p1"),
                objectId = ProviderObjectId("doc1"),
                displayLabel = "DCIM",
                includeDescendants = true,
            ),
        )
        val collections = listOf(CatalogCollection(id = "c1", label = "Main"))
        val rootsByCollection = mapOf("c1" to roots)
        assertThat(ShellStartup.startRoute(collections, rootsByCollection)).isEqualTo(ShellRoute.Home)
        assertThat(ShellStartup.hasPlayableRoots(collections, rootsByCollection)).isTrue()
        assertThat(ShellStartup.activeCollectionId(collections, rootsByCollection)).isEqualTo("c1")
    }
}
