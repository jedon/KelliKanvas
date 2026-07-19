package com.jedon.kellikanvas.shell

import com.jedon.kellikanvas.catalog.CatalogCollection
import com.jedon.kellikanvas.catalog.SelectedRoot

enum class ShellRoute {
    Setup,
    Home,
}

object ShellStartup {
    @Suppress("UNUSED_PARAMETER")
    fun startRoute(
        collections: List<CatalogCollection>,
        rootsByCollection: Map<String, List<SelectedRoot>>,
    ): ShellRoute = ShellRoute.Home

    fun hasPlayableRoots(
        collections: List<CatalogCollection>,
        rootsByCollection: Map<String, List<SelectedRoot>>,
    ): Boolean = collections.any { (rootsByCollection[it.id] ?: emptyList()).isNotEmpty() }

    fun activeCollectionId(
        collections: List<CatalogCollection>,
        rootsByCollection: Map<String, List<SelectedRoot>>,
    ): String? = collections.firstOrNull { (rootsByCollection[it.id] ?: emptyList()).isNotEmpty() }?.id
}
