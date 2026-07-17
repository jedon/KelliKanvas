package com.jedon.kellikanvas.model

enum class LayoutMode {
    FULL_PHOTO,
    FILL_SCREEN,
    BLURRED_BORDER,
    SOLID_BACKGROUND,
    STRETCH,
}

enum class PortraitPairingMode {
    OFF,
    AUTO,
    ALWAYS,
}

enum class TransitionType {
    CUT,
    CROSSFADE,
    FADE_THROUGH_BLACK,
    SLIDE_LEFT,
    SLIDE_RIGHT,
    PAN_ZOOM,
    RANDOM,
}

enum class PlaybackOrder {
    SHUFFLE,
    NAME,
    CAPTURE_DATE_ASC,
    CAPTURE_DATE_DESC,
    MODIFIED_DATE_ASC,
    MODIFIED_DATE_DESC,
}
