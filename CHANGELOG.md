# Changelog

## [0.2.0] - 2026-10-10

Brings the Android SDK to the same contract as every other Ravensight SDK.
Pre-1.0, so this minor version contains breaking changes.

### Breaking

* `RavensightCore.track` returns false for a blank name instead of throwing
  `IllegalArgumentException`. `Ravensight.track("")` used to crash the game
  from the worker thread; it now returns false and logs a warning.
* `game_paused` and `game_resumed` are no longer sent by default. Set
  `trackPauseEvents = true` to keep them.
* `game_started`, the settings check and the session wait for the first
  foreground, so a process started in the background (push, broadcast,
  scheduled job) sends nothing. `Ravensight.deviceId` is null until the first
  Activity is created.
* The player's opt-out is `Ravensight.setTrackingEnabled(context, enabled)`
  and is saved. `setEnabled` is the game's own switch and is not saved.
* `RavensightConfig.DEFAULT_API_URL` is the bare host
  `https://api.ravensight.io`; `/api/v1` is still appended for you.
* A `401` or a `403` stops automatic retries (see `statusReason`) instead of
  retrying forever, and a settings `401` is no longer "assuming tracking
  enabled".
* A blank ingest key no longer throws from `initialize`; the SDK starts
  stopped with `invalid_key`.
* `SessionOutcome.Refused` and `BatchOutcome.Refused` are new subclasses, so
  an exhaustive `when` over the core outcomes needs a branch for them.
* The library now depends on `androidx.lifecycle:lifecycle-process`.

### Added

* Mobile session model: after 30 minutes in the background
  (`sessionTimeoutMs`) the session ends with `game_exited` stamped at the
  background time, and the next foreground starts a new session. A marker in
  the `ravensight` prefs reports the exit of an app that was killed in the
  background on the next cold launch.
* `ProcessLifecycleOwner` drives foreground and background, so a rotation or
  another configuration change sends nothing.
* `Ravensight.setTrackingEnabled(context, enabled)` and
  `isTrackingEnabled(context)`, callable before `initialize`.
* `Ravensight.resetDeviceId()` and `resetDeviceId(context)`.
* `Ravensight.setEnabled` before `initialize` is remembered.
* `Ravensight.statusReason` and `RavensightListener.onStatusChanged` with the
  shared reasons `disabled`, `invalid_key`, `tracking_disabled`, `offline`,
  `rate_limited`, `session_failed`, `playtest_job_closed`
  (`RavensightReason` in the core).
* Playtest: `playtestToken` and `playtestRunId` config, `setPlaytestContext`,
  the launch intent extras `ravensight_playtest_token` and
  `ravensight_playtest_run_id`, and the environment variables. A playtest
  run uses `pt-<runId>`, sends `X-Ravensight-Playtest` on every request and
  saves nothing; `Ravensight.isPlaytest`.
* `Ravensight.sessionToken` for a game server's `joinToken`.
* `RavensightConfig.FEEDBACK_CATEGORIES` and local `invalid_category` and
  `invalid_rating` checks with no request.
* `RavensightConfig.RS_KINDS` and a `verboseLogging` warning for an unknown
  `rs_kind` or `rs_` key.
* `res/xml/ravensight_backup_rules.xml` and
  `ravensight_data_extraction_rules.xml` keep the SDK's prefs out of Auto
  Backup so the device id is per install.
* `Ravensight.VERSION` and a `User-Agent: ravensight-android/0.2.0` header.
* A Robolectric test suite for the Android layer
  (`./gradlew :ravensight:testDebugUnitTest`), run in CI.

### Fixed

* An opt-out or `setEnabled(false)` right after start no longer lets the
  settings check, the session and `game_started` go out first: each protocol
  step is its own worker message and re-checks the switch.
* A session `403 tracking_disabled` is the kill switch, cleared queue and
  `onTrackingDisabled("server")`, instead of a retry loop.
* `Retry-After` is honoured on `503` as well as `429`.
* A feedback `401` renews the session.
* A NaN or infinite number in event data is sent as `null` instead of failing
  every batch behind it.
* No exception inside the SDK can reach the worker looper.
* Suggestions and feedback make no request while tracking is off.

## [0.1.0]

First public beta.

* Sessions against `POST /api/v1/session` with the publishable ingest key.
* Batched event delivery to `POST /api/v1/track/batch`, 50 events per batch.
* In memory offline queue capped at 500 events, oldest dropped first.
* Server kill switch read from `GET /api/v1/settings` at boot.
* Re authentication on `401` with the batch left queued.
* `Retry-After` support on `429`, exponential backoff from 10s to 5 minutes.
* Oversize batch splitting on `400`, halving down to a single event.
* Player feedback via `POST /api/v1/feedback`.
* Experimental suggestions fetch from `GET /api/v1/agent/suggestions`.
* 5 second flush timer plus a flush whenever the app leaves the foreground.
* Pure JVM protocol core with a JUnit suite, no emulator needed.
