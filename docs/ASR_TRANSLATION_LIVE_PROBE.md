# ASR / Translation Live Probe

This is an opt-in host-side smoke test for the real ASR and translation providers. It reuses the
production `SyncTextConnectionTester`, provider adapters, request budgets, prompt assets, and
response validators. It is not a prompt-quality benchmark and it does not exercise Android media
lifecycle behavior.

## Configure

Copy `.env.example` to an ignored `.env.local`, or export the variables directly in the shell. The
probe reads `.env.local`/`.env` from the repository root for local convenience; environment variables
take precedence over file values.
Do not put credentials in `BuildConfig`, the Android manifest, source files, or committed files.

At least one provider key is required when the probe is enabled:

- `VORTEX_LIVE_ASR_API_KEY` runs the short bundled English ASR fixture;
- `VORTEX_LIVE_TRANSLATION_API_KEY`, `VORTEX_LIVE_TRANSLATION_ENDPOINT`, and
  `VORTEX_LIVE_TRANSLATION_MODEL` run the two-cue translation probe.

`VORTEX_LIVE_TEST` must equal `1`. The probe is skipped otherwise, so normal unit tests never spend
money or require network access.

## Run on Windows

```powershell
$env:VORTEX_LIVE_TEST="1"
$env:VORTEX_LIVE_ASR_API_KEY="..."
$env:VORTEX_LIVE_TRANSLATION_API_KEY="..."
$env:VORTEX_LIVE_TRANSLATION_ENDPOINT="https://..."
$env:VORTEX_LIVE_TRANSLATION_MODEL="..."

.\gradlew.bat :app:testDebugUnitTest --tests "*LiveProviderSmokeTest"
```

The test only asserts provider contract and orchestration invariants: usable timestamped ASR
output, strict translation cue validation, policy asset integrity, and successful completion
through the production gateway. It does not score translation style.

Android device testing remains necessary for media URI access, audio extraction, Room recovery,
network consent, background execution, and process restart behavior.
