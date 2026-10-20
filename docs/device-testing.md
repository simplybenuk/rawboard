# Pixel 10 acceptance checks

Unless explicitly identified as a user-reported result below, these physical-device checks are pending. Run with the built APK and current Android/speech-service versions. Record the model, Android build, speech-provider version, date and results in `verification.md`. Do not use real passwords or submit public test posts.

## First local proof

1. Install, grant the microphone, enable Raw+, select it and leave Gboard enabled.
2. Check en-GB model support in setup. Download it if needed, wait for completion and recheck.
3. With networking disabled, use the setup practice field. Dictate about a minute of natural speech, including false starts and uncertainty. Select Raw and verify usable output.
4. Repeat with pauses of two, five, ten and thirty seconds. Note when recognition actually stops and whether the keyboard states match capture. Check that Dictate more appends rather than replaces confirmed speech.
5. Reach the two-minute limit. Stop halfway through a sentence. Cancel during startup and finalization. Trigger a competing audio app or call, toggle microphone privacy, lock the screen and revoke permission. Confirm errors are visible and unfinished text needs confirmation. A service may return no partial text; unreturned speech cannot be recovered.

Do not proceed on the assumption that Gboard's own offline voice typing proves this API path works. If the public recognizer consistently drops useful speech or stops too early, record examples. Reconsider the transcription provider separately rather than masking failures with repeated restarts or silently uploading audio.

## Raw+ quality

Install 0.1.7 over the existing version. For the ChatGPT path, complete the sign-in, explicit account/model and provider selection checks below. For the separate API path, open Providers, select OpenAI API, enter the key on the phone, enable transcript-sharing consent and save. Alternatively, start the optional backend and save its URL, access token and sharing consent. ChatGPT editing shares plan usage limits and may use credits enabled in ChatGPT; API editing is billed separately. Use non-sensitive sample speech such as:

> um I guess, I guess what I'm trying to say is this is kind of useful, but I don't know if it would work for every team. Ooh, the bit I liked was how simple it was.

Check the original first. If the recognizer omitted a word, label that a transcription issue. In Raw+, remove disruptive repetitions without removing meaningful "I guess", "kind of", "I don't know" or "Ooh". Preserve the point and level of certainty. There should be no invented hook, strengthened opinion, example, conclusion, CTA, hashtag or generic marketing wording. Polished may restructure more but must keep meaning. Raw should retain every recognizer word. Switch modes repeatedly and confirm every request's source is the original.

In OpenAI API mode, verify no server or USB connection is needed over Wi-Fi/mobile data. Save a key, restart the app and confirm it remains hidden but usable. Saving alone must make no API call. Check that Forget saved API key or backend token removes the stored credential without removing ChatGPT authorization, and that backend/provider changes cannot reuse it. Test an invalid key, a key without model access, and insufficient API credit. These should show useful errors and preserve Raw. Never use a real key in test logs or screenshots.

In backend mode, disconnect the backend, use a wrong token, omit its API key and exhaust its rate limit. Confirm each case retains the original, offers Raw and allows a manual retry. Switch fields while an edit is pending. No result should appear in the new field. Turning off sharing and saving must disable subsequent AI requests. A request already dispatched cannot be recalled.

## Target apps

Repeat the same flow in each app before marking the MVP accepted:

| App and field | Required outcome | Result |
| --- | --- | --- |
| LinkedIn comment | Dictate, review original, generate Raw+, compare all modes, Insert, return to Gboard. No automatic comment submission. | Pending Pixel |
| WhatsApp message | Same flow. Insertion leaves an unsent draft, including when Enter-to-send is enabled. | Pending Pixel |
| Gmail message body | Same flow. The email remains unsent; cursor position and paragraph breaks are usable. | Pending Pixel |

Verify Android's keyboard-switch control and the cog menu's Return to Gboard before dictation, during capture, during editing and after insertion. Disable Gboard once to check Choose keyboard fallback, then re-enable it. Rotate to landscape and increase Android font size. All controls must remain reachable. On short screens, mode selection moves to the cog menu.

