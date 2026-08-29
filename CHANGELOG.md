# Changelog

## 0.1.0

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
