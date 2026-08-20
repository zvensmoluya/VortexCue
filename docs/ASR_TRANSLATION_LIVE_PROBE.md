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

## Run a local media excerpt

`LiveMediaPipelineTest` exercises a representative local excerpt through real ASR, timeline merging,
translation batch planning, the production prompts, and strict response validation. It requires both
provider configurations plus `ffmpeg` on `PATH` (or `VORTEX_LIVE_FFMPEG` pointing to the executable).

Set `VORTEX_LIVE_MEDIA_TEST=1` and `VORTEX_LIVE_MEDIA_FILE` to one local audio file. The separate
media switch prevents a saved path from making normal provider smoke runs upload a long excerpt.
The optional start and duration values default to the first 225 seconds; the host test converts only
that range to a temporary 16 kHz mono WAV and never uploads the original file. Keep the duration at
or below 300 seconds so it remains one ASR window.

```powershell
$env:VORTEX_LIVE_MEDIA_TEST="1"
$env:VORTEX_LIVE_MEDIA_FILE="D:\media\sample.wav"
$env:VORTEX_LIVE_MEDIA_START_SECONDS="0"
$env:VORTEX_LIVE_MEDIA_DURATION_SECONDS="225"
$env:VORTEX_LIVE_MEDIA_SOURCE_LANGUAGE="ja"

.\gradlew.bat :app:testDebugUnitTest --tests "*LiveMediaPipelineTest"
```

The local transcript/translation comparison is written below `app/build/reports/live-provider/`,
which is excluded from Git. This host-side conversion does not replace Android decoder coverage.
