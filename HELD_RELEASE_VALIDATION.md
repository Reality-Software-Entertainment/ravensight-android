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
