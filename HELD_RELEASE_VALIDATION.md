# Held combined release, October 17, 2026

Public-build support and typed events have been combined locally. This record
is verification evidence, not a publication or deployment record.

Validated: 59 core and 90 Robolectric tests; release AAR builds.

The merge preserves public-key consent and inert-without-key behavior,
stable event IDs, typed event helpers and opt-in performance sampling.
JavaScript now defers performance listeners until consent; Android health
summaries carry the same deduplication IDs as other events.

Physical-device and live-service validation is not implied by these checks.
Publishing, tags, marketplace submission and deployments remain held pending
owner approval. Older README test counts describe their earlier branch runs;
this file records the combined candidate.

Remote config wave: core and Android Robolectric test tasks passed. The Android module has 93 passing cases, including config caching/ETags, strict fallback, automatic labels, malformed data and consent changes during a response. Version 0.3.1 remains held; physical-device and live API checks remain distinct release gates.

Config identifier compatibility audit: 5 focused Robolectric config tests passed with Gradle offline mode.
Config property keys reject constructor/prototype, including nested JSON properties.
Experiment keys and variant labels may use those words as string values, matching
the server contract. Regression coverage distinguishes those cases. Publishing remains held.

Player Reports: full `:core:test :ravensight:testDebugUnitTest :ravensight:assembleRelease` passes offline on JDK 17. Report tests use native Robolectric graphics and fake API/storage transports; 10 cases cover immutable capture, retry identity, expired sessions, identity/consent changes, malformed images/metadata, bounded polling and Bitmap encoding. Physical device and live storage upload remain unverified. Publishing remains held.
