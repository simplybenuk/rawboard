# Pixel 10 acceptance checks

These are physical-device checks, not completed claims. Run with the built APK and current Android/speech-service versions. Record the model, Android build, speech-provider version, date and results in `verification.md`. Do not use real passwords or submit public test posts.

## First local proof

1. Install, grant the microphone, enable Raw+, select it and leave Gboard enabled.
2. Check en-GB model support in setup. Download it if needed, wait for completion and recheck.
3. With networking disabled, use the setup practice field. Dictate about a minute of natural speech, including false starts and uncertainty. Select Raw and verify usable output.
4. Repeat with pauses of two, five, ten and thirty seconds. Note when recognition actually stops and whether the keyboard states match capture. Check that Dictate more appends rather than replaces confirmed speech.
5. Reach the two-minute limit. Stop halfway through a sentence. Cancel during startup and finalization. Trigger a competing audio app or call, toggle microphone privacy, lock the screen and revoke permission. Confirm errors are visible and unfinished text needs confirmation. A service may return no partial text; unreturned speech cannot be recovered.

Do not proceed on the assumption that Gboard's own offline voice typing proves this API path works. If the public recognizer consistently drops useful speech or stops too early, record examples. Reconsider the transcription provider separately rather than masking failures with repeated restarts or silently uploading audio.

## Raw+ quality

For the OpenAI phone-only path, install the APK over the existing version, open Providers, select OpenAI, enter the key on the phone, enable transcript-sharing consent and save. Alternatively, start the optional backend and save its URL, access token and sharing consent. An external edit incurs a separately billed API request. Use non-sensitive sample speech such as:

> um I guess, I guess what I'm trying to say is this is kind of useful, but I don't know if it would work for every team. Ooh, the bit I liked was how simple it was.

Check the original first. If the recognizer omitted a word, label that a transcription issue. In Raw+, remove disruptive repetitions without removing meaningful "I guess", "kind of", "I don't know" or "Ooh". Preserve the point and level of certainty. There should be no invented hook, strengthened opinion, example, conclusion, CTA, hashtag or generic marketing wording. Polished may restructure more but must keep meaning. Raw should retain every recognizer word. Switch modes repeatedly and confirm every request's source is the original.

In direct mode, verify no server or USB connection is needed over Wi-Fi/mobile data. Save a key, restart the app and confirm it remains hidden but usable. Saving alone must make no API call. Check that Forget saved key removes the stored credential, and that backend/provider changes cannot reuse it. Test an invalid key, a key without model access, and insufficient API credit. These should show useful errors and preserve Raw. Never use a real key in test logs or screenshots.

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

With version 0.1.3, check gesture navigation and three-button navigation. Insert, Dictate/Stop and Cancel must sit above system controls. Tap Insert repeatedly with invented drafts, including near its lower edge, and confirm it never selects the Android globe. Check landscape cutouts and larger font/display sizes too.

Dictate enough words to scroll. The preview should follow partial replacements and final segments. Scroll upward while speaking and check that new words do not pull you away. Scroll to the bottom or tap Latest to resume. Expand and collapse while recording, while reviewing older text and during AI editing. Expansion must preserve the same draft, ongoing capture and pending result without reading the host field. Test each target app for editor restarts during resizing; these deliberately clear drafts under the existing privacy policy.

Set a short screen timeout. While Raw+ is listening or finalizing, the screen must stay awake without taps. After Stop completes, Cancel, an error, keyboard hiding, switching, field changes or app backgrounding, normal timeout must resume. Manual locking must still work and stop dictation. The two-minute recording cap and eight-second finalization watchdog remain in effect.

With a valid key saved, verify each new field, cleared draft and recording starts in Raw. Stop, keep unfinished words and Dictate more must make no AI request. Choose Raw+ or Polished explicitly to send one request. Dictate more after an AI edit must preserve the original words, return to Raw and wait for another explicit AI selection. Retry is also an explicit paid request. Use the optional backend's controlled request counter or the API usage dashboard, without logging speech or keys.

Test password, visible password, numeric password, private/incognito and ordinary multi-line fields. Protected fields must disable capture, insertion and history events without reading their contents. Test backgrounding, field changes, keyboard hiding and same-field editor restarts. Working drafts clear on these boundaries. Inspect app storage and logcat using invented phrases: only the encrypted local-history journal may retain confirmed speech when enabled. No audio files, plaintext transcript files, clipboard writes or logcat transcript output should appear.

## Cursor dictation and prompt refinement

With version 0.1.4, dictate three short paragraphs, tap inside the middle one and check that a blinking cursor appears without changing the host field or opening another keyboard. Tap Dictate here and speak a new paragraph. Stop and verify that both original sides remain intact, new words stay at the chosen point, and successive recognition segments keep their order. Repeat at the beginning, end, before/between/after existing blank lines, near emoji, after scrolling and in expanded/compact layouts. Dragging to scroll must not place a cursor. Cancel a provisional recording and confirm the original and insertion point are unchanged. Interrupt and Keep words, then check the same insertion position.

While viewing Raw+ or Polished, the first tap should show the original from the top, and the next tap should place its cursor. New dictation must return to Raw. No AI output may become a future request's source. While dictating into a long middle section, Latest must follow the new insertion, not the original document end; manual review pauses following. Check actual touch accuracy, blinking, accessibility selection and host cursor stability on the Pixel.

Prompt version raw-plus-2026-10-20.2 should keep Raw+ close to Raw. Try already-readable speech and meaningful "I guess", "kind of" and "literally". Confirm the result does not trade those expressions for professional-sounding synonyms, strengthen uncertainty or add AI introductions, em dashes, headings or conclusions. The prompt tests verify instructions and source wiring, not model output quality. Use local history to collect examples for later refinement.

