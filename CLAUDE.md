# MangaCracker: notes for Claude

Personal Android manga reader for Exos (Kotlin, Jetpack Compose, OkHttp, Jsoup, Coil 2, kotlinx.serialization).
PUBLIC repo YoruCAnime/Manga, branch main (this folder is the repo in place; commit + push here). Keep everything
personal out of it: no PC paths, cookies, keys or passwords. The private branch `mangacracker` of YoruCAnime/Claudesesh
holds the history from before the move (2026-11-01).
User guide: README.md. Log of past work: the DOA2XPS repo's HISTORY.md (2026-11-01 entry).

## Layout
- `core/`: `Source` interface + models, `Http` (one OkHttp client; cookies shared with the WebView CookieManager;
  per-host / per-path rate limits `Http.rateLimits`, per-host User-Agent `Http.userAgents` (Danbooru, nhentai,
  Gelbooru need an app UA), referers `Http.referers`; Cloudflare 403/503 -> hidden WebView solve, else
  `CloudflareException` -> "Open site" button), `Repo` (JSON files in filesDir/data: library, history, progress,
  updates, settings), `Safety` (minor tags: HARD always, SOFT only on 18+ titles / non-mainstream sources),
  `UpdateWorker` (WorkManager), `Recommender`, `ImageSaver` (MediaStore).
- `sources/`: one object per site. Specs with live samples: `_dev_tools\specs\*.md` (read them before changing a
  source). `ComixBridge` + `assets\cxlib.js`: comix signs / encrypts its API inside an obfuscated VM, so a hidden
  WebView keeps a comix page open and cxlib.js calls the site's own api module (methods learnt from the page's
  TanStack query cache, saved per build in filesDir/comix_methods.json). If comix breaks, see comix.md 2.4
  (plan B = keiyoushi's DOM harvesters in `_dev_tools\probe_comix\out\ext\`).
- `ui/`: AppRoot (nav + bottom bar), Home, Library, Browse (+ FilterSheet), Sources / Global search, Details,
  Reader (LazyColumn strip, pinch zoom on the whole strip, progress saved debounced, next chapter appended,
  pages decoded at most screen-wide x 4096 tall, INEXACT so never upscaled), MoreScreens (Updates, More, History,
  Settings, WebScreen).
- Theme: AniList navy (`ui/theme/Theme.kt`, `Mc.*`).

## Rules / facts learnt
- Chapters are newest first. `chaptersFlow` lets slow sources (comix, 2.5-4.8 s between chapter pages) fill in;
  `latestChapters` = what the update check fetches.
- UK blocks: e-hentai lists / search answer 451 (we show a message; never read the 451 body); nhentai's website is
  451 but its API is not. Don't add VPN / bypass tricks.
- Danbooru: a browser-like UA gets a Cloudflare wall; app UA works (also for cdn.donmai.us). Anonymous 2-tag limit
  (rating: is free). Gelbooru images need Referer gelbooru.com. comix images must have NO Referer.
- No working Android emulator on this PC (Windows Hypervisor Platform off; the user's LDPlayer is theirs, ADB off).
  UI checks run in Robolectric (`ScreensTest`, screenshots in `_dev_tools\out\screens`); the WebView (comix
  search / chapters / pages) can't run there and needs a real device.
- Signing key `keystore\mangacracker.jks` and its passwords in `keystore\keystore.properties` are both git-ignored.
  Keep them; updates need them.
- `_dev_tools\probe_atsu\keiyoushi\` (keiyoushi's Atsumaru extension, Apache-2.0) stays local only: not ours to publish.
- Bump `versionCode` / `versionName` in app/build.gradle.kts for every APK you hand over; name it
  `MangaCracker-<version>.apk` in this folder.
