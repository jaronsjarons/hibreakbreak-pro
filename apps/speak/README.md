# Read aloud (local.claude.speak)

Adds **Read aloud** to Android's text-selection menu (PROCESS_TEXT) and share menu (SEND text/plain) on the HiBreak (Oct 24 2026). Speaks the text with the phone's default TTS engine — SherpaTTS (F-Droid `org.woheller69.ttsengine` 3.4) with the Piper voice **en_US-lessac-medium**. Small bottom panel with progress + Stop; touches outside pass through to the app underneath. Long text is split into sentence chunks (≤1000 chars).

Build: `./build.sh` → `out/speak.apk` (own local key in `keys/`). No Gradle.

Verified: PROCESS_TEXT test spoke via SherpaTTS (AudioTrack, CONTENT_TYPE_SPEECH), panel showed "Reading aloud… 1/1" and closed when done; 0 crashes.

## Public build

`./build.sh` is unchanged (local key in `keys/`, output in `out/`). For a public release:
`KS=/path/to/speak-release.p12 KS_PASS=… ./build.sh --release` → `out-release/speak.apk`, signed with alias `release`.
The APK asks for no permissions and logs nothing; the text it reads is passed only to the phone's TTS engine.
`.gitignore` keeps `keys/` and build output out of the public repo.
