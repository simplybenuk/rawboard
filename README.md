# Raw+ Android keyboard

A personal voice-first Android keyboard built with Kotlin, `InputMethodService`, native Views and Android's on-device `SpeechRecognizer`. Every recording starts in local Raw mode. AI editing runs only when you choose Raw+ or Polished. For testing, it keeps encrypted local text history with export and deletion controls. There are no accounts, cloud history or automatic posting. Local AI editing uses app-owned llama.cpp CPU inference; no analytics or telemetry SDK is included.

## Install on a Pixel

Use `artifacts/rawplus-keyboard-0.1.6-debug.apk` after building, or `app/build/outputs/apk/debug/app-debug.apk`. It is a debug-signed personal prototype, not a Play Store release. Version 0.1.6 removes Gemini Nano and its ML Kit SDK, and adds an experimental llama.cpp provider for Pixel testing. Install it over the existing version to retain app settings.

With USB debugging enabled:

```sh
adb install -r artifacts/rawplus-keyboard-0.1.6-debug.apk
```

Alternatively, copy the APK to the Pixel, open it and allow installation from that file source. Open **Raw+ Keyboard** from the launcher. Allow microphone access, enable Raw+ in Android's keyboard settings, and select it. Keep Gboard enabled. Check the on-device English status and request the en-GB model download if needed.

Tap the setup app's practice field to try it. Dictate, review the local Raw result, then tap **Insert**. Open **Providers** from setup or the keyboard cog to choose OpenAI, llama.cpp on this device or your own backend for Raw+ and Polished. No provider fallback or edit runs automatically.

The keyboard and setup screen follow Android's system light/dark setting. There is no separate theme switch. Screenshots of the keyboard preview and ordinary setup screens are allowed for feedback. Setup screenshots are blocked while a credential field is focused or contains unsaved text. Save or clear the credential, then tap elsewhere to allow them again. Saved keys are never displayed. A host app can still apply its own screenshot restrictions.

Inside LinkedIn, WhatsApp or Gmail:

1. Focus the comment or message field and select Raw+ as the keyboard.
2. Tap **Dictate**. Recording never starts automatically. The screen stays awake during dictation, finalization and requested local AI editing, then returns to the phone's normal timeout.
3. Speak, then tap **Stop**, or let recognition finish. Tap a point in the Raw transcript to place the blinking cursor, then **Dictate here** to add a paragraph there. With the cursor at the end, **Dictate more** appends. Each recording returns to Raw and makes no AI request.
4. Review the preview. Use **Expand preview** for a near-full-height view, then **Collapse preview** to return. New speech follows the latest words until you scroll back. Tap **Latest** to resume following. The cog menu includes **Show original transcript**.
5. Optionally choose Raw+ or Polished for an AI edit using the selected provider. OpenAI uses API credit; llama.cpp runs locally. Each generation uses the original transcript. Recognition, finishing, keeping unfinished words and adding speech never request an AI edit automatically.
6. Tap **Insert**. This only calls `InputConnection.commitText`. Use the host app's own send/post button when ready.
7. Use Android's keyboard-switch control or the cog menu's **Return to Gboard** for normal corrections. **Choose keyboard** in the menu opens Android's keyboard picker. Setup and Clear draft are also in the menu. On short landscape screens the mode choices move into that menu too.

The panel respects Android's available height and reserves any navigation/cutout overlap inside its padding. Expansion resizes the same preview without opening another app or Android's extracted host editor. Android can still restart an editor when its layout changes; those restarts retain the existing draft-clearing privacy policy and need checking in each target app.

Cursor placement uses the exact original transcript. When viewing an AI result, the first tap reveals the original, then a second tap places the cursor. This avoids guessing how an edited word maps to your source. New segments and provisional speech appear at that point, and Latest follows the insertion rather than the original suffix. The preview never takes focus as a second keyboard editor. It places one insertion point and does not offer selection replacement or typed corrections. **Dictate at end** in the cog moves the cursor back to the end.

## Enable Raw+ editing

For a phone-only test over Wi-Fi or mobile data:

1. Open **Raw+ Keyboard** from the launcher, then **Open Providers**, or use **Providers** in the keyboard cog.
2. Select **OpenAI**. Paste your API key into the masked field on the phone. Do not put it in this chat.
3. Check the consent box for sending your original dictated transcript directly to OpenAI.
4. Tap **Save and use OpenAI**. Saving makes no API request and does not check the key's balance.
5. Dictate a short thought in Raw, tap **Raw+** to request editing, review the result, then tap **Insert**.

