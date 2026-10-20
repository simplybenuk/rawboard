# Verification evidence

Checked 20 October 2026. This file distinguishes completed checks from device-dependent acceptance.

## Version 0.1.6, llama.cpp replacement

The user requested removing Nano and trying llama.cpp after the failed keyboard test. The current implementation removes Nano code, ML Kit and coroutines dependencies, and the SDK diagnostics disclosure because that SDK is no longer bundled. The retired NANO selection migrates to LLAMA with model setup required, preserving the cloud credential without selecting a paid provider.

- App-owned native CPU inference uses the pinned, vendored official llama.cpp revision recorded in `third_party/llama.cpp/RAWPLUS_UPSTREAM.txt`. Native code is optimized in debug APKs too, with bounded compile concurrency and 16 KB linker alignment.
- Setup accepts only the pinned official Qwen2.5-1.5B-Instruct Q4_K_M model, 1,117,320,736 bytes, SHA-256 `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e`. Download/import require a tap; size, GGUF version and streaming checksum verification precede installation. Weights are outside the APK and excluded from backups.
- Independent source review of the root's editor/IME integration found no blocking lifecycle or routing findings. Native review covered handles, cancellation, full-prompt token counting, mandatory completion, safe UTF conversion and fixed errors. Settings review covered partial-file cleanup, cancelled resource opening, credential isolation and preservation of installed files on failure. These are source checks, not Android runtime checks.
- `:app:externalNativeBuildDebug` succeeds with NDK 28.2.13676358 and CMake 3.22.1. `:app:assembleDebug`, `:app:testDebugUnitTest` and `:app:lintDebug` then pass with the existing JDK 17 / Gradle 8.13 / AGP 8.13.2 / Kotlin 2.3.21 toolchain. All 69 JVM tests pass, with no failures, errors or skips. Lint reports 0 errors and 5 warnings: pinned target/compile/Gradle/test JSON versions and intentionally absent ChromeOS x86 ABI support. No baseline or disabled checks were added. The initial unit compilation caught three test names containing JVM-illegal dots; those names were corrected before the successful full run.
- The actual model completed five synthetic host inferences through the exact app JNI source and full unchanged prompt. False-start and already-readable cases passed narrow checks; café and an emoji survived the JNI round trip. One Raw+ case deleted the meaningful phrase "I deleted it". Polished preserved meaning but only added a full stop. Exact evidence is in `artifacts/llama-smoke/review.md` and `results.json`. Host wall times of 17.157–19.833 seconds include loading and prompt processing and are not Pixel estimates. These five cases do not establish general quality or injection resistance.
- Following that observed loss, local Raw+ now rejects nontrivial word changes through a bounded, ordered token check. Its regression tests reject the exact missing clause, added/reordered words, lost uncertainty/negation, and changed symbols/numbers. It permits known fillers and exact adjacent repetition cleanup, punctuation, case and paragraphs. It can reject useful repairs, and it cannot guarantee unchanged meaning through punctuation or emphasis. Polished is not filtered. The original, prompts and cloud editing path remain unchanged.
- Host JNI checks safely rejected malformed files and invalid settings, cancelled before/during generation, and enforced an elapsed deadline without a native crash or partial output. Actual token-output-limit refusal was not exercised; its source checks and synthetic protocol tests are not a runtime proof.
- Final package inspection confirms version 0.1.6/code 7, minimum API 31, target API 35, arm64-v8a only, exactly RECORD_AUDIO and INTERNET permissions, and no ML Kit/Nano classes or AICore binding/startup providers. It includes only `librawplus_llama.so` and `libc++_shared.so`, not weights. Both ELF libraries have 0x4000 LOAD alignment, and `zipalign -c -P 16 -v 4` passes. `apksigner` verifies one v2 signer with the same certificate as previous distributed versions. Backup stays disabled; Providers and History are not exported.

