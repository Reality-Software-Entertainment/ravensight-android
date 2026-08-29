# Ravensight for Android

Official Android SDK for [Ravensight](https://ravensight.io) player analytics.

Sessions, batched events, an offline queue, rate limit handling and a server
side kill switch, in one Kotlin library with zero third party dependencies.

* API base: `https://api.ravensight.io/api/v1`
* Docs: https://ravensight.io/docs/
* minSdk 24 (Android 7.0), Kotlin only

## Verification status

The protocol logic in the `core` module is written and
reviewed against the live API contract, and it is deliberately free of any
Android dependency so it is covered by plain JVM unit tests. It has **not**
yet been verified inside a device or emulator build.

Treat it as pending on device verification: read the code before you ship it
in a release build, and please open an issue with anything you hit. The
JavaScript, Godot and Unity SDKs speak the same protocol.

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
    implementation("com.github.Reality-Software-Entertainment.ravensight-android:ravensight:v0.1.0")
}
```

The library declares the `INTERNET` permission for you via manifest merging.

## Quickstart

Initialize once, as early as you like, with your publishable ingest key
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

That is the whole setup. The SDK reads the server kill switch, opens a
session, tracks `game_started`, and flushes queued events every 5 seconds and
whenever the app leaves the foreground.

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
        flushIntervalMs = 5_000,
        trackLifecycleEvents = true,
    ),
    object : RavensightListener {
        override fun onSessionReady() { Log.i("Game", "analytics up") }
        override fun onFeedbackSubmitted() { showThanksToast() }
    },
)
```

## API

Everything game code needs is on the `Ravensight` object:

| Call | What it does |
| --- | --- |
| `Ravensight.initialize(context, ingestKey)` | Starts the SDK. Also takes a `RavensightConfig` and a `RavensightListener`. |
| `Ravensight.track(name, data)` | Queues an event. `data` is optional. Returns false when tracking is off. |
| `Ravensight.flush()` | Asks for an immediate flush of anything queued. |
| `Ravensight.submitFeedback(message, category, rating)` | Posts player feedback. Outcome arrives on the listener. |
| `Ravensight.fetchSuggestions()` | EXPERIMENTAL. AI generated design suggestions for your game, via the listener. |
| `Ravensight.setEnabled(bool)` | Local opt in and opt out for a privacy toggle. |
| `Ravensight.isReady` | True once a session exists. |
| `Ravensight.setListener(listener)` | Replaces the listener at any time. |

Event values can be strings, numbers, booleans, nulls, nested maps or lists.
They are serialized with the platform's bundled `org.json`, so nothing extra
ships in your APK.

Every call is non blocking and thread safe: one background `HandlerThread`
owns the queue and the network, and listener callbacks are delivered on the
main thread.

## Configuration

| `RavensightConfig` field | Default | Meaning |
| --- | --- | --- |
| `ingestKey` | required | Your publishable `gt_live_...` key. |
| `apiUrl` | `https://api.ravensight.io/api/v1` | Only change this if you self host. `/api/v1` is appended when omitted. |
| `gameVersion` | empty | Empty uses your app's `versionName`. |
| `maxQueueSize` | `500` | Offline queue cap. Oldest events are dropped first. |
| `flushIntervalMs` | `5000` | Flush timer period. `0` flushes only on demand and on lifecycle stops. |
| `requestTimeoutMs` | `15000` | Per request connect and read timeout. |
| `trackLifecycleEvents` | on | Sends `game_started`, `game_paused`, `game_resumed`. |
| `flushOnBackground` | on | Flushes every time the app leaves the foreground. |
| `verboseLogging` | off | Logs SDK activity to Logcat under the `Ravensight` tag. |
| `enabled` | on | Start opted out by setting this false. |

## About your ingest key

The `gt_live_...` key is publishable. It is safe to ship inside a build: it
can only open sessions and read the tracking kill switch. It cannot read
analytics, read feedback or touch your account. Rotate it from the dashboard
at any time.

## How delivery works

* **Batching.** Up to 50 events per request, the server hard limit. A flush
  keeps sending batches until the queue drains.
* **Offline queue.** Events accumulate up to `maxQueueSize` (500 by default).
  Past that the oldest are dropped so the newest are always kept.
* **Never dropped on failure.** Events leave the queue only after the server
  answers `202`.
* **Session expiry.** A `401` clears the session, opens a new one and re sends
  the same batch.
* **Rate limits.** A `429` honors `Retry-After` in seconds. With no such
  header the SDK backs off exponentially from 10 seconds to a 5 minute
  ceiling. The queue is held, never discarded.
* **Oversize batches.** A `400` halves the batch and retries down to a single
  event. An event still rejected on its own is dropped so it cannot block
  everything behind it.
* **Kill switch.** `GET /settings` is read once at boot. If the server has
  tracking off for your game, the queue is cleared and nothing is sent.

Flushes happen on the 5 second timer, on every `Ravensight.flush()`, and when
the last started Activity stops (the app going to the background). There is no
reliable process exit signal on Android, so no `game_exited` event is sent and
the background flush is the final delivery point. Anything not delivered by
process death is gone, since the queue lives in memory only.

## Architecture

```
core/                                 pure Kotlin JVM, no Android imports
  RavensightCore.kt                   the whole protocol as a clock injected
                                      state machine: batching, backoff, 401,
                                      429, 400 splitting, the queue
ravensight/                           the Android library (com.realityse.ravensight)
  Ravensight.kt                       public API, HandlerThread worker,
                                      Activity lifecycle hooks
  RavensightConfig.kt                 config and the listener interface
  RavensightHttp.kt                   HttpURLConnection transport and org.json glue
```

`core` is a separate JVM module with no Android or transport imports, so a
stray Android type in the protocol layer is a compile error rather than a
surprise. That is what makes the logic testable without an emulator: hand
`RavensightCore` a stepped fake clock and you can drive every retry path in a
plain JUnit test. CI runs those tests on every push:

```bash
./gradlew :core:test
```

## Device id and privacy

The SDK generates a random device id on first run and stores it in
`SharedPreferences` under `ravensight/device_id`. No hardware identifier,
advertising id, IP based fingerprint or personal data is collected by the SDK
itself. Only the events you choose to send leave the device.

`Ravensight.setEnabled(false)` stops all sending and discards anything still
queued, so an opt out does not leave player data sitting in memory.

## License

MIT. Copyright 2026 Reality Software Entertainment.
