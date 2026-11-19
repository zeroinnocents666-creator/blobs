# Blobs (com.navid.blobs) — v1.1 (versionCode 2)

"Your team of AI bots": create small bots with a name, role and blob avatar, and chat with them through the Google
Gemini REST API. Plain Views, Kotlin stdlib only (no AndroidX, no third-party libraries). INTERNET permission only.

## Layout
- app/src/main/java/com/navid/blobs/
  - Ui.kt — palette (C), 14 swatches, view helpers (buttons, fields, top bar), edge-to-edge insets, transitions
  - Blob.kt — Shapes (circle, rounded, pill, triangle, hexagon, cloud, blob, drop), BlobView (body + two oval eyes,
    random blink / double blink, highlight), DotsView (typing indicator)
  - Data.kt — Bot / Msg models (Msg also stores m = model, src = search sources, n = note), Store (files/bots.json +
    files/chats/<botId>.json, atomic writes, 3 seeded bots, accuracyRules()), Prefs (mode auto/manual, manual model,
    speed fast/smart, search default + per-chat search_<botId>, accent, onboarded), SecureKey (API key AES-GCM, Android Keystore)
  - Router.kt — Models (verified IDs, labels, thinkingConfig per model), Limits (SharedPreferences "limits": model[|search] ->
    blocked-until + reason), Router.plan() (ordered attempts for one reply)
  - Gemini.kt — v1beta streamGenerateContent?alt=sse over HttpURLConnection, x-goog-api-key header, keep-alive (streams read
    to EOF and closed, disconnect only on Stop), warm() pre-opens the TLS connection when a chat opens, fallback over the
    attempt list, groundingMetadata.groundingChunks -> sources, error classification; test() = GET models/<id> or models?pageSize=1
  - Md.kt — small Markdown renderer (headings, bold, italic, strike, inline code, fenced code, bullets, numbers, quotes),
    tappable [text](url) links and bare http(s) URLs (Md.Link / Md.Touch; long-press still copies)
  - WelcomeActivity (launcher), HomeActivity, EditBotActivity, ChatActivity, SettingsActivity
- res/drawable: Material-style vector icons (ic_*, ms_*), adaptive launcher icon (ic_fg, ic_fg_mono)
- keystore/blobs-release.jks + keystore.properties — RELEASE KEY. Keep it: every update must be signed with it.
- out/Blobs-v1.0.apk, out/Blobs-v1.1.apk — signed release builds (same key, v1.1 installs over v1.0)

## Rebuild (sandbox is aarch64)
Toolchain lives in /tmp/tc (NOT persistent). Re-download if missing:
  - Temurin 17 aarch64: https://api.adoptium.net/v3/binary/latest/17/ga/linux/aarch64/jdk/hotspot/normal/eclipse -> /tmp/tc/jdk-17.*
  - https://dl.google.com/android/repository/platform-35_r02.zip -> /tmp/tc/sdk/platforms/android-35
  - https://dl.google.com/android/repository/build-tools_r35_linux.zip -> /tmp/tc/sdk/build-tools/35.0.0
  - copy aapt aapt2 aidl zipalign dexdump split-select from
    https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip (build-tools/)
    over /tmp/tc/sdk/build-tools/35.0.0 (chmod +x)
gradle.properties sets android.aapt2FromMavenOverride=/tmp/tc/sdk/build-tools/35.0.0/aapt2; local.properties sdk.dir=/tmp/tc/sdk.
Build (shell tool times out at 120 s, so detach; cold build ~95 s, incremental ~20 s; drop --offline on a fresh ~/.gradle):
  (JAVA_HOME=/tmp/tc/jdk-17.0.21+9 setsid nohup ./gradlew assembleRelease --offline --console=plain > /tmp/build.log 2>&1 &); sleep 100; tail /tmp/build.log
Output: app/build/outputs/apk/release/Blobs-v<versionName>.apk -> copy to out/.
Verify: PATH=$JAVA_HOME/bin:$PATH apksigner verify --print-certs out/Blobs-v<ver>.apk ; ANDROID_DATA=/tmp/ad aapt2 dump badging out/Blobs-v<ver>.apk
Bump versionCode/versionName in app/build.gradle.kts for each update.
Release cert SHA-256: 9a3d02cb75068baaea15c6cd3388af8c5553f24982e7867426e363d1b9791df2

