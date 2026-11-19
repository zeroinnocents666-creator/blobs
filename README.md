# Blobs (com.navid.blobs) — v1.2 (versionCode 3)

"Your team of AI bots": create small bots with a name, role and blob avatar, and chat with them through the Google
Gemini REST API. Plain Views, Kotlin stdlib only (no AndroidX, no third-party libraries). INTERNET permission only
(photos come from the system Photo Picker / camera app / document picker, so no storage or camera permission).

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
  - Attach.kt (v1.2) — Att model (kind image/pdf/text, name, mime, file, size, w/h), Attach (import/copy into
    files/attachments/<botId>/, image downscale + EXIF rotate, text read, size limits, thumbnail LruCache, full-screen
    viewer), ZoomImageView (pinch / pan / double-tap), FilesProvider (AndroidX-free FileProvider: authority
    com.navid.blobs.files, /cam/<name> = cache/camera read-write for ACTION_IMAGE_CAPTURE, /att/<botId>/<file> read-only)
  - ShareActivity (v1.2) — exported share target (SEND image/* application/pdf text/plain; SEND_MULTIPLE image/* pdf):
    bot picker -> copies files into that bot's folder -> opens ChatActivity with extras "staged" (Att JSON) and "text"
  - WelcomeActivity (launcher), HomeActivity, EditBotActivity, ChatActivity, SettingsActivity
- res/drawable: Material-style vector icons (ic_*, ms_*), adaptive launcher icon (ic_fg, ic_fg_mono)
- keystore/blobs-release.jks + keystore.properties — RELEASE KEY. Keep it: every update must be signed with it.
- out/Blobs-v1.0.apk, out/Blobs-v1.1.apk, out/Blobs-v1.2.apk — signed release builds (same key, each installs over the last)

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

## v1.2 changes (Nov 2026): attachments in chat
API shape checked on ai.google.dev (2026-11-20): REST generateContent parts take {"inline_data": {"mime_type", "data"}}
(base64; camelCase inlineData also accepted by the JSON API). Inline data limits the whole request (prompt, system
instruction, inline bytes) to 20 MB; image types png/jpeg/webp/heic/heif; PDFs up to 50 MB / 1000 pages via the Files API
(not used here). The image/document guides now show the Interactions API; this app still uses v1beta generateContent.
- + button opens a bottom sheet: Photo (ACTION_PICK_IMAGES on Android 13+ or 11-12 with SdkExtension R >= 2, else
  ACTION_GET_CONTENT image/*; multiple, up to the 5-per-message slots left), Camera (ACTION_IMAGE_CAPTURE into
  FilesProvider /cam, no CAMERA permission), File (ACTION_OPEN_DOCUMENT: pdf, text/*, json, xml, js, sh, yaml,
  octet-stream for code files, images), Paste image (reads ClipboardManager primary clip image uris). Below a divider:
  Edit bot, Clear chat (also deletes the bot's attachments), Copy whole chat (adds "[image: name]" lines).
- Paste into the text field: OnReceiveContentListener for image/* on Android 12+ (clipboard paste and keyboard
  GIF/sticker/image commits); bytes are copied to cache/paste immediately, then imported. Text paste is untouched.
  ContentInfo.partition is not in the public SDK, so the listener splits the clip by hand and returns the non-image rest.
- Share to Blobs from the gallery / any app -> pick a bot -> chat opens with the files staged (and shared text in the box).
- Staged attachments: removable chips above the input bar (64dp rounded image thumbnails; PDF/code/file icon + name +
  type + size). Spinner chips while copying. Send works with text, attachments, or both. Staged files survive rotation
  (onSaveInstanceState); unsent files are swept from the folder after 24 h when the chat opens.
- Import rules (Attach.import): images -> max 1600 px long side, EXIF rotation applied, JPEG q85; PNG kept as PNG when
  it re-encodes to <= 1 MB (sharp screenshots); small png/jpeg/webp (<= 1600 px, <= 1.5 MB, no rotation) copied as-is.
  PDFs copied raw (<= 14 MB). Text/code: first 200 KB as UTF-8 (cut at a character boundary, name gets
  "(first 200 KB)"), files with NUL bytes rejected as binary. Anything else: friendly "can't be sent" toast.
  Max 5 attachments and 14 MB raw per message (base64 adds a third, staying under the 20 MB request cap).
- Storage: files/attachments/<botId>/<random>.<ext>; Msg JSON key "a" = [{k,n,mt,f,s,w,h}]. Delete bot / clear chat /
  Settings > clear all chats delete the files too.
- Request (Gemini.contents/writeBody): user turn parts = text (if any), then per attachment: inline_data for images/PDFs,
  or a text part "File: name + fenced content" for text files. Only the newest 3 user turns that have attachments (and
  14 MB together) are sent in full; older ones become "[image: name]" / "[file: name]". If the newest message alone is
  over 14 MB the reply fails fast with a friendly message. The JSON is written with placeholder tokens that are replaced
  by base64 streamed straight from disk (Base64OutputStream, fixed-length streaming), so no 20 MB strings in memory.
- Fallback: a 400 that mentions mime/image/inline/media/pdf/document/modality on a request with media = BAD_MEDIA: the
  next model is tried, nothing is written to Limits (text chats on that model are unaffected), no Manual->Auto
  migration. 413 / "payload size" = fatal "too big" message. Search mode: if the 2.5 search models reject the files,
  the reply comes from the normal chain without search, tagged "no search with files".
- Chat bubbles: user messages show one large rounded image (aspect kept, max 220x280dp) or a grid of 86dp squares, plus
  file chips; tap an image = full-screen viewer (pinch-zoom, pan, double-tap, tap to close); tap a file chip = open it
  in another app via FilesProvider /att (read-only grant). Long-press on the text still copies. Home list preview shows
  "Photo" / "N photos" / file name for attachment-only messages.
- New vector icons: ic_image, ic_camera, ic_file, ic_paste, ic_pdf, ic_code (Material shapes, no emoji).

## Notes / limits
- Not tested on a device or against a real key from the sandbox (no emulator, no key). Request shapes follow the official
  REST reference; the invalid-key error path was checked live. Free daily quotas (e.g. Hydrogen's small RPD) are not
  published on the rate-limits page (only in AI Studio), so the app learns them from 429s instead of hard-coding numbers.
- From 2026-12-01 gemini-2.5-flash routes to 3.5 Flash-Lite, which has no free grounding; search then relies on
  gemini-2.5-flash-lite, and keys that never used 2.5 may get no free search at all (reply is tagged "search unavailable").
- Markdown is a light renderer (no tables, no syntax highlighting).
- Attachments: no video/audio, no Office files (docx/xlsx/pptx), no Files API upload (so PDFs > 14 MB are refused);
  Manual mode has a single model, so if it can't read a file the reply fails with that model's message.
  The paste-into-field path needs Android 12+; older phones use "Paste image" in the + sheet.
  Not tested on a device: picker / camera / share / paste flows follow the platform docs only.
- A reply is kept if you press Stop. Chats are stored unencrypted in app-private storage; allowBackup=false.