## Layout, long drafts and explicit AI use

Retest the layout introduced in 0.1.3 with gesture navigation and three-button navigation. Insert, Dictate/Stop and Cancel must sit above system controls. Tap Insert repeatedly with invented drafts, including near its lower edge, and confirm it never selects the Android globe. Check landscape cutouts and larger font/display sizes too.

Dictate enough words to scroll. The preview should follow partial replacements and final segments. Scroll upward while speaking and check that new words do not pull you away. Scroll to the bottom or tap Latest to resume. Expand and collapse while recording, while reviewing older text and during AI editing. Expansion must preserve the same draft, ongoing capture and pending result without reading the host field. Test each target app for editor restarts during resizing; these deliberately clear drafts under the existing privacy policy.

Set a short screen timeout. While Raw+ is listening, finalizing or processing a requested edit, the screen must stay awake without taps. After Stop completes with no edit pending, edit completion, Cancel, an error, keyboard hiding, switching, field changes or app backgrounding, normal timeout must resume. Manual locking must still work and stop dictation. The two-minute recording cap and eight-second finalization watchdog remain in effect.

With a valid ChatGPT authorization or API key saved, verify each new field, cleared draft and recording starts in Raw. Stop, keep unfinished words and Dictate more must make no AI request. Choose Raw+ or Polished explicitly to send one request. Dictate more after an AI edit must preserve the original words, return to Raw and wait for another explicit AI selection. Retry is also an explicit usage-consuming request. Check ChatGPT usage for plan editing, or use the optional backend's controlled request counter or API usage dashboard for API editing, without logging speech, tokens or keys.

Test password, visible password, numeric password, private/incognito and ordinary multi-line fields. Protected fields must disable capture, insertion and history events without reading their contents. Test backgrounding, field changes, keyboard hiding and same-field editor restarts. Working drafts clear on these boundaries. Inspect app storage and logcat using invented phrases: only the encrypted local-history journal may retain confirmed speech when enabled. No audio files, plaintext transcript files, clipboard writes or logcat transcript output should appear.

## Cursor dictation and prompt refinement

Retest cursor dictation introduced in 0.1.4. Dictate three short paragraphs, tap inside the middle one and check that a blinking cursor appears without changing the host field or opening another keyboard. Tap Dictate here and speak a new paragraph. Stop and verify that both original sides remain intact, new words stay at the chosen point, and successive recognition segments keep their order. Repeat at the beginning, end, before/between/after existing blank lines, near emoji, after scrolling and in expanded/compact layouts. Dragging to scroll must not place a cursor. Cancel a provisional recording and confirm the original and insertion point are unchanged. Interrupt and Keep words, then check the same insertion position.

While viewing Raw+ or Polished, the first tap should show the original from the top, and the next tap should place its cursor. New dictation must return to Raw. No AI output may become a future request's source. While dictating into a long middle section, Latest must follow the new insertion, not the original document end; manual review pauses following. Check actual touch accuracy, blinking, accessibility selection and host cursor stability on the Pixel.

Prompt version raw-plus-2026-10-20.2 should keep Raw+ close to Raw. Try already-readable speech and meaningful "I guess", "kind of" and "literally". Confirm the result does not trade those expressions for professional-sounding synonyms, strengthen uncertainty or add AI introductions, em dashes, headings or conclusions. The prompt tests verify instructions and source wiring, not model output quality. Use local history to collect examples for later refinement.

## Local test history

After installing the update, verify local logging is on and its description is visible in setup/history. With invented speech, check a confirmed Raw snapshot, Raw+ result, Polished result and successful insertion. Each AI event must retain the exact original used for that result, even after later middle insertion. Consecutive Raw segments should coalesce, while AI and inserted events remain separate. Check time, mode and prompt version. Inserted must never be labelled published.

