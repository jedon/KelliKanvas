package com.jedon.kellikanvas.home

/**
 * Destinations shown in the TV home navigation drawer.
 *
 * In-shell destinations ([inShell] = true) swap the content pane inside TvHomeShell;
 * the others fire navigation callbacks and leave the shell via the NavHost.
 * Declaration order is the drawer order, top to bottom; add new entries here.
 */
enum class TvHomeDestination(
    val label: String,
    val inShell: Boolean,
) {
    Home(label = "Home", inShell = true),
    Collection(label = "Collection", inShell = true),
    Appearance(label = "Appearance", inShell = false),
    Playback(label = "Playback", inShell = false),
    Ambient(label = "Ambient", inShell = false),
    System(label = "System", inShell = false),
    Diagnostics(label = "Diagnostics", inShell = false),
}

/** Drawer entries, top to bottom. */
val tvHomeDrawerDestinations: List<TvHomeDestination> = TvHomeDestination.entries.toList()

/**
 * Where Back should land from an in-shell destination: Home from anywhere else,
 * or null from Home meaning the activity should finish.
 */
fun tvHomeBackTarget(current: TvHomeDestination): TvHomeDestination? {
    if (current == TvHomeDestination.Home) return null
    return TvHomeDestination.Home
}
