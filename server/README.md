# Optional editing server

The Android keyboard can use your own OpenAI API key directly on the phone. This server is optional; select **My own backend** in the app to use it. Raw stays on the phone. When backend editing is selected, this server sends the original transcript to OpenAI, then returns edited text. It uses Node.js 22 or newer, built-in modules and no runtime dependencies.

## Start locally

From the project root:

```sh
cd server
cp .env.example .env
node -e "console.log(require('node:crypto').randomBytes(32).toString('hex'))"
```

Edit `.env`. Set `OPENAI_API_KEY` to your OpenAI API key and `RAWPLUS_CLIENT_TOKEN` to the generated token. The client token must contain at least 32 characters and no whitespace. Put that client token in the keyboard's settings, never the OpenAI key.

```sh
node --env-file=.env index.mjs
```

The default listener is `127.0.0.1:8787`. With a USB-connected Android device and USB debugging enabled, run this on the computer:

```sh
adb reverse tcp:8787 tcp:8787
```

Set the keyboard's backend URL to `http://127.0.0.1:8787`. This loopback HTTP connection uses the USB debugging tunnel. For any other hostname, use HTTPS and a TLS reverse proxy. Set `HOST` only when you need another bind address. Do not expose this plain HTTP listener directly to the internet.

## Requests and limits

`GET /health` returns only `{"ready":true}` with status 200 when credentials are configured, or `{"ready":false}` with status 503. It does not check upstream access or incur API costs.

`POST /edit` requires `Authorization: Bearer <RAWPLUS_CLIENT_TOKEN>` and `Content-Type: application/json`:

```json
{"mode":"raw_plus","transcript":"um I guess that sounds right"}
```

Use `raw_plus` or `polished`. Success returns `{"text":"I guess that sounds right."}`. The server rejects `raw`, blank transcripts and transcripts longer than 12,000 JavaScript characters. It preserves the original transcript in the provider input. Errors return `{"error":"..."}`; the keyboard should retain its original text.

The server limits request bodies to 96 KiB, body reads to 10 seconds, upstream requests to 25 seconds and provider response bodies to 256 KiB. Each process accepts at most 10 authenticated requests per minute and two simultaneous requests. Invalid authenticated requests count toward the rate cap. Limits reset on process restart. There are no automatic retries. A canceled or timed-out request may still incur provider charges if processing already began.

`OPENAI_MODEL` defaults to `gpt-4.1-mini`, with at most 4,096 output tokens per request. Its listed standard text price is $0.40 per million input tokens and $1.60 per million output tokens as checked on 20 October 2026. Account access and prices can change. See the [official model page](https://developers.openai.com/api/docs/models/gpt-4.1-mini). Configure a provider project budget as well; process limits do not impose a monthly spend cap.

## Editing rules and data

[raw-plus/SKILL.md](raw-plus/SKILL.md) is a byte-for-byte copy of the [user's Raw+ skill](https://github.com/simplybenuk/ai-skills/blob/main/raw-plus/SKILL.md). Every request includes that full file, [versioned editing notes](raw-plus/editing-notes.txt), explicit mode controls and instructions to treat the transcript as data. The notes keep Raw+ close to Raw, preserve meaningful uncertainty and expressions, and prevent added AI phrasing. The skill and notes match the phone assets. Model editing quality and exact preservation still need human review.

The server keeps no transcript history, logs no request bodies or tokens, and requests `store:false` through the [OpenAI Responses API](https://developers.openai.com/api/docs/guides/text). This disables storing the response for later API retrieval. It does not mean zero data retention. OpenAI abuse monitoring and other applicable retention rules may still apply; review [OpenAI data controls](https://developers.openai.com/api/docs/guides/your-data) before sending sensitive text. Your speech recognition provider has its own data handling, separate from this editing server.

## Verify without API charges

```sh
cd server
node --test
```

All tests inject a mocked fetch implementation. They make local HTTP requests only and never contact OpenAI. No deployment or paid request is part of this prototype's verification.
