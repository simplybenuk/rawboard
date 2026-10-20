# Raw+ Android keyboard

A personal voice-first Android keyboard built with Kotlin, `InputMethodService`, native Views and Android's on-device `SpeechRecognizer`. Every recording starts in local Raw mode. AI editing runs only when you choose Raw+ or Polished. For testing, it keeps encrypted local text history with export and deletion controls. AI editing can use your ChatGPT plan, your OpenAI API key or an optional backend. There is no Raw+ account service, cloud transcript history, automatic posting, analytics or telemetry SDK. Local model inference is no longer included.

## Install on a Pixel

Use `artifacts/rawplus-keyboard-0.1.7-debug.apk` after building, or `app/build/outputs/apk/debug/app-debug.apk`. It is a debug-signed personal prototype, not a Play Store release. Version 0.1.7, version code 8, removes local model inference and implements official ChatGPT plan sign-in. The sign-in and editing path still needs a real Pixel test. Install it over the existing version to retain app settings.

With USB debugging enabled:

```sh
adb install -r artifacts/rawplus-keyboard-0.1.7-debug.apk
```

Alternatively, copy the APK to the Pixel, open it and allow installation from that file source. Open **Raw+ Keyboard** from the launcher. Allow microphone access, enable Raw+ in Android's keyboard settings, and select it. Keep Gboard enabled. Check the on-device English status and request the en-GB model download if needed.

Tap the setup app's practice field to try it. Dictate, review the local Raw result, then tap **Insert**. Open **Providers** from setup or the keyboard cog to choose your ChatGPT plan, an OpenAI API key or your own backend for Raw+ and Polished. No provider fallback or edit runs automatically.

The keyboard and setup screen follow Android's system light/dark setting. There is no separate theme switch. Screenshots of the keyboard preview and ordinary setup screens are allowed for feedback. Providers screenshots are blocked while an API credential field is focused or contains unsaved text. Save or clear the credential, then tap elsewhere to allow them again. Saved keys are never displayed. A host app can still apply its own screenshot restrictions.

Inside LinkedIn, WhatsApp or Gmail:

1. Focus the comment or message field and select Raw+ as the keyboard.
2. Tap **Dictate**. Recording never starts automatically. The screen stays awake during dictation, finalization and requested AI editing, then returns to the phone's normal timeout.
3. Speak, then tap **Stop**, or let recognition finish. Tap a point in the Raw transcript to place the blinking cursor, then **Dictate here** to add a paragraph there. With the cursor at the end, **Dictate more** appends. Each recording returns to Raw and makes no AI request.
4. Review the preview. Use **Expand preview** for a near-full-height view, then **Collapse preview** to return. New speech follows the latest words until you scroll back. Tap **Latest** to resume following. The cog menu includes **Show original transcript**.
5. Optionally choose Raw+ or Polished for an AI edit using the selected provider. ChatGPT uses your plan allowance or enabled credits; the API options use separately billed API credit. Each generation uses the original transcript. Recognition, finishing, keeping unfinished words and adding speech never request an AI edit automatically.
6. Tap **Insert**. This only calls `InputConnection.commitText`. Use the host app's own send/post button when ready.
7. Use Android's keyboard-switch control or the cog menu's **Return to Gboard** for normal corrections. **Choose keyboard** in the menu opens Android's keyboard picker. Setup and Clear draft are also in the menu. On short landscape screens the mode choices move into that menu too.

The panel respects Android's available height and reserves any navigation/cutout overlap inside its padding. Expansion resizes the same preview without opening another app or Android's extracted host editor. Android can still restart an editor when its layout changes; those restarts retain the existing draft-clearing privacy policy and need checking in each target app.

Cursor placement uses the exact original transcript. When viewing an AI result, the first tap reveals the original, then a second tap places the cursor. This avoids guessing how an edited word maps to your source. New segments and provisional speech appear at that point, and Latest follows the insertion rather than the original suffix. The preview never takes focus as a second keyboard editor. It places one insertion point and does not offer selection replacement or typed corrections. **Dictate at end** in the cog moves the cursor back to the end.

## Use your ChatGPT plan

This build implements [OpenAI's official sign-in flow for open-source apps](https://developers.openai.com/siwc/token-sharing-open-source/sign-in). It registers Raw+ as its own public client, opens approval in your normal browser and stores the resulting authorization encrypted on this phone. It does not borrow another app's OAuth client or ask you to paste a ChatGPT cookie, password or token. No Raw+ backend is needed.

1. Open **Raw+ Keyboard**, then **Open Providers**, or choose **Providers** in the keyboard cog.
2. Select **ChatGPT plan**, then **Continue with ChatGPT**. Approve access in the browser using your existing ChatGPT account. Press Android's Back button to return to Raw+, then **Back to Providers** after sign-in finishes.
3. Tap **Load available models**. Choose one from the returned list and tap **Save selected model**. There is no automatic model choice.
4. Tap **Use ChatGPT plan**. The status identifies the active account and model. Signing in, listing models and saving choices do not send a transcript or request an edit.
5. Return to LinkedIn, dictate in Raw, then explicitly tap **Raw+** or **Polished**. Review and Insert. Raw remains local and makes no model request.

Editing shares your ChatGPT plan's usage limits. It can use extra credits if you have enabled them under your ChatGPT settings. **Manage ChatGPT usage** opens the usage page so you can review access and limits. An OpenAI API balance is separate from this plan authorization. See [OpenAI's model and inference documentation](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference) and [preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).

