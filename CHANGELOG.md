# Changelog

## [0.3.0] - 2026-10-16

For games whose builds or source anyone can download. A game that passes
only a key behaves as on 0.2.0, except for the changes listed first.

### Changed for 0.2.0 games

* A `401` or `403` on the settings check or a session open (other than
  `403 tracking_disabled` and a refused playtest token) now ends the run:
  the queue is cleared and nothing is retried, an explicit `flush()`
  included. It used to keep the queue and let `flush()` try again. A batch
  `401` still opens a fresh session first, a batch `403` of this kind still
  allows one explicit retry, and `statusReason` is still `invalid_key`.
* A blank ingest key makes the SDK inert with `statusReason` `no_key`
  (it was `invalid_key`), logs a warning instead of an error, and no longer
  starts the worker thread or registers lifecycle callbacks.
* Every event's data gains an `rs_event_id` (a value of your own under that
  key is replaced), the cold launch `game_exited` included.
* Retry waits without a `Retry-After` carry plus or minus 20 percent
  jitter. `Retry-After` is still obeyed exactly.
* An event whose data is over 8,192 bytes as JSON is dropped when tracked
  instead of being sent and refused by the server: `track` returns false,
  one warning is logged for the run and `onFlushFailed("event_too_large")`
  is called.
* `Ravensight.setTrackingEnabled` before `initialize` also writes
  `tracking_opt_in` on a yes (and removes it on a no), since the consent
  mode is not known yet.
* `RavensightCoreConfig` has a new trailing `random` parameter (the jitter
  source), and the core gains `countDropped`.

### Added

* **Inert without a key**, with `RavensightConfig.optional` to silence the
  one warning.
* **`consent = RavensightConsent.OPT_IN`**: no device id, settings check or
  session before `setTrackingEnabled(context, true)`, which saves the yes
  as `tracking_opt_in` in the SDK's `SharedPreferences`. Events tracked
  before the yes are dropped; events right after it are queued while the
  settings check runs. Saying no again keeps the device id;
  `resetDeviceId()` before a yes forgets it and returns `""`.
  `isTrackingEnabled(context)` follows the mode. `OPT_OUT` stays the
  default.
* **`distribution`**: a label of at most 40 characters sent on
  `POST /session`. An invalid one is ignored with one warning.
* **`rs_event_id`** (`RavensightEventIds` in the core): device id prefix,
  start time, a launch nonce from `SecureRandom` and a counter, matching
  `^[A-Za-z0-9_-]{1,64}$`. A resend carries the same ids.
* A "Public builds" section in the README, and 25 tests (8 core, 17
  Robolectric).

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
  saves nothing; `Ravensight.isPlaytest`. The intent extras count only in a
  debuggable build or with the manifest meta-data
  `com.realityse.ravensight.ALLOW_PLAYTEST_INTENT` set to true.
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
* After 30 minutes in the background, `game_exited` is one request on the old
  session, sent after that session's leftovers; if it fails it is dropped,
  never sent on the new session.
* `game_started` goes first in the queue, ahead of events tracked before the
  first foreground.
* A settings `429`, or `503` with `Retry-After`, waits and checks again
  (`rate_limited`).
* `maxQueueSize` and `maxBatchSize` below 1 are treated as 1.
* Event data is serialized inside `track()` on the caller's thread; data that
  cannot be serialized makes `track()` return false instead of blocking the
  queue.

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
