# Ravensight for Android

Official Android SDK for [Ravensight](https://ravensight.io) player analytics.

Sessions, batched events, an in-memory queue, rate limit handling and a
server side kill switch, in one Kotlin library whose only dependency is
`androidx.lifecycle:lifecycle-process`.

* API base: `https://api.ravensight.io` (`/api/v1` is appended for you)
* Docs: https://ravensight.io/docs/
* Privacy: a random device id, no advertising identifier, a saved player opt-out (`Ravensight.setTrackingEnabled(context, false)`), a reset (`Ravensight.resetDeviceId()`), no tracking permission prompt needed. The id is per install, and the saved opt-out stays on this device, once you merge the Auto Backup rules (see "Device id, privacy and Auto Backup"). What your store label can say: https://ravensight.io/docs/#privacy-label
* minSdk 24 (Android 7.0), Kotlin only

## Verification status

Tested with `./gradlew :core:test :ravensight:testDebugUnitTest :ravensight:assembleRelease`
on JDK 17.0.19 and Gradle 8.10.2: 47 plain JVM tests drive the protocol core,
and 42 Robolectric 4.14.1 tests (Android API 34 runtime) drive the Android
layer with a fake transport, a fake clock and a manual worker queue.

It has **not** yet been verified on a device or an emulator, and the real
`ProcessLifecycleOwner` foreground and background signals are not covered by
the tests (the tests call the same entry points directly). Read the code
before you ship it in a release build, and please open an issue with anything
you hit.

## Install

Add JitPack to your repositories (`settings.gradle.kts`):

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Then add the dependency (`app/build.gradle.kts`):

```kotlin
dependencies {
    implementation("com.github.Reality-Software-Entertainment.ravensight-android:ravensight:v0.2.0")
}
```

The library declares the `INTERNET` permission for you via manifest merging.

## Quickstart

Initialize once, from your `Application`, with your publishable ingest key
(`gt_live_...`) from the Ravensight dashboard:

```kotlin
import com.realityse.ravensight.Ravensight

class MyGameApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Ravensight.initialize(this, "gt_live_your_key")
    }
}
```

That is the whole setup. Nothing touches the network until the app first
comes to the foreground. Then the SDK queues `game_started`, reads the
server kill switch, opens a session, and flushes queued events every 5
seconds, as soon as 50 are waiting, and whenever the app leaves the
foreground.

Then track from anywhere, on any thread:

```kotlin
Ravensight.track("level_completed", mapOf(
    "level" to 3,
    "deaths" to 1,
    "seconds" to 74.5,
))
```

Prefer full control? Pass a config and a listener:

```kotlin
Ravensight.initialize(
    this,
    RavensightConfig(
        ingestKey = "gt_live_your_key",
        gameVersion = "1.2.0",
    ),
    object : RavensightListener {
        override fun onSessionReady() { Log.i("Game", "analytics up") }
        override fun onStatusChanged(reason: String?) { Log.i("Game", "analytics: ${reason ?: "ok"}") }
        override fun onFeedbackSubmitted() { showThanksToast() }
    },
)
```

Player feedback, with one of the six categories in
`RavensightConfig.FEEDBACK_CATEGORIES` (`bug`, `suggestion`, `complaint`,
`praise`, `playtest`, `other`) and an optional rating from 1 to 5:

```kotlin
Ravensight.submitFeedback("More checkpoints in the cave, please", "suggestion", 4)
```

A category outside the list fails with `invalid_category`, and a rating
other than 0 (omitted) or 1 to 5 fails with `invalid_rating`, both on
`onFeedbackFailed` and without a request.

## API

Everything game code needs is on the `Ravensight` object. Every call is non
blocking, safe from any thread, and never throws.

| Call | What it does |
| --- | --- |
| `Ravensight.initialize(context, ingestKey)` | Starts the SDK. Also takes a `RavensightConfig` and a `RavensightListener`. |
| `Ravensight.track(name, data)` | Queues an event. `data` is optional. Returns false for a blank name or when tracking is off. |
| `Ravensight.flush()` | Asks for an immediate flush. After a refused key it makes exactly one more attempt. |
| `Ravensight.submitFeedback(message, category, rating)` | Posts player feedback. Outcome arrives on the listener. |
| `Ravensight.fetchSuggestions()` | EXPERIMENTAL. AI generated design suggestions for your game, via the listener. An empty list, with no request, while tracking is off. |
| `Ravensight.setTrackingEnabled(context, bool)` | The player's choice, saved across launches. Callable before `initialize`. |
| `Ravensight.isTrackingEnabled(context)` | The player's saved choice. |
| `Ravensight.resetDeviceId()` | New random id, saved; clears the queue and ends the session. Returns the new id. `resetDeviceId(context)` also works before `initialize`. |
| `Ravensight.setEnabled(bool)` | The game's own switch, like `RavensightConfig.enabled`. Not saved. Callable before `initialize`. |
| `Ravensight.statusReason` | Why the SDK is not delivering, or null. See "Status and failures". |
| `Ravensight.sessionToken` | The current session token, or null until a session opens. |
| `Ravensight.isReady` | True while a session token is held. |
| `Ravensight.isEnabled` | True while events are accepted. |
| `Ravensight.isPlaytest` | True in a Ravensight Playtest run. |
| `Ravensight.deviceId` | The anonymous id sent to the server. Null until the first Activity is created. |
| `Ravensight.setPlaytestContext(token, runId)` | Marks the run as a playtest run, if nothing else has. |
| `Ravensight.setListener(listener)` | Replaces the listener at any time. |

Event values can be strings, numbers, booleans, nulls, nested maps or lists;
anything else is sent as its `toString()`. They are serialized with the
platform's bundled `org.json` inside `track()`, on the calling thread, so
later changes to your map do not affect the event, and data that cannot be
serialized (a map that contains itself, say) makes `track()` return false
with a warning. A NaN or infinite number is sent as `null`.

One background `HandlerThread` owns the queue and the network, and listener
callbacks are delivered on the main thread.

## Configuration

| `RavensightConfig` field | Default | Meaning |
| --- | --- | --- |
| `ingestKey` | required | Your publishable `gt_live_...` key. A blank key logs an error and stops the SDK with `invalid_key`. |
| `apiUrl` | `https://api.ravensight.io` | Only change this if you self host. Whitespace and trailing slashes are trimmed, `/api/v1` is appended when omitted, and empty means the default. |
| `gameVersion` | empty | Empty uses your app's `versionName`. |
| `maxQueueSize` | `500` | Queue cap. Oldest events are dropped first. |
| `flushIntervalMs` | `5000` | Flush timer period. `0` flushes only on demand, at 50 queued and on background. |
| `requestTimeoutMs` | `15000` | Per request connect and read timeout. |
| `trackLifecycleEvents` | on | Sends the two automatic events, `game_started` and `game_exited`. |
| `trackPauseEvents` | off | Opt-in: also sends `game_paused` and `game_resumed`. |
| `sessionTimeoutMs` | `1800000` | How long the app may stay in the background before the session ends (30 minutes). |
| `flushOnBackground` | on | Flushes every time the app leaves the foreground. |
| `verboseLogging` | off | Logs SDK activity to Logcat under the `Ravensight` tag, and warns about reserved `rs_` keys. |
| `enabled` | on | False starts switched off: no request of any kind until `setEnabled(true)`. Never saved. |
| `playtestToken` | null | Ravensight Playtest run token. Normally read from the launch intent instead. |
| `playtestRunId` | null | Ravensight Playtest run id, paired with the token. |

## About your ingest key

The `gt_live_...` key is publishable. It is safe to ship inside a build: it
can open sessions, read the tracking kill switch and read this game's design
suggestions. It cannot read analytics, read feedback or touch your account.
Rotate it from the dashboard at any time.

## Automatic events and sessions

The SDK sends exactly two automatic events, and only while tracking is on:

* **`game_started`**, once per session, stamped at the moment the app came to
  the foreground and put first in the queue. A process started in the
  background (a push message, a broadcast, a scheduled job) sends nothing and
  opens no session until the app first comes to the foreground. A game that
  starts switched off and is enabled later in the run sends it then, once.
* **`game_exited`**, once per session, stamped with the time the app went to
  the background.

Android gives a game no reliable signal that it is about to be closed, so a
session ends by time in the background, through `ProcessLifecycleOwner`
(which already ignores a rotation or another configuration change):

* **Going to the background** flushes the queue and saves a small marker
  (the session token, its expiry and the background time) in the SDK's
  `SharedPreferences`. No event is sent.
* **Coming back within 30 minutes** (`sessionTimeoutMs`) continues the same
  session, and nothing is sent.
* **Coming back after 30 minutes or more** sends `game_exited`, stamped with
  the background time, once on the old session (if that request fails it is
  dropped, never moved to the new session), then starts a new session with a
  new `game_started`.
* **A cold launch after the player swiped the app away** finds the marker and
  sends that session's `game_exited` once, before anything else, if the
  player has not opted out and the session has not expired. The marker is
  deleted whatever the answer, and the request is not retried.

`game_paused` and `game_resumed` are not sent unless you set
`trackPauseEvents = true`.

## Levels and deaths

Property keys starting with `rs_` are reserved for Ravensight. `rs_kind` says
what an event means for the level funnel, whatever you name the event, and
must be one of `RavensightConfig.RS_KINDS`: `level_start`, `level_complete`,
`death` or `none`.

```kotlin
Ravensight.track("cave_entered", mapOf("level" to "cave", "rs_kind" to "level_start"))
Ravensight.track("player_fell", mapOf("level" to "cave", "rs_kind" to "death"))
Ravensight.track("cutscene_started", mapOf("rs_kind" to "none")) // not a level start
```

With `verboseLogging = true` the SDK warns once per event and key about an
`rs_kind` outside that list or an `rs_` key Ravensight does not read. It never
throws and still queues the event.

## Status and failures

`Ravensight.statusReason` is null while delivery is healthy, and otherwise one
of the reasons every Ravensight SDK uses. `RavensightListener.onStatusChanged`
is called whenever it changes.

| Reason | What happened | What the SDK does |
| --- | --- | --- |
| `disabled` | `enabled = false`, `setEnabled(false)` or the player's saved opt-out. | Nothing touches the network. |
| `invalid_key` | A `401`, or a `403` other than the ones below, from any endpoint. | Logs one warning, `Ravensight: ingest key refused (invalid_key), sending stopped for this run. Check your gt_live_ key.` Events keep queueing (the cap applies); automatic sending stops; `flush()` makes one more attempt. |
| `tracking_disabled` | The kill switch is on for your game (`GET /settings` or a `403 tracking_disabled`). | Clears the queue and sends nothing more this run. `onTrackingDisabled("server")`. |
| `offline` | The network was unreachable or timed out, or a batch answered `5xx`. | Keeps events and retries after 10 seconds, doubling to 5 minutes. |
| `rate_limited` | `429`, or `503` with `Retry-After`, from the settings check, the session or a batch. | Waits exactly `Retry-After`, then retries. |
| `session_failed` | Opening a session failed another way, or a playtest token was refused or arrived without a run id. | Retries with backoff; a playtest refusal stops the run. |
| `playtest_job_closed` | The playtest job this run belongs to has closed. | Clears the queue and sends nothing more this run. |

## How delivery works

* **Batching.** Up to 50 events per request, the server hard limit. A flush
  keeps sending batches until the queue drains. A request goes out on the 5
  second timer, as soon as 50 events are waiting, on `flush()` and when the
  app goes to the background, never one per `track()`.
* **Queue.** Events wait in memory, up to `maxQueueSize` (500 by default).
  Past that the oldest are dropped so the newest are kept.
* **Failures.** A failed request keeps its events queued for the next
  attempt. The queue lives in memory, so anything not delivered when the
  process dies is gone.
* **Session expiry.** A batch `401` clears the session, opens a new one and
  re sends the same batch, at most twice in a row before backing off.
* **Oversize batches.** A `400` halves the batch and retries down to a single
  event. An event still rejected on its own is dropped and counted, so it
  cannot block everything behind it.
* **Timestamps.** Each event carries the Unix time, in seconds, at which it
  was tracked.

## Opt-out and reset

```kotlin
// An "Analytics" switch in your settings screen: remembered across launches.
fun setAnalytics(context: Context, on: Boolean) {
    Ravensight.setTrackingEnabled(context, on)
}

// A "Forget me" button: a fresh random identity from now on.
fun forgetMe() {
    Ravensight.resetDeviceId()
}
```

`setTrackingEnabled` writes the choice synchronously, so it can run before
`initialize`; the SDK reads it at start, and a saved opt-out wins over
`enabled = true`. Turning it off clears anything queued and stops sending.

If your game asks for consent before any analytics, start switched off and
turn the SDK on once the player agrees. Nothing is sent before that:

```kotlin
Ravensight.initialize(this, RavensightConfig(ingestKey = "gt_live_your_key", enabled = false))
// later, once the player has agreed
Ravensight.setEnabled(true)
```

## Telling players

You do not need a permission prompt, but saying what the game sends is good
manners. A paragraph your game can show, in a settings screen or on its
store page:

> This game sends anonymous gameplay events (what happens in the game, never
> who you are) to Ravensight so we can improve it. You can turn this off in
> Settings at any time.

The toggle behind "turn this off" is the `setAnalytics` example above, and
the "Forget me" button is `resetDeviceId()`.

## Device id, privacy and Auto Backup

The SDK generates a random device id (`dev_` plus 32 hex characters, from 16
bytes of the platform's secure random source) when the first Activity is
created, and stores it in `SharedPreferences` under `ravensight/device_id`.
An existing id is never rewritten. No hardware identifier, advertising id,
IP based fingerprint or personal data is collected by the SDK itself. Only
the events you choose to send leave the device.

Android's Auto Backup copies `SharedPreferences` to the player's cloud
backup by default, which would carry the id to a new phone or across a
reinstall. The library ships two rule files that leave the SDK's
`ravensight.xml` out. Point your app's manifest at them:

```xml
<application
    android:fullBackupContent="@xml/ravensight_backup_rules"
    android:dataExtractionRules="@xml/ravensight_data_extraction_rules"
    ... >
```

If your app already has its own rule files, add these lines to them instead:

```xml
<!-- in your fullBackupContent file -->
<exclude domain="sharedpref" path="ravensight.xml" />

<!-- in your dataExtractionRules file, inside both <cloud-backup> and <device-transfer> -->
<exclude domain="sharedpref" path="ravensight.xml" />
```

A game that sends gameplay events and nothing else can declare Device ID and
Product Interaction as not linked to the player and not used for tracking,
and needs no tracking permission prompt. With `setTrackingEnabled` wired to a
setting, collection is optional for the player, and `resetDeviceId` is a
deletion path for this install's identity. The row by row answers for the
App Store and Google Play forms are at https://ravensight.io/docs/#privacy-label.

## Session hand-off to a game server

`Ravensight.sessionToken` is the current session token, or null until a
session opens. A game server can pass it to Ravensight as `joinToken` to add
server events to this player's session. The SDK never computes a session id
itself.

## Playtest

When Ravensight Playtest drives an Android build, the run is identified by a
token and a run id. The SDK reads them from, first found wins:

1. `RavensightConfig(playtestToken = ..., playtestRunId = ...)` or
   `Ravensight.setPlaytestContext(token, runId)`;
2. the string extras `ravensight_playtest_token` and
   `ravensight_playtest_run_id` on the first Activity's launch intent (call
   `initialize` from `Application.onCreate` so the SDK sees that Activity):

   ```bash
   adb shell am start -n com.example.game/.MainActivity \
     -e ravensight_playtest_token T -e ravensight_playtest_run_id R
   ```

3. the environment variables `RAVENSIGHT_PLAYTEST_TOKEN` and
   `RAVENSIGHT_PLAYTEST_RUN_ID`, as a last resort.

Any app on a device can launch your Activity with extras, and a playtest run
ignores the player's opt-out, so the SDK honours the intent extras only in a
debuggable build, or when your manifest opts in. A release build you want
playtested needs this inside `<application>`:

```xml
<meta-data
    android:name="com.realityse.ravensight.ALLOW_PLAYTEST_INTENT"
    android:value="true" />
```

Without it, a release build ignores the extras, logs one warning and runs as
an ordinary player build that respects the saved opt-out. Leave it out of the
build you ship to players.

In a playtest run the device id is `pt-<runId>`, held in memory and never
saved; every request carries the `X-Ravensight-Playtest` header; the
player's saved opt-out is ignored, and `setTrackingEnabled` and
`resetDeviceId` do nothing but log one line. A token without a run id never
runs as a real player: the SDK stops with `session_failed`. A closed job
stops the run with `playtest_job_closed`. A bad token only silences that run.

## Architecture

```
core/                                 pure Kotlin JVM, no Android imports
  RavensightCore.kt                   the whole protocol as a clock injected
                                      state machine: batching, backoff, 401,
                                      403, 429, 503, 400 splitting, the queue
ravensight/                           the Android library (com.realityse.ravensight)
  Ravensight.kt                       public API, HandlerThread worker,
                                      process lifecycle, sessions, playtest
  RavensightConfig.kt                 config and the listener interface
  RavensightHttp.kt                   HttpURLConnection transport and org.json glue
  res/xml/                            Auto Backup rules
```

`core` is a separate JVM module with no Android or transport imports, so a
stray Android type in the protocol layer is a compile error rather than a
surprise.

## Running the tests

```bash
./gradlew :core:test                    # protocol core, plain JVM
./gradlew :ravensight:testDebugUnitTest # Android layer, Robolectric
./gradlew :ravensight:assembleRelease   # the library itself
```

You need JDK 17 and an Android SDK with platform 35 (`ANDROID_HOME`, or
`sdk.dir` in `local.properties`). The first Robolectric run downloads the
Android runtime it simulates. No device or emulator is needed. CI runs all
three on pushes to `main` and on pull requests.

## License

MIT. Copyright 2026 Reality Software Entertainment.
