package com.jedon.kellikanvas.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TvHomeDestinationsTest {
    @Test
    fun drawerListsAllDestinationsInDeclarationOrder() {
        assertThat(tvHomeDrawerDestinations).containsExactly(
            TvHomeDestination.Home,
            TvHomeDestination.Collection,
            TvHomeDestination.Appearance,
            TvHomeDestination.Playback,
            TvHomeDestination.Ambient,
            TvHomeDestination.System,
        ).inOrder()
    }

    @Test
    fun homeAndCollectionAreInShellDestinations() {
        assertThat(
            tvHomeDrawerDestinations.filter { it.inShell },
        ).containsExactly(
            TvHomeDestination.Home,
            TvHomeDestination.Collection,
        )
    }

    @Test
    fun settingsDestinationsLeaveTheShell() {
        assertThat(TvHomeDestination.Appearance.inShell).isFalse()
        assertThat(TvHomeDestination.Playback.inShell).isFalse()
        assertThat(TvHomeDestination.Ambient.inShell).isFalse()
        assertThat(TvHomeDestination.System.inShell).isFalse()
    }

    @Test
    fun backFromCollectionReturnsHome() {
        assertThat(tvHomeBackTarget(TvHomeDestination.Collection))
            .isEqualTo(TvHomeDestination.Home)
    }

    @Test
    fun backFromHomeExits() {
        assertThat(tvHomeBackTarget(TvHomeDestination.Home)).isNull()
    }

    @Test
    fun everyDestinationHasNonBlankLabel() {
        tvHomeDrawerDestinations.forEach { destination ->
            assertThat(destination.label.isNotBlank()).isTrue()
        }
    }
}
