package com.jedon.kellikanvas.shell

import com.jedon.kellikanvas.catalog.CatalogCollection
import com.jedon.kellikanvas.catalog.SelectedRoot

enum class ShellRoute {
    Setup,
    Home,
}

object ShellStartup {
    fun startRoute(
        collections: List<CatalogCollection>,
        rootsByCollection: Map<String, List<SelectedRoot>>,
    ): ShellRoute {
        val hasRoot = collections.any { (rootsByCollection[it.id] ?: emptyList()).isNotEmpty() }
        return if (hasRoot) ShellRoute.Home else ShellRoute.Setup
    }

    fun activeCollectionId(
        collections: List<CatalogCollection>,
        rootsByCollection: Map<String, List<SelectedRoot>>,
    ): String? = collections.firstOrNull { (rootsByCollection[it.id] ?: emptyList()).isNotEmpty() }?.id
}
