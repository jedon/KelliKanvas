# Ambient integration boundary

Task 15 owns ambient policies, Android sensor/timer adapters, `AndroidAmbientLifecycle`, and the
optional `KelliKanvasDreamService`. It does not own a slideshow renderer, settings screen, or
application container.

The remaining bindings follow the implementation plan:

- Task 17 writes ambient choices through the existing `AppPreferencesRepository`.
- Task 14 supplies the native renderer that a Dream slideshow host must attach.
- Task 18 implements the slideshow route/session and host adapter. Its window-scoped owner adapts playback to
  `AmbientPlaybackHost`, calls `AmbientLifecycle.start()` when slideshow playback becomes active,
  and calls `stop()` before that owner is destroyed.
- Task 20's `AppContainer` maps persisted preferences to `AmbientConfigRepository`, constructs
  `AndroidAmbientLifecycle` with the slideshow window/host, and installs a real
  `DreamSlideshowHostProvider` through the application graph.

The placeholder `MainActivity` must not start ambient listeners: it has no slideshow playback
state or renderer window to control. Likewise, declaring a `KelliKanvasApplication` that always
returns `DreamSlideshowHost.Unavailable` would not be a production integration. Until Tasks 14,
18, and 20 provide the real host, DreamService intentionally uses its tested unavailable fallback and
finishes cleanly. Task 15's "reuse slideshow/renderer" acceptance point therefore remains an
explicit integration blocker for Task 20 after Tasks 14 and 18 merge; the current branch proves
that an application-provided concrete host is selected and receives Dream lifecycle callbacks.