Unconfirmed partials and canceled unfinished words must not create entries. Private/password fields and setup credentials must create none. Switching fields clears the working draft and starts a separate history session. Turn logging off and confirm new dictated, generated and inserted text is absent, including after restarting the app. Existing history should remain until Clear all history. Logging off and then on, or clearing while a prior write is queued, must not resurrect older queued content. Deleting history must preserve the separately saved API key and ChatGPT authorization. ChatGPT sign-out must preserve local history.

Export JSON through the document picker and compare its source/result pairs. Cancel the picker and verify no export write. Test local storage and a user-chosen Drive destination, file-provider failure, rotation during selection/export and deletion. Export errors should preserve local history and mention that a partial destination file may remain. Exported files are plaintext and must be deleted separately. No API key, OAuth token, audio, host content or app identifier belongs in the JSON. Encrypted storage, retention, disable/delete races and export still require real-device checks.

## ChatGPT sign-in and provider changes

Version 0.1.7, version code 8, implements [OpenAI's documented public-app sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in) with direct ChatGPT plan authorization. No real Pixel OAuth or ChatGPT inference success has been established. Follow these checks using an existing account, with no password, cookie, API key or OAuth token in screenshots or logs.

1. Upgrade in place from 0.1.6 or 0.1.5. An old llama.cpp or Nano selection must become unconfigured ChatGPT. No browser, model lookup, edit, API-key decryption or fallback request may start. Raw works before sign-in. Existing history and the separate saved API credential survive.
2. Open **Providers**, select **ChatGPT plan**, and tap **Continue with ChatGPT**. Verify the normal browser opens OpenAI approval for Raw+ Keyboard. The app must not ask for your password or an API key. Complete approval, press Android Back to return to Raw+, then tap **Back to Providers** when sign-in finishes. Check that signing in did not select an editing provider or send a draft.
3. Confirm the connected account is identified. Tap **Load available models** and verify a returned list appears with **Choose a model** as its placeholder. No model may be chosen automatically. Pick one, tap **Save selected model**, then **Use ChatGPT plan**. The status must identify the active account and model and say **Using ChatGPT plan** only when that provider is ready and selected.
4. Open LinkedIn, dictate a short thought in Raw, explicitly tap Raw+, review and Insert. It must remain an unsent comment, with immediate access to Gboard. Repeat with Polished from the same original, return to Raw and add dictation at the source cursor. Repeat in WhatsApp and Gmail over Wi-Fi and mobile data without a computer or backend.

Check shared-plan usage and the **Manage ChatGPT usage** link. The UI must explain that edits share the ChatGPT allowance and may consume extra credits if enabled in ChatGPT. The API key option must remain clearly separate. Loading available models must not send the current transcript or generate text. Compare output quality and timing using invented speech and local history; do not infer Pixel speed from JVM tests.

Cancel browser approval, deny the plan grant, close the browser, leave sign-in waiting, press Cancel sign-in, rotate during sign-in and kill/relaunch the process. A lost OAuth attempt must require a fresh explicit attempt. Test a second attempt after cancellation and ensure the old browser callback cannot connect an account. Error messages must not include authorization codes, tokens, redirect query strings or provider response bodies. No automatic retry or provider switch may occur.

Register another existing OpenAI account through **Continue with ChatGPT**. Choose it in the saved-account picker, then tap **Use selected account**. Verify account choice is explicit, models are loaded for that account and no model is chosen silently. Reauthorize it with **Sign in again to selected account**. Switch back to the first account and verify each registration retains only its own authorization and model selection. These are saved OpenAI registrations on the phone, not new Raw+ user accounts. See [OpenAI's account/session documentation](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions).

Load the model list, choose a different model, save and use it. Confirm the next edit uses the selected model, while every source remains the original transcript. Test a missing model, unavailable model, denied plan scope, expired authorization, invalid refresh authorization, quota exhaustion, rate limits, a disconnected/slow network and incomplete responses. Each edit failure must preserve the original, expose Raw and require an explicit retry. It must never use the saved API key automatically. See [model and inference requirements](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference) and [preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).

Start a long edit, then Cancel, change field, hide the keyboard, begin another recording or open Providers. No late result may enter a new draft or field. Change the account, model or provider while an old request is in flight and verify its result is discarded. Opening Providers hides the keyboard, so its normal privacy policy clears the working draft; confirmed local test history remains when enabled. An in-place authorization/network/quota failure keeps the original in the keyboard. A dispatched request cannot be recalled and can still consume usage even after cancellation.

Sign out explicitly through **Sign out of selected ChatGPT account**. Verify the chosen registration is disconnected and cannot edit until reauthorized. The saved API key, other registrations and test history must remain. Test revocation failure and offline sign-out: the UI must distinguish unconfirmed remote revocation from local authorization removal. Revoke app access from ChatGPT while an edit is pending and confirm the next failure retains Raw, with no API fallback. No cached result may become usable after sign-out or an account/model change.

Select OpenAI API again. Leave the key field blank with saved consent and explicitly tap Save and use OpenAI; verify the existing key still works. Selecting or signing out of ChatGPT must not replace/decrypt it. Forget the saved API key and verify ChatGPT remains connected. Check API provider/address credential boundaries as before. Go to the browser or another app with an unsaved API credential, then return: account status refresh must neither display saved secrets nor reset the unsaved field or its screenshot protection.

Inspect private storage, backup rules, saved screen state, local journal/export and logcat. OAuth access/refresh tokens must be encrypted outside backup and absent from UI diagnostics, bundles, transcript history, exports and logs. No app-owned cloud account, transcript-history service or telemetry SDK should appear. ChatGPT sign-in must not grant access to stored ChatGPT conversations. The callback listener must close on completion, cancellation or expiry rather than remain listening indefinitely.

## Retired local model tests

The user reported that Nano 0.1.5 worked in setup but failed at keyboard input. The exact error and host app were not supplied. The user then reported that llama.cpp 0.1.6 worked on the Pixel but was too slow to use. These reports justify retiring both integrations; they do not provide measured latency, memory or error-code evidence. Do not retest local inference as part of current acceptance.

Verify neither local provider, local sample nor model download/import action appears in 0.1.7. If the old 1.12 GB model exists, Providers should offer **Delete downloaded local model**. It must not delete automatically on upgrade. Tap the button and confirm only the retired model directory disappears; API credentials, ChatGPT authorization and history remain. No arbitrary filesystem path or symlink target may be deleted.

Inspect the final APK: only RECORD_AUDIO and INTERNET permissions, no ML Kit/AICore components, no diagnostics startup provider, no native `.so` libraries and no model weights. Building requires JDK 17, Gradle 8.13 and SDK 35, with no NDK or CMake. The old native integration and upstream source remain as unbuilt reference files after the broad directory-deletion action was blocked by automatic review; they must not be referenced by the Gradle build. Migration from a retired provider must not trigger an edit or API fallback.

## Screenshots and system theme

Use an invented phrase to check that a keyboard screenshot works in an ordinary host field. Ordinary setup screenshots should work too. Focusing an empty credential field must block screenshots; an unsaved credential must remain protected after losing focus. Save or clear it and tap elsewhere, then verify screenshots work again. Test both the OpenAI key field and backend token field without including real secrets in evidence.

Set the phone to dark mode, open setup and show the keyboard in a host app. Check the page, preview, buttons, inputs, hints, radio/check controls and navigation bar. Repeat in light mode. Toggle the system setting while setup or the keyboard is visible and reopen the keyboard as needed. The palette should follow the system, including the preview and keyboard navigation area. Existing draft clearing on hiding, field changes or editor restarts still applies. Verify no extra bright background fills unused IME window space.

The definition of done requires the real LinkedIn-on-Pixel experience plus immediate access to Gboard. Compilation, mocked editing tests and a generic emulator cannot replace it.
