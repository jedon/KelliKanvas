package com.jedon.kellikanvas.model

enum class PortraitFit {
    FULL_HEIGHT,
    FILL_SCREEN,
}

enum class BlurStrength {
    LOW,
    MEDIUM,
    HIGH,
}

enum class NewPhotosPolicy {
    NEXT_CYCLE,
}

enum class BrightnessMode {
    FOLLOW_TV,
    AMBIENT_SENSOR,
    SCHEDULE,
}

data class AppPreferences(
    val landscapeLayout: LayoutMode = LayoutMode.FILL_SCREEN,
    val singlePortraitLayout: LayoutMode = LayoutMode.BLURRED_BORDER,
    val singlePortraitFit: PortraitFit = PortraitFit.FULL_HEIGHT,
    val portraitPairingMode: PortraitPairingMode = PortraitPairingMode.AUTO,
    val portraitLookAhead: Int = 4,
    val pairGutterDp: Int = 24,
    val blurStrength: BlurStrength = BlurStrength.MEDIUM,
    val blurDimAmount: Double = 0.35,
    val slideDurationMillis: Long = 15_000,
    val transitionType: TransitionType = TransitionType.CROSSFADE,
    val transitionDurationMillis: Long = 700,
    val playbackOrder: PlaybackOrder = PlaybackOrder.SHUFFLE,
    val loopEnabled: Boolean = true,
    val resumeEnabled: Boolean = true,
    val newPhotosPolicy: NewPhotosPolicy = NewPhotosPolicy.NEXT_CYCLE,
    val metadataOverlayEnabled: Boolean = false,
    val clockOverlayEnabled: Boolean = false,
    val captureDateOverlayEnabled: Boolean = false,
    val filenameOverlayEnabled: Boolean = false,
    val presenceEnabled: Boolean = false,
    val brightnessMode: BrightnessMode = BrightnessMode.FOLLOW_TV,
) {
    init {
        require(slideDurationMillis >= 1_000) {
            "Slide duration must be at least 1,000 milliseconds"
        }
        require(transitionDurationMillis >= 0) {
            "Transition duration must be nonnegative"
        }
        require(transitionDurationMillis < slideDurationMillis) {
            "Transition duration must be shorter than slide duration"
        }
        require(portraitLookAhead in 1..4) {
            "Portrait look-ahead must be between 1 and 4"
        }
        require(pairGutterDp >= 0) {
            "Pair gutter must be nonnegative"
        }
        require(blurDimAmount in 0.0..1.0) {
            "Blur dim amount must be between 0 and 1"
        }
    }
}
