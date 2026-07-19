package com.jedon.kellikanvas.renderer.surface

/**
 * Target panel size for still-photo decode.
 *
 * Video already goes through a hardware decoder into a Surface — that path never
 * materializes a full-frame ARGB Bitmap in the Java heap. Still photos must not
 * pretend they can follow the Compose `Image` + ARGB_8888 shortcut at 4K.
 */
data class DisplaySize(
    val widthPx: Int,
    val heightPx: Int,
) {
    init {
        require(widthPx > 0 && heightPx > 0) { "DisplaySize must be positive" }
    }

    val longEdgePx: Int get() = maxOf(widthPx, heightPx)
}

object DisplayPhotoTarget {
    const val UHD_WIDTH = 3840
    const val UHD_HEIGHT = 2160

    /** Soft cap for phones/tablets — TV photo frames need the real panel edge. */
    const val NON_TV_MAX_EDGE_PX = 2048

    fun preferMode(
        available: List<DisplaySize>,
        current: DisplaySize,
    ): DisplaySize {
        val uhd =
            available.firstOrNull {
                it.widthPx == UHD_WIDTH && it.heightPx == UHD_HEIGHT
            }
        if (uhd != null) return uhd
        return available.maxByOrNull { it.widthPx.toLong() * it.heightPx } ?: current
    }

    fun decodeLongEdgePx(
        selected: DisplaySize,
        television: Boolean,
    ): Int {
        val edge = selected.longEdgePx
        return if (television) edge else minOf(edge, NON_TV_MAX_EDGE_PX)
    }
}