## v1.1 changes (Nov 2026)
Model IDs verified on ai.google.dev/gemini-api/docs/models + /pricing + /thinking + /api/generate-content (2026-11-20):
free-tier text models gemini-4-hydrogen, gemini-3.8-flash, gemini-3.6-flash, gemini-3.5-flash-lite, gemini-3.1-flash-lite,
gemini-2.5-flash (deprecated, existing users only; from 2026-12-01 requests route to gemini-3.5-flash-lite),
gemini-2.5-flash-lite (existing users only). Not free: gemini-4-carbon/neon/argon, gemini-3.1-pro-preview.
Free Grounding with Google Search: only gemini-2.5-flash / -flash-lite (500 RPD pooled); "Not available" on free tier for 3.x/4.
- Model mode (Settings > Model): Auto (default) or Manual (7 verified quick picks + custom id).
  Auto chain: 4 Hydrogen -> 3.8 Flash -> 3.6 Flash -> 3.5 Flash-Lite -> 3.1 Flash-Lite -> 2.5 Flash -> 2.5 Flash-Lite.
  Before the first token, these move to the next model and are remembered in Limits:
  429 per-day quota (quotaId contains PerDay) -> until next midnight Pacific; 429 "limit: 0" / 404 / model 403 / 400 unsupported
  -> until midnight Pacific; 429 per-minute -> RetryInfo delay (15 s..10 min); 5xx, empty reply, read timeout -> 2 min.
  Fatal (no fallback): invalid key, key/service 403, region, safety block, network down, other 400.
  Settings > Model limits lists blocked models; tap to clear. Changing the API key clears it.
  Tiny model tag under each reply ("Hydrogen", "3.5 Flash-Lite", "... · Google Search", "... · search unavailable").
- Migration: v1.0 users with no model or gemini-2.5-flash saved start in Auto; another saved model stays Manual. A Manual
  gemini-2.5-flash / gemini-2.5-pro that fails switches Settings to Auto (toast) and the reply continues down the Auto chain.
- Speed (Settings > Speed, default Fast): generationConfig.thinkingConfig = thinkingLevel "low" for gemini-4* / 3.8 / 3.6,
  nothing for 3.x Flash-Lite (default minimal), thinkingBudget 0 for gemini-2.5-flash, nothing for 2.5 Flash-Lite.
  If a model rejects the thinking config (400 mentioning thinking) the same model is retried once without it. Smart = model defaults.
- Google Search: globe button in the chat input bar (per chat, saved), global default in Settings (off by default).
  When on: tools [{google_search:{}}] on gemini-2.5-flash, then gemini-2.5-flash-lite; if both fail it answers without
  search (normal chain) and tags the reply "search unavailable". Sources from groundingChunks[].web {uri,title} show as
  numbered chips under the reply; tap opens, long-press copies.
- Accuracy rules appended to every bot's system prompt (Store.accuracyRules): correct + concise, say when unsure, no invented
  facts/links, reply in the user's language and script (Hinglish -> Hinglish), today's date; plus "use Search" when on.

## Notes / limits
- Not tested on a device or against a real key from the sandbox (no emulator, no key). Request shapes follow the official
  REST reference; the invalid-key error path was checked live. Free daily quotas (e.g. Hydrogen's small RPD) are not
  published on the rate-limits page (only in AI Studio), so the app learns them from 429s instead of hard-coding numbers.
- From 2026-12-01 gemini-2.5-flash routes to 3.5 Flash-Lite, which has no free grounding; search then relies on
  gemini-2.5-flash-lite, and keys that never used 2.5 may get no free search at all (reply is tagged "search unavailable").
- Markdown is a light renderer (no tables, no syntax highlighting).
- No image/file attachments; the + button opens a menu (Edit bot, Clear chat, Copy whole chat).
- Text only; a reply is kept if you press Stop. Chats are stored unencrypted in app-private storage; allowBackup=false.
