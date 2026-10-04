# Themes and Kelli branding

Choose **Appearance → App theme** to change the entire interface. Selection applies immediately and is saved across app restarts.

- **Kelli** is the default: black backgrounds, purple panels, lavender details, and gold accents. Small bat silhouettes and photography viewfinder details appear in the empty gallery artwork.
- **Gallery** uses sage and charcoal.
- **Midnight** uses deep blue and cool silver.
- **Paper** uses warm ivory and bronze with a light interface.
- **Device colors** uses Android's dynamic palette on Android 12 and newer. Phones follow the system light/dark setting; TVs use a dark palette. Older Android versions fall back to Midnight or Paper.

The original launcher monogram appears in the TV navigation rail, phone home, permission welcome screen, and the wide settings sidebar. The shared logo resource is an unchanged copy of the existing launcher PNG. Startup also displays that icon on a black background.

The theme system uses the existing [Material 3 Compose library](https://developer.android.com/develop/ui/compose/designsystems/material3), with the same palette supplied to TV Material. Composition-scoped colors allow nested screens to inherit the user's selection. Themes affect app chrome, source setup, and slideshow/screensaver controls. User photographs retain their original colors and have no decorative overlays.

The selection lives in DataStore under `app_theme_v1`, encoded as a stable versioned string. Missing or unknown values fall back to Kelli without resetting other preferences. Theme swatches and selected markers can be navigated with a TV remote or touch. The app's status and navigation bar icon colors follow the selected light/dark appearance.

Validation covers persistence across repository instances, unknown-value fallback, immediate updates through nested theme wrappers, remote selection, phone selection, and text/button contrast. Preset text and button labels meet a minimum 4.5:1 contrast ratio. Native Android renderings are saved under `app/build/reports/themes/` for visual review.

NAS credentials are never embedded in APKs. Add them through the phone pairing form under Collection → Connect NAS. Public previews use the debug signing certificate and do not replace a production-signed installation.