Providers keeps saved OpenAI account registrations on the phone. Choose an entry, then **Use selected account**, to change accounts explicitly. **Sign in again to selected account** renews that registration. Load models and save a model choice for the chosen account before selecting the provider. Raw+ does not load your existing ChatGPT conversations or cloud history. See [OpenAI's account and session documentation](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions).

**Sign out of selected ChatGPT account** revokes that registration's authorization and clears it locally. If remote revocation cannot be confirmed, the app reports that separately. Sign-out preserves your separately saved API credential and local test history. Forgetting an API key likewise preserves ChatGPT authorization. No auth token is displayed, exported to transcript history or stored in saved screen state.

Quota, network and authorization failures during an edit preserve the original transcript and offer Raw. No provider fallback, automatic retry or paid API request follows a ChatGPT failure. Canceling an edit discards a late result. Changing account or model invalidates results from an earlier selection. Opening Providers still hides the keyboard and clears the working draft under the existing privacy policy; it does not delete local test history.

Implementation and JVM tests do not establish successful phone authorization, account eligibility, latency or output quality. Browser approval, returning to the app and the actual LinkedIn keyboard edit remain physical-device acceptance checks.

## Use an OpenAI API key or backend

The API key option remains available for a phone-only test over Wi-Fi or mobile data:

1. Open **Providers** and select **OpenAI API**.
2. Paste your API key into the masked field on the phone. Do not put it in chat.
3. Check the consent box for sending your original dictated transcript directly to OpenAI.
4. Tap **Save and use OpenAI**. Saving makes no API request and does not check the key's balance.
5. Dictate in Raw, then explicitly tap Raw+ or Polished to request editing.

The key is encrypted on the device using Android Keystore and excluded from backup. It is never bundled in the APK, displayed after saving or included in the request body. It authenticates HTTPS requests only to `https://api.openai.com/v1/responses`. **Forget saved API key or backend token** removes it while preserving provider selection, ChatGPT authorization and local history. Leave a field blank to reuse a saved key only for its original API provider and endpoint. Choosing ChatGPT retains that credential without using it. There is one saved API credential slot; saving a different API provider replaces it.

API requests are billed separately from your ChatGPT subscription. The fixed API model is `gpt-4.1-mini`; see [model pricing](https://developers.openai.com/api/docs/models/gpt-4.1-mini). That model is scheduled for API removal on 14 January 2027, so update the prototype before then. See [OpenAI deprecations](https://developers.openai.com/api/docs/deprecations). This fixed API model does not set the ChatGPT model choice. Invalid credentials, API credit/quota and network failures retain the original transcript and allow Raw insertion.

The backend option remains available for stronger separation of the API key from the phone. Follow [the Node server setup](server/README.md), select **My own backend**, and enter its HTTPS URL and separate access token. For USB development, localhost HTTP with `adb reverse` remains supported. No backend hosting or account system is provisioned by this repository.

## Retired local model tests

The user reported that Gemini Nano worked in setup but failed at keyboard input. This is consistent with the documented foreground restriction, but the exact error was not supplied. The subsequent llama.cpp model worked on the Pixel but was too slow to use. Local editing is therefore removed from 0.1.7. These are user-reported results, not agent-observed device benchmarks.

Stored Nano or llama.cpp selections migrate to ChatGPT with sign-in and model choice required. This does not authorize a new provider, use an API key, send a draft or start an edit. The saved API credential and local history are retained.

If an earlier version downloaded the 1.12 GB model, Providers offers **Delete downloaded local model**. Deletion is explicit and touches only the retired app-owned model directory. It preserves credentials and test history. Old weights are not used or downloaded by this version.

The old C++ integration, pinned upstream source and license files remain in the repository as unbuilt reference material after automatic review blocked their broad recursive deletion. The Gradle build no longer references them. The APK includes no native inference library or model weights, and building it requires no NDK or CMake. Nano, ML Kit and AICore dependencies also remain absent. Historical findings are in [verification evidence](docs/verification.md).

## What the modes do

- **Raw** runs locally. The cursor preview shows the exact original words and punctuation. Inserting Raw adds a terminal full stop when missing in each paragraph. Dictated segments are separated by blank lines. It does not guess additional sentence boundaries or correct words.
- **Raw+** applies the [exact supplied skill](server/raw-plus/SKILL.md), followed by [the app's narrower editing notes](app/src/main/assets/raw-plus/editing-notes.txt). Remove hesitation. Preserve personality. Change as little as possible, retain meaningful uncertainty, expressions and vocabulary, and leave already-readable wording alone. Unslop concepts limit AI additions rather than treating your own words as a blacklist.
- **Polished** uses the same original transcript with more freedom to improve sentence structure. It must preserve meaning and certainty.

The recognizer's output is the original transcript available to this app. Android may already omit a hesitation or misrecognize a word. Raw+ cannot recover speech that the recognizer did not return. Model instructions constrain editing, but human review is still necessary.

## Local test history

Version 0.1.4 enables local logging for this personal test, as requested. Open **Local test history** from the cog or setup screen to compare entries, turn logging off, export or delete everything. Each confirmed dictation, accepted AI result and successful insertion records its original source, output, mode, time, session and app prompt version. Consecutive Raw updates coalesce into the latest snapshot; an AI result retains the source it actually used. Inserted text is the text passed to the host field, not proof of publication, sending or later corrections.

Entries are encrypted with an independent Android Keystore key in app-private, non-backed-up storage. The journal keeps at most 100 entries for 30 days within 4 MiB, trimming older entries when next read or written. It records no audio, unconfirmed hypotheses, credentials, host text or host app identifiers. Writes run off the keyboard's UI thread. Queued records from before Clear or a logging change are skipped, so deleted history cannot reappear from an old queue.

Logging off stops future records and leaves existing entries until deletion or expiry. **Clear draft** clears the working draft; **Clear all history** deletes the local journal. Neither deletes exported files. Export requires a separate tap and destination choice in Android's file picker, where Google Drive can be selected. The exported JSON contains plain text and is sent nowhere automatically. Review it before sharing it for feedback. Forgetting your API key or signing out of ChatGPT does not delete this separate text history. Authorization tokens are never part of the journal or export.

## Recovery and privacy

Confirmed speech remains in the working draft during recognition and editing failures. Partial hypotheses replace each other at the insertion point rather than appending duplicates. After interruption, unfinished text is labelled and needs **Keep words** before it can be edited, inserted or logged. **Cancel** discards unfinished speech but retains confirmed text and the insertion position. There is no automatic retry or recording restart.

Changing fields, hiding or switching keyboards, and successful insertion clear the working draft. Android editor restarts also clear it, even in the same field. Cancel a request or leave the keyboard to discard an edit; bytes already sent to a backend or provider cannot be recalled. Confirmed text remains in encrypted local history while logging is enabled. There is no automatic draft restoration, audio storage, logcat transcript output, cloud backup or clipboard capture. Process death loses the working draft but leaves previously saved history.

The app never reads host text around the cursor. Password, private/incognito and non-text fields disable dictation and insertion. Sensitive-field detection relies on correct host editor flags. Do not dictate secrets into an ordinary text field. The Providers credential field accepts your own API key or backend token; it does not capture passwords from other apps.

Audio uses only Android's explicit on-device recognizer, with no fallback to an online speech service. English speech-model downloads can need a network connection. When you request AI editing, the original dictated transcript goes to OpenAI through your ChatGPT authorization, your API key or the selected backend, with the Raw+ rules and mode. `store:false` disables stored Responses state; it is not a zero-retention promise. Provider data policies still apply. See [OpenAI's API data controls](https://developers.openai.com/api/docs/guides/your-data) for API editing and the plan authorization terms shown during ChatGPT approval. Feedback screenshots are user initiated; credential entry remains protected as described above. Host apps may store text after insertion under their own policies.

## Build and verify

The Android build requires JDK 17, Gradle 8.13, Kotlin 2.3.21, Android Gradle plugin 8.13.2, Android SDK platform 35 and build-tools 35.0.0. The optional backend tests require Node.js 22 or newer. NDK and CMake are not required. The wrapper pins Gradle's official checksum. Set `ANDROID_HOME` or create an ignored `local.properties` containing `sdk.dir=/path/to/Android/Sdk`. Minimum device version remains Android 12, API 31; the target and compile SDK are 35.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
cd server
node --test
```

The build versions are pinned. The application has no native build configuration, bundled `.so` files, model weights, ML Kit or AICore binding. The retired `third_party/llama.cpp` subset and `app/src/main/cpp` are unbuilt reference source only. No additional runtime SDK dependencies remain beyond the Kotlin standard library. The backend still has no runtime dependencies.

See [API findings and implementation decisions](docs/android-investigation.md), [device acceptance checks](docs/device-testing.md), and [verification evidence](docs/verification.md). A successful build or emulator test does not establish Pixel speech quality or acceptance in the three target apps.