The user-entered key is encrypted on the device using Android Keystore and excluded from backup. It is never bundled in the APK, displayed after saving, or included in the request body. It authenticates HTTPS requests only to `https://api.openai.com/v1/responses`. **Forget saved cloud credential** removes it while preserving provider selection and local history. Leave a field blank to reuse the key only for its original cloud provider and endpoint. Selecting llama.cpp retains that cloud credential without decrypting it. Selecting OpenAI again can reuse it. There is one saved cloud credential slot; saving another cloud provider replaces it.

## Experimental local editing with llama.cpp

1. Open **Providers**, then select **llama.cpp**.
2. Choose **Download model** for the official Qwen2.5-1.5B-Instruct Q4_K_M file, about 1.12 GB. Prefer Wi-Fi and keep Providers open until it finishes. Leaving cancels setup; interrupted downloads restart rather than resume. Alternatively, download [the exact pinned file](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/blob/91cad51170dc346986eccefdc2dd33a9da36ead9/qwen2.5-1.5b-instruct-q4_k_m.gguf) in a browser, then **Import model file** with Android's picker. No broad storage permission is needed.
3. The app checks the complete file size, GGUF version and SHA-256 before installation. Other models or quantizations are rejected for this first test. The model occupies 1,117,320,736 bytes in private storage outside backups. Keep at least 1.2 GB free for installation, in addition to any browser-downloaded copy. An existing verified model remains until a replacement is fully verified.
4. Optionally run the synthetic sample, then **Use llama.cpp**. Saving a provider does not run an edit. Return to a LinkedIn field, dictate in Raw and explicitly tap Raw+.

