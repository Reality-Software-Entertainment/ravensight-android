# Ravensight for Android

Official Android SDK for [Ravensight](https://ravensight.io) player analytics.

Sessions, batched events, an in-memory queue, rate limit handling and a
server side kill switch, in one Kotlin library whose only dependency is
`androidx.lifecycle:lifecycle-process`.

* API base: `https://api.ravensight.io` (`/api/v1` is appended for you)
* Docs: https://ravensight.io/docs/
* Privacy: a random device id, no advertising identifier, a saved player opt-out (`Ravensight.setTrackingEnabled(context, false)`), an opt-in mode that saves and sends nothing before the player's yes (`consent = RavensightConsent.OPT_IN`), a reset (`Ravensight.resetDeviceId()`), no tracking permission prompt needed. The id is per install, and the saved opt-out stays on this device, once you merge the Auto Backup rules (see "Device id, privacy and Auto Backup"). What your store label can say: https://ravensight.io/docs/#privacy-label
* minSdk 24 (Android 7.0), Kotlin only
* Public builds: since 0.3.0, `gt_pub_` keys, inert without a key, opt-in consent, a distribution label, `rs_event_id` on every event and an 8 KB event cap (see "Public builds")

## Verification status

Tested with `./gradlew :core:test :ravensight:testDebugUnitTest :ravensight:assembleRelease`
on JDK 17.0.19 and Gradle 8.10.2: 59 plain JVM tests drive the protocol core,
and 68 Robolectric 4.14.1 tests (Android API 34 runtime) drive the Android
layer with a fake transport, a fake clock and a manual worker queue. 25 of
them cover the 0.3.0 public build rules (see "Public builds").

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
    implementation("com.github.Reality-Software-Entertainment.ravensight-android:ravensight:v0.3.0")
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
| `ingestKey` | required | Your publishable `gt_live_...` key, or a `gt_pub_...` public key. A blank key makes the SDK inert for the run with `no_key` (see "Public builds"). |
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
| `optional` | off | Silences the one warning a blank `ingestKey` logs. |
| `consent` | `OPT_OUT` | `RavensightConsent.OPT_IN` saves nothing, creates no device id and sends nothing until `setTrackingEnabled(context, true)`. See "Public builds". |
| `distribution` | null | A short label for where this build was distributed, such as `"play-store"`, sent when a session opens. At most 40 characters, no control characters; anything else is ignored with one warning. |

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
| `no_key` | `initialize` was given a blank ingest key. | Inert for the run: nothing saved, no worker thread, no request, no lifecycle callback. One warning unless `optional = true`. |
| `disabled` | `enabled = false`, `setEnabled(false)`, the player's saved opt-out, or, with `consent = OPT_IN`, no yes yet. | Nothing touches the network. |
| `invalid_key` | A `401`, or a `403` other than the ones below. | Logs one warning, `Ravensight: ingest key refused (invalid_key), sending stopped for this run. Check your gt_live_ or gt_pub_ key.` On the settings check or a session open the run ends: the queue is cleared and nothing is retried, not even by `flush()`. On a batch or another endpoint, automatic sending stops and `flush()` makes one more attempt. |
| `tracking_disabled` | The kill switch is on for your game (`GET /settings` or a `403 tracking_disabled`). | Clears the queue and sends nothing more this run. `onTrackingDisabled("server")`. |
| `offline` | The network was unreachable or timed out, or a batch answered `5xx`. | Keeps events and retries after 10 seconds, doubling to 5 minutes, each wait varied by plus or minus 20 percent. |
| `rate_limited` | `429`, or `503` with `Retry-After`, from the settings check, the session or a batch. | Waits exactly `Retry-After`, then retries. |
| `session_failed` | Opening a session failed another way, or a playtest token was refused or arrived without a run id. | Retries with backoff; a playtest refusal stops the run. |
| `playtest_job_closed` | The playtest job this run belongs to has closed. | Clears the queue and sends nothing more this run. |

## Public builds

For a game whose build or source anyone can download (an open source
project, a demo on a public page, an APK shared with testers):

* **Use a public key.** Create a `gt_pub_...` key in the dashboard. It can
  open sessions and send events under the limits its policy sets (event
  names, origins, versions, sizes), and nothing else.
* **Inject it at build time, never commit it.** For example from a Gradle
  property (`ravensightKey` in `~/.gradle/gradle.properties`, or
  `-PravensightKey=...` in CI) into `BuildConfig`, so a fork or a clone
  builds without one:

  ```kotlin
  // app/build.gradle.kts
  android {
      buildFeatures { buildConfig = true }
      defaultConfig {
          buildConfigField("String", "RAVENSIGHT_KEY", "\"${providers.gradleProperty("ravensightKey").getOrElse("")}\"")
      }
  }
  ```

* **Without a key the SDK is inert.** `initialize` with a blank key saves
  nothing in `SharedPreferences`, starts no worker thread, registers no
  lifecycle callback and makes no request. `track` returns false,
  `isEnabled` is false and `statusReason` is `no_key`. It logs one warning;
  set `optional = true` to silence it. `resetDeviceId()` forgets any saved
  id and returns `""`.
* **Opt-in mode.** With `consent = RavensightConsent.OPT_IN` nothing
  happens before the player's yes: no device id is created, no settings
  check is made and no session opens. Events tracked before the yes are
  dropped, not held for later (`track` returns false).
  `setTrackingEnabled(context, true)` is the yes: it is saved synchronously
  in the SDK's `SharedPreferences` (`tracking_opt_in`, beside the opt-out),
  creates the device id and starts the SDK; events tracked right after it
  are queued while the settings check runs. `setTrackingEnabled(context,
  false)` withdraws it and keeps the device id; `resetDeviceId()` is the
  separate "forget me", and before a yes it forgets the saved id without
  making a new one. Called before `initialize`, `setTrackingEnabled` does
  not know the mode yet and saves the choice both ways. The default,
  `OPT_OUT`, behaves as 0.2.0 did.
* **Distribution.** `distribution = "itch"` (or `"play-store"`, or your
  own label, at most 40 characters) is sent when a session opens, so the
  dashboard can tell builds apart.

```kotlin
Ravensight.initialize(
    this,
    RavensightConfig(
        ingestKey = BuildConfig.RAVENSIGHT_KEY,
        optional = true,                      // a build without a key is fine
        consent = RavensightConsent.OPT_IN,   // ask first
        distribution = "public-demo",
    ),
)

// Later, from your consent screen:
Ravensight.setTrackingEnabled(context, playerSaidYes)
```

What 0.3.0 adds to every build, public or not:

* **`rs_event_id`.** Every event's data carries one, so the server can
  drop a resend it already has. It is the first 16 characters of the
  device id (a random 16 hex character value for this launch when the
  event is tracked before the first Activity, when the id is not known
  yet), the start time in hex, 8 hex characters from `SecureRandom` minted
  once per launch, and a counter, for example
  `dev_3f9c0a1b2c3d-6720f1a0-9b1e44c2-12`. It always matches
  `^[A-Za-z0-9_-]{1,64}$`, is written into the event when it is tracked,
  and a resend after a failure carries the same ids. The queue lives in
  memory, so there is no saved queue for it to survive. A value of your
  own under that key is replaced.
* **An 8 KB event cap.** An event whose data is larger than 8,192 bytes as
  JSON is dropped when tracked, never sent: `track` returns false, one
  warning is logged for the run and `onFlushFailed("event_too_large")` is
  called for each.
* **A refused key ends the run** (see `invalid_key` under "Status and
  failures").
* **Retry jitter.** A retry wait without a `Retry-After` varies by plus or
  minus 20 percent; a `Retry-After` is still obeyed exactly.

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

If your game asks for consent before any analytics, use the opt-in mode:
nothing is saved or sent before the player's yes, and the yes is
remembered across launches (see "Public builds"):

```kotlin
Ravensight.initialize(this, RavensightConfig(ingestKey = "gt_live_your_key", consent = RavensightConsent.OPT_IN))
// later, once the player has agreed
Ravensight.setTrackingEnabled(context, true)
```

Starting with `enabled = false` and calling `setEnabled(true)` still works,
but it is not saved, so the game has to remember the answer itself.

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
An existing id is never rewritten. With `consent = OPT_IN` it is created at
the player's first yes, not before, and without a key it is never created. No hardware identifier, advertising id,
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
build you ship to players. The gate reads the `android:debuggable` flag, so a
build shipped with `android:debuggable="true"` is treated as a debug build
and honours the extras.

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
  RavensightEventIds.kt               rs_event_id and the shared limits
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
