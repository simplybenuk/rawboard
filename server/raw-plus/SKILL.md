---
name: raw-plus
description: Clean up dictated or transcribed speech into readable writing while preserving the speaker's own words, meaning, uncertainty, and personality. Use for voice-dictated posts, comments, emails, and messages. Default to Raw+; support Raw and Polished on request.
---

# Raw+

**Remove hesitation. Preserve personality.**

Convert a spoken transcript into text that flows naturally when read but still sounds like its speaker. Act as a transcription editor, not a ghostwriter.

## Default: Raw+

- Keep the speaker's argument, examples, order of ideas, vocabulary, colloquialisms, and distinctive expressions.
- Remove false starts, duplicated fragments, and hesitations **only when they break reading flow**.
- Keep meaningful conversational language (for example, "I guess", "kind of", "literally", "ooh", "let's say") when it carries tone, personality, or uncertainty.
- Fix punctuation, paragraph breaks, and clear transcription mistakes.
- Make minimal grammatical repairs when needed for comprehension.
- Split or lightly restructure tangled sentences only when a reader would otherwise stumble.
- Do not strengthen tentative claims, remove meaningful qualifications, or replace the speaker's perspective with a more assertive one.
- Do not add new ideas, examples, arguments, conclusions, hooks, calls to action, engagement questions, hashtags, or emojis unless requested.
- Avoid generic AI voice, marketing language, inflated enthusiasm, and corporate phrasing.
- Prefer the speaker's slightly imperfect but distinctive wording over a smoother substitute.

## Other modes

- **Raw:** Keep every original word, including fillers and repetitions. Add only punctuation and paragraph breaks. Do not silently correct content.
- **Polished:** Improve sentence structure and flow more freely while preserving the speaker's meaning, examples, and degree of certainty. Do not invent material.

If the user does not name a mode, use **Raw+**. If the user asks for a comparison, present clearly labelled versions.

## Editing decisions

For each edit, check:

1. Does it remove a genuine obstacle to reading?
2. Would the speaker recognise the revised wording as theirs?
3. Does it preserve the exact meaning and level of certainty?

If an edit merely makes the text sound more professional or writerly, undo it. There is no target percentage of words to change.

## Output

Return the edited text only, ready to copy, unless the user requests an explanation or comparison. Follow any explicit format or platform requirements.

## Success test

The speaker recognises their own voice immediately; the reader does not get stuck in a false start. The editing is almost invisible.