[llama.cpp's Android integration](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md) runs our own copy of the model inside the app. It does not use AICore or Google's foreground-only inference service. After model setup, local editing sends neither text nor audio to a server and needs no API key. Downloading contacts Hugging Face and its download infrastructure for model bytes only. Import may contact whichever document provider you choose. Nothing downloads or falls back to cloud editing automatically.

This build targets **arm64-v8a** devices such as the Pixel 10, with CPU inference only. The model is loaded for each requested edit and released afterward. It uses the same full Raw+ skill, editing notes, original transcript and selected mode as OpenAI. Complete-prompt token counting happens in the native engine. The app uses an 8,192-token context and up to 2,048 output tokens, rejects inputs without enough output room, and rejects incomplete generations rather than inserting fragments. Local editing has a three-minute limit. Tap **Cancel** to return to Raw with the original retained. Android can still kill the process under memory pressure, which loses an unsaved in-memory draft.

A real host smoke test found a meaningful phrase missing from one Raw+ result. Local Raw+ therefore compares the source and output words in order, allowing punctuation, case, known hesitation words and bounded adjacent repetition cleanup. Other word changes are rejected with the original retained. This can also reject useful transcription repairs. The check does not prove meaning is unchanged, especially with punctuation or emphatic repetition. Polished has no word-preservation filter and needs careful review.

Speed, battery use, heat, instruction following and long-draft quality need actual Pixel testing. A host inference check cannot establish keyboard responsiveness or performance. Review every result before inserting. **Delete local model** removes the installed model without removing your saved cloud key or text history.

Nano and its ML Kit diagnostics SDK are absent from 0.1.6. The prior setup-success/keyboard-failure result is retained in [verification evidence](docs/verification.md#user-reported-nano-device-result). An existing Nano selection migrates to llama.cpp, with model setup required; it never silently selects a cloud provider. The old cloud credential remains saved for an explicit return to OpenAI.

This personal prototype uses separately billed OpenAI API credit. The default model is `gpt-4.1-mini`; see [current model pricing](https://developers.openai.com/api/docs/models/gpt-4.1-mini). That model is currently scheduled for API removal on 14 January 2027, so update the prototype before then. See [OpenAI deprecations](https://developers.openai.com/api/docs/deprecations). Authentication, credit/quota and network failures retain the original transcript and allow local Raw insertion.

The backend option remains available for stronger separation of the API key from the phone. Follow [the Node server setup](server/README.md), select **My own backend**, and enter its HTTPS URL and separate access token. For USB development, localhost HTTP with `adb reverse` remains supported. No backend hosting or account system is provisioned by this repository.

ChatGPT account sign-in is not implemented. OpenAI now documents [ChatGPT plan usage for eligible apps](https://developers.openai.com/siwc/quickstart); it is a separate future integration rather than a way to enter an API key.

## What the modes do

- **Raw** runs locally. The cursor preview shows the exact original words and punctuation. Inserting Raw adds a terminal full stop when missing in each paragraph. Dictated segments are separated by blank lines. It does not guess additional sentence boundaries or correct words.
- **Raw+** applies the [exact supplied skill](server/raw-plus/SKILL.md), followed by [the app's narrower editing notes](app/src/main/assets/raw-plus/editing-notes.txt). Remove hesitation. Preserve personality. Change as little as possible, retain meaningful uncertainty, expressions and vocabulary, and leave already-readable wording alone. Unslop concepts limit AI additions rather than treating your own words as a blacklist.
- **Polished** uses the same original transcript with more freedom to improve sentence structure. It must preserve meaning and certainty.

The recognizer's output is the original transcript available to this app. Android may already omit a hesitation or misrecognize a word. Raw+ cannot recover speech that the recognizer did not return. Model instructions constrain editing, but human review is still necessary.

## Local test history

Version 0.1.4 enables local logging for this personal test, as requested. Open **Local test history** from the cog or setup screen to compare entries, turn logging off, export or delete everything. Each confirmed dictation, accepted AI result and successful insertion records its original source, output, mode, time, session and app prompt version. Consecutive Raw updates coalesce into the latest snapshot; an AI result retains the source it actually used. Inserted text is the text passed to the host field, not proof of publication, sending or later corrections.

Entries are encrypted with an independent Android Keystore key in app-private, non-backed-up storage. The journal keeps at most 100 entries for 30 days within 4 MiB, trimming older entries when next read or written. It records no audio, unconfirmed hypotheses, credentials, host text or host app identifiers. Writes run off the keyboard's UI thread. Queued records from before Clear or a logging change are skipped, so deleted history cannot reappear from an old queue.

Logging off stops future records and leaves existing entries until deletion or expiry. **Clear draft** clears the working draft; **Clear all history** deletes the local journal. Neither deletes exported files. Export requires a separate tap and destination choice in Android's file picker, where Google Drive can be selected. The exported JSON contains plain text and is sent nowhere automatically. Review it before sharing it for feedback. Forgetting your API key does not delete this separate text history.

## Recovery and privacy

Confirmed speech remains in the working draft during recognition and editing failures. Partial hypotheses replace each other at the insertion point rather than appending duplicates. After interruption, unfinished text is labelled and needs **Keep words** before it can be edited, inserted or logged. **Cancel** discards unfinished speech but retains confirmed text and the insertion position. There is no automatic retry or recording restart.

Changing fields, hiding or switching keyboards, and successful insertion clear the working draft. Android editor restarts also clear it, even in the same field. Cancel a request or leave the keyboard to discard an edit; bytes already sent to a backend or provider cannot be recalled. Confirmed text remains in encrypted local history while logging is enabled. There is no automatic draft restoration, audio storage, logcat transcript output, cloud backup or clipboard capture. Process death loses the working draft but leaves previously saved history.

The app never reads host text around the cursor. Password, private/incognito and non-text fields disable dictation and insertion. Sensitive-field detection relies on correct host editor flags. Do not dictate secrets into an ordinary text field. The Providers credential field accepts your own API key or backend token; it does not capture passwords from other apps.

Audio uses only Android's explicit on-device recognizer, with no fallback to an online speech service. Model downloads can need a network connection. When OpenAI or backend editing is selected, the original dictated transcript goes directly to OpenAI or through the selected backend, with the Raw+ rules and selected mode. The llama.cpp provider processes that transcript on device. `store:false` disables stored Responses state; it is not a zero-retention promise. See [OpenAI's API data controls](https://developers.openai.com/api/docs/guides/your-data). Feedback screenshots are user initiated; credential entry remains protected as described above. Host apps may store text after insertion under their own policies.

## Build and verify

Requires JDK 17, Gradle 8.13, Kotlin 2.3.21, Android Gradle plugin 8.13.2, Android SDK platform 35, build-tools 35.0.0, NDK 28.2.13676358, CMake 3.22.1 and Node.js 22 or newer. The wrapper pins Gradle's official checksum. Set `ANDROID_HOME` or create an ignored `local.properties` containing `sdk.dir=/path/to/Android/Sdk`. Minimum device version remains Android 12, API 31; the target and compile SDK are 35.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
cd server
node --test
```

The build versions are pinned. A minimal unmodified official llama.cpp source subset is vendored under `third_party/llama.cpp`; its revision, archive checksum and license are recorded in `RAWPLUS_UPSTREAM.txt`. The APK contains the app's JNI bridge, CPU backend and C++ runtime, with no ML Kit, AICore binding or model weights. No additional runtime SDK dependencies remain beyond the Kotlin standard library. The backend still has no runtime dependencies.

See [API findings and implementation decisions](docs/android-investigation.md), [device acceptance checks](docs/device-testing.md), and [verification evidence](docs/verification.md). A successful build or emulator test does not establish Pixel speech quality or acceptance in the three target apps.