## Local test history

After installing the update, verify local logging is on and its description is visible in setup/history. With invented speech, check a confirmed Raw snapshot, Raw+ result, Polished result and successful insertion. Each AI event must retain the exact original used for that result, even after later middle insertion. Consecutive Raw segments should coalesce, while AI and inserted events remain separate. Check time, mode and prompt version. Inserted must never be labelled published.

Unconfirmed partials and canceled unfinished words must not create entries. Private/password fields and setup credentials must create none. Switching fields clears the working draft and starts a separate history session. Turn logging off and confirm new dictated, generated and inserted text is absent, including after restarting the app. Existing history should remain until Clear all history. Logging off and then on, or clearing while a prior write is queued, must not resurrect older queued content. Deleting history must preserve the separately saved API key.

Export JSON through the document picker and compare its source/result pairs. Cancel the picker and verify no export write. Test local storage and a user-chosen Drive destination, file-provider failure, rotation during selection/export and deletion. Export errors should preserve local history and mention that a partial destination file may remain. Exported files are plaintext and must be deleted separately. No API key, audio, host content or app identifier belongs in the JSON. Encrypted storage, retention, disable/delete races and export still require real-device checks.

## Provider selection and local llama.cpp

Version 0.1.6 removes Nano and ML Kit entirely. The 0.1.5 user test succeeded in setup and failed at keyboard input; the exact error and host app were not supplied. Preserve that result as historical evidence, rather than retesting Nano without a specific relevant change.

Upgrade in place from 0.1.5. An old Nano selection must become llama.cpp with model setup required. It must never silently choose OpenAI, upload a draft or decrypt the saved cloud key. Explicitly choose OpenAI again with its credential field blank and saved consent retained; confirm the existing key still works. Verify cloud provider/address boundaries and credential screenshot protection as before. Local history must survive the update.

In Providers, choose llama.cpp. Before installing a model, sample and Use must explain that setup is needed. Raw remains available. Confirm no download starts on launch, selection, recording, mode defaults or saving a cloud provider. Download the 1.12 GB pinned model on Wi-Fi, observe progress, cancel, retry, leave the screen, rotate, and test a failed/slow network. Interrupted downloads restart; existing verified files must survive setup failure. The screen should stay awake only during setup/sample work and return to normal on completion/cancel/stop. The download contains model requests only, with no transcript or credential.

Download the exact linked file in a browser and import it through Android's file picker. Test picker cancellation, a wrong model/quantization, truncated file, invalid GGUF header, checksum mismatch, low storage, revoked/failed document provider and import cancellation. Every invalid import must be rejected before native loading and leave the old verified model available. Partial staging files must be cleaned without deleting another running operation's file. No storage permission should be requested. Restart the app and verify the installed model remains available. Delete it and verify local editing requires setup again, with the API key and history retained.

Run the synthetic sample and review the complete output. Test cancellation during model load/prefill/generation, timeouts and repeated requests. No result should appear after leaving, changing provider, hiding the keyboard, changing field or starting another dictation. A successful settings sample remains separate from actual keyboard acceptance, though this runtime does not use AICore's foreground gate.

Then select llama.cpp and focus a LinkedIn comment. Dictate a short thought in Raw, tap Raw+, review, and Insert without leaving LinkedIn or posting. Repeat in WhatsApp and Gmail. Test Polished from the same original, return to Raw, and insert another paragraph at the source cursor. In airplane mode, after installing English speech and the local model, repeat local dictation/editing/insertion to establish actual offline operation. Check immediate Gboard access and navigation clearance.

Try a local Raw+ output that changes or omits original words. It must report rejection, keep the source and offer Raw, without a cloud request. This conservative filter can reject legitimate word repairs too; punctuation and emphasis still need review. Polished does not use that word filter.

Increase draft length, include meaningful uncertainty and distinctive expressions, compare original/result in local history and inspect Nano-free provider tags. Check screen wakefulness during local editing, UI responsiveness, warm-up latency, total edit latency, RAM pressure, battery/heat and cancellation responsiveness. The three-minute/output/context limits must retain Raw rather than inserting a fragment. Test process death separately; the working draft is memory-only and may be lost even though confirmed history persists. CPU desktop results cannot establish Pixel performance or preservation of voice.

Inspect the final APK: only RECORD_AUDIO and INTERNET permissions, no ML Kit/AICore components or diagnostics startup provider, no model weights, one arm64-v8a JNI library plus the C++ runtime, and valid 16 KB page alignment. Confirm no prompt, output, credentials or host text appears in logcat. Local samples should not become draft/history events. No automatic cloud fallback may occur on any local failure.

## Screenshots and system theme

Use an invented phrase to check that a keyboard screenshot works in an ordinary host field. Ordinary setup screenshots should work too. Focusing an empty credential field must block screenshots; an unsaved credential must remain protected after losing focus. Save or clear it and tap elsewhere, then verify screenshots work again. Test both the OpenAI key field and backend token field without including real secrets in evidence.

Set the phone to dark mode, open setup and show the keyboard in a host app. Check the page, preview, buttons, inputs, hints, radio/check controls and navigation bar. Repeat in light mode. Toggle the system setting while setup or the keyboard is visible and reopen the keyboard as needed. The palette should follow the system, including the preview and keyboard navigation area. Existing draft clearing on hiding, field changes or editor restarts still applies. Verify no extra bright background fills unused IME window space.

The definition of done requires the real LinkedIn-on-Pixel experience plus immediate access to Gboard. Compilation, mocked editing tests and a generic emulator cannot replace it.