APK: [rawplus-keyboard-0.1.6-debug.apk](https://drive.google.com/file/d/1PDqLse9VK27bhYrpy9j6em_rNfg5K3tQ/view?usp=drivesdk), also retained at `artifacts/rawplus-keyboard-0.1.6-debug.apk`, 9,648,971 bytes. Upload succeeded and Drive metadata readback confirmed the file name, APK MIME type and exact size. Sharing permissions were not changed. SHA-256:

```text
44287e2107d24bf0e8007b6a3d9ae1c87de291c94ee25e6b3b327032c44a606a
```

Independent compiled re-review verified the observed missing-clause failure is rejected and all three previously good Raw+ outputs remain accepted. Two additional counterexamples, invented repetition and deletion of uppercase ER/ERM as fillers, were fixed with ordered-subsequence checks and case-aware filler recognition before the final successful build. Polished remains unchanged. Known decimal-punctuation, emoji-joiner and other semantic limits remain; the check is not a guarantee of unchanged meaning.

Physical Pixel checks are required for model setup, offline operation, IME responsiveness, cancellation under CPU load, memory/heat/power behavior and real insertion into the three target apps. No physical device is attached. See [the local provider checklist](device-testing.md#provider-selection-and-local-llamacpp).

## User-reported Nano device result

On 20 October 2026, the user reported that the Google Nano test succeeded in setup but failed at keyboard input. This is user-reported device evidence, not an agent-observed run. The host app, exact error code, Android/AICore versions and preservation of Raw after this particular failure were not supplied.

The setup-versus-keyboard difference is consistent with Google's documented top-foreground-app restriction. BACKGROUND_USE_BLOCKED is the leading explanation, not a captured error. Nano has failed the keyboard compatibility acceptance check on the user's test and must not be presented as a working local editing option for their current workflow. A successful setup sample does not override this result. OpenAI remains the existing alternative selected manually in Providers; an app-owned local runtime would require a separate implementation and device test.

Source inspection of version 0.1.5 confirmed its Nano path mapped BACKGROUND_USE_BLOCKED to a fixed explanation, retains the original draft on editing failure and has no automatic cloud fallback. These checks do not establish what appeared on the user's screen. No app code, APK or runtime tests changed in response to this report.

## Experimental 0.1.5 approved for Pixel testing

The user requested provider selection and key-free on-device Gemini Nano, and explicitly accepted Google's SDK metrics and experimental keyboard limitations for a Pixel test on 20 October 2026. The built APK was uploaded to Google Drive and its name, MIME type and 13,059,183-byte size were read back successfully. ML Kit sends performance/utilization metrics even though prompts and results stay on device, changing the original no-telemetry scope. Google also documents foreground-only inference. The subsequent user test above reported setup success and keyboard failure.

- Build succeeds with JDK 17, verified Gradle 8.13, AGP 8.13.2, Kotlin 2.3.21 and compile/target API 35. The dependency metadata required the compiler upgrade. ML Kit Prompt API is pinned to 1.0.0-beta4 and coroutines Android to 1.11.0 for Google's documented download compatibility issue.
- All 51 Android JVM tests pass. The previous 39 regressions remain, plus seven provider-selection/credential-scope tests, four Nano instruction/budget/response tests and one HTTP rejection test for Nano. No live model or paid API request ran.
- Lint succeeds with 0 errors and 11 warnings, covering pinned-version update suggestions, KTX preferences and a compat drawable API suggestion. A single class-level AppCompatCustomView suppression documents that the custom caret uses native platform/IME themes despite the SDK's transitive AppCompat dependency. No baseline or global check disabling was added. This is not a clean-warning lint report.
- Independent source reviews found no remaining blocking defects in Nano future cancellation, deadline/generation guards, complete-output validation, full-request token counting, explicit download/sample actions, HTTP exclusion, provider migration or credential screenshot protection. SDK diagnostics disclosure is visible regardless of selected provider.
- Providers is an internal, non-exported Activity. Selection of Nano retains the prior encrypted cloud key without decrypting it. Cloud reuse requires matching provider and normalized endpoint. Runtime preference/Keystore migration still needs a Pixel test.
- Nano uses the same packaged skill/notes and original transcript. It checks status and system-instruction support, never automatically downloads, never falls back to OpenAI, and preserves Raw after failure. A successful foreground settings sample explicitly does not establish keyboard compatibility.
- The compiled APK is version 0.1.5, code 6, with the same verified signing certificate as released versions. Permissions now also include the SDK's AICore service binding, ACCESS_NETWORK_STATE and its app-scoped receiver permission. The manifest contains ML Kit's startup provider and transport components, so SDK initialization is not gated by selecting Nano. No extra dangerous runtime permission was added.

APK: [rawplus-keyboard-0.1.5-debug.apk](https://drive.google.com/file/d/1L017aBomSqIATE3T5KpGsHjO5IdEzL3n/view?usp=drivesdk), also retained at `artifacts/rawplus-keyboard-0.1.5-debug.apk`, with sibling checksum. SHA-256:

```text
c6b665e468c4fe7ed1dfdc5275efe2c848cb84fad038ab3f01dcf5a8675b5b70
```

Required device checks are listed in [current provider acceptance checks](device-testing.md#provider-selection-and-local-llamacpp). Distribution is complete; the user reports setup success and keyboard failure, while the exact failure and remaining acceptance checks are unverified. The checks below describe the previously distributed version.

## Released 0.1.4 checks

- `:app:assembleDebug` succeeds with JDK 17, Gradle 8.11.1, Android Gradle plugin 8.9.2, Kotlin 2.1.20 and Android SDK 35.
- `:app:testDebugUnitTest` passes all 39 tests. They cover original-source regeneration, Raw defaults and word preservation, stale responses after mode changes/field changes/insertions, failure recovery, draft size limits, password/private field policy and endpoint restrictions. Cursor tests cover beginning/middle/end placement, ordered segments, paragraph boundaries, unchanged source during provisional projection, failed-size preservation and split-surrogate safety. Direct-provider tests exercise fixed OpenAI routing, credential redaction, original-only mode prompts, packaged minimal-editing notes, `store:false`, strict response validation, Raw staying local, backend compatibility and safe credit/authentication/rate errors. Panel geometry tests cover parent height limits, expansion never shrinking a short panel, residual navigation overlap and landscape side insets without double padding. Eight journal tests cover exact source/result JSON pairs, coalescing, count/age/UTF-8 size retention and invalid documents.
- `:app:lintDebug` reports `No issues found.` No baseline or disabled checks were used.
- `node --test` in `server/` passes all 14 HTTP tests. Cases include authorization, original transcript and mode controls, exact vendored skill and shared versioned editing notes, `store:false`, malformed/oversized input, provider refusals/truncation/oversized output, body/upstream timeouts and rate/concurrency limits. Provider fetch is injected and mocked. The network sandbox initially blocked the loopback test server; a scoped native retry passed. No paid API calls ran.
- `apksigner verify --verbose` verifies the APK's v2 signature and one signer. It is a debug build for personal installation.
- `aapt dump badging` confirms exactly `RECORD_AUDIO` and `INTERNET`. Package `uk.co.simplyben.rawplus`, version `0.1.4`, version code 5, minimum API 31, target API 35. Cursor, history and export add no permission. Compiled manifest inspection confirms HistoryActivity is not exported and application backups remain disabled.
- Exact Raw+ skill retrieved through authenticated GitHub and vendored byte-for-byte. Git blob SHA `28e610ec9b5e0d6dd85ba5283da21406cd5ec6ab`.
- Independent source reviews covered recognition lifecycle, mode/source integrity, host writes, cancellation, network configuration and response validation. Concrete findings were fixed before the final build. A further review of version 0.1.1 found no concrete remaining defects in encrypted personal-key setup, provider/endpoint credential boundaries, fixed direct OpenAI routing, response parsing, consent or cancellation.
- Version 0.1.4 uses the same verified signing certificate as earlier versions and a higher version code, supporting an in-place APK update.
- Direct editing uses a user-entered phone key. No real API key has been supplied to the agent, compiled into the APK or used in verification. The app asset copy of the Raw+ skill matches the server copy byte-for-byte.
- Light and dark resource XML parses successfully. Calculated normal text contrast is at least 4.99:1 in the light palette and 5.16:1 in the dark palette. Activity and IME themes use these resources without an app-specific appearance override.
- Independent source review of 0.1.2 found no concrete defects in screenshot controls, resource selection, keyboard theme initialization or window appearance. The keyboard permits screenshots; setup protects focused credential fields and any unsaved credential text. The user subsequently confirmed the update worked and supplied a dark-mode keyboard screenshot. This is user evidence, not an agent runtime check.
- Independent source review of 0.1.3 covered explicit AI triggers, wakefulness cleanup, same-view expansion, navigation/caption insets, short-screen controls and review-following interactions. Findings about caption-bar clearance, compact confirmation space, final-text following and accessibility scrolling were resolved. No unresolved concrete findings remained in that review scope. The final build and lint ran after these fixes.
- Source audit confirms AI editing is called only by explicit mode selection or Retry. Recognition callbacks and Keep words do not trigger it. Each recording selects Raw. The visible panel keeps the screen on during active dictation/finalization and clears it on terminal/hide/destroy paths. These source checks do not establish device power behavior or actual billing counts.
- Independent 0.1.4 source reviews covered source/caret offset integrity, provisional versus confirmed insertion, cursor blink lifecycle, child touch dispatch, local-only history hooks, immutable event snapshots, generation checks around queued writes, encrypted/atomic storage, retention and explicit export. Findings about paragraph separators, deferred source-review scrolling, dropped busy history actions and partial export error reporting were resolved. No blocking findings remained in those review scopes. Final build/tests/lint ran after source fixes.
- Both app/server SKILL.md copies remain identical, as do the new editing-notes.txt copies. Prompt version raw-plus-2026-10-20.2 is packaged in the direct path. Tests establish instructions, examples and original-only source routing; no live model-output comparison ran.
- History source review confirms confirmed dictation, accepted AI source/result pairs and successful insertions are the only saved events. Audio, partials, unaccepted interrupted words, credentials, surrounding text and host app identifiers are excluded by the call paths. Password/private fields remain blocked. History is encrypted under a separate Keystore alias and written in a worker. Android Keystore, atomic-file and document-provider behavior still require actual device tests.

APK: `artifacts/rawplus-keyboard-0.1.4-debug.apk`, with a sibling SHA-256 checksum file. Current checksum:

```text
afdcfa8575758d7569d1c18a4a9b693a2f7a34ba6a02861d25eba1a682a759b6
```

## Local native smoke

A bounded software-only Android 35 emulator attempt did not complete first boot within ten minutes. Hardware acceleration is unavailable. A sandbox launch first failed with QEMU watchdog/ptrace errors. A scoped native launch progressed through Android system startup and connected to adb, but first-boot compilation and package/storage service initialization did not finish in time. APK installation did not complete. This is not a native app startup or UI pass, and no field/picker/landscape behavior was verified on that emulator. Evidence is retained in `artifacts/native-smoke/emulator-boot.log`.

The vanilla system image has no Google speech provider or Gboard, so it could not establish those paths even after boot. A debug-only `TestEditorActivity` provides ordinary, password, numeric and private test fields for a future run. It has no transcript injection or fake speech service and is not present in release builds.

## Still pending

No physical Pixel is attached. T3's device tools also report that agent device access is turned off for this environment. The user installed earlier versions, supplied a version 0.1.2 screenshot, and described version 0.1.3's UI as neat. Their current feedback asks for cursor-based paragraph insertion, less Raw+ rewriting and local test logs. Version 0.1.4 addresses those requests in source; no new device result is claimed yet. Any real API key is entered privately on the phone. The following have not been established through agent runtime checks:

- Pixel 10 on-device recognizer availability, en-GB installation/download and actual offline operation.
- Native setup and keyboard UI startup, microphone permissions, secure token round trip, layout and switching on a fully booted device.
- Version 0.1.2 system dark/light appearance, changing system mode while using the app, allowed keyboard/setup screenshots and screenshot blocking during credential entry.
- Version 0.1.3 navigation/globe clearance in gesture and three-button modes, compact/expanded layouts, touch/fling/accessibility following, actual screen-timeout behavior, and API request counts after recognition or Dictate more. Expansion resizes the same panel, but target-app editor restarts during resizing still need testing because the existing privacy policy clears restarted editors.
- Version 0.1.4 cursor tap accuracy/blinking, middle-of-draft partial following, original-preview tap transition, accessibility selection, long insertion review and cancel/interruption interaction. Local encrypted history round trips, logging controls, deletion versus queued writes, retention and document-provider exports also need Pixel checks. These were not established by the pure journal tests or source review.
- Natural pause handling, long speech, disfluency preservation and real microphone interruption recovery.
- Actual model output quality and the speaker's recognition of their own voice in Raw+.
- Insertion, cursor behavior and immediate Gboard switching in LinkedIn, WhatsApp and Gmail.

Use [the device checklist](device-testing.md). The user's real LinkedIn-on-Pixel definition of done remains pending. Unit tests, an APK and source review do not establish it.
