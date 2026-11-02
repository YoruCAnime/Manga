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

- App updates (1.1.0+): `core/AppUpdate.kt` reads GitHub `releases/latest` of YoruCAnime/Manga (anonymous API,
  60/h, so automatic checks at most every 3 h; Settings "Check now" forces), downloads the release's .apk asset into
  cache/updates (FileProvider root `updates/`), checks it is our package, opens Android's installer (ACTION_VIEW,
  REQUEST_INSTALL_PACKAGES). UI `ui/AppUpdateUi.kt`: pop-up on the main tabs (never over the reader), "Later" =
  `dismissedAppVersion`, More tab dot, Settings card; UpdateWorker also notifies once per version. A RELEASE is what
  users get: every APK handed over must be published as a GitHub release with notes (they show in the pop-up).
- Search box autocomplete: `BrowseScreen` asks `src.suggestTags(last word)`, `rankTags` = prefix first, then
  `Tag.count` (Danbooru / Gelbooru post counts), dedup by label; nhentai's made-up "typed" entries only in the sheet.
- `ui/PageGrid.kt`: page grid (jump / pick and save). Reader route `reader/{key}/{chapter}?grid=1` opens it.
- Animated booru posts use the ORIGINAL file (samples are still JPEGs); Coil uses ImageDecoderDecoder on phones
  (GifDecoder only under Robolectric). Recommended skips danbooru / gelbooru.
- Tests: `python _dev_tools\run_tests.py AppUpdateTest GifAndTagsTest ScreensTest LiveSourcesTest`. FileProvider can't
  match Windows paths under Robolectric (AppUpdateTest skips just that step on Windows).

- Videos (1.2.0): package `video/` = `VideoSource` (+ `VideoRef` / `VideoDetails` / `VideoStream`), sources
  `HentaigasmSource` (WP REST; the post HTML has the player only for a MOBILE browser agent, pinned in
  `Http.userAgents`; poster CDN answers 429 to a burst: `Http.rateLimits["hgasm1.com/thumbnail"]` + the image
  client's 429 retry), `OppaiStreamSource` (POST search.php HTML; text search ignores the blacklist, so `denied`
  folder ids from tags=Shota/Loli), `MuchoHentaiSource` (HTML; AES HLS key needs POST keyserver /authorize cookies,
  same User-Agent as the key request; playerClient re-authorizes on a key 403). `VideoSafety` (tags + title words)
  on every list, `details()` checks again before playing. `VideoRepo` = watch positions (data/videos.json).
  UI: `ui/VideosScreen.kt` (tab), `ui/PlayerScreen.kt` (Media3 ExoPlayer on a TextureView so Snapshot can read the
  frame; SubtitleView; Compose controls; SENSOR_LANDSCAPE = forced even with auto-rotate locked), `ui/VideoCover.kt`
  (cover -> altCovers -> `SeriesCovers` -> placeholder), `video/ClipSaver.kt` (snapshot PNG; Record = Media3
  Transformer clip -> H.264/AAC MP4 in Movies/MangaCracker). Specs: `_dev_tools\specs\{hentaigasm,oppaistream,muchohentai}.md`.
  Tests: VideoSourcesTest (live lists, details, stream heads, key unlock), CoverSpeedTest (whole grids + fallbacks),
  ImagePipelineTest (every cover through Coil), VideoScreensTest (screens; no video decode on the PC; ExoPlayer
  diagnostics and the TextureView are skipped under Robolectric).

- 1.3.0: `VideoSource.series(ref)` = every video of the series in all languages (hentaigasm: the post's category
  via tag "cat:<id>"; MuchoHentai: /series/<slug> via tag "series:<slug>", cached; oppai: the watch page's
  episodes) -> `ui/VideoSeriesScreen.kt` (route vseries/{key}). Video favourites `VideoRepo.favs`
  (data/video_favs.json); Library shelves Manga / Fav videos / Watched in `LibraryScreen.kt`.

- 1.4.0 (2026-11-02): manga sources `HitomiSource` (data host ltn.gold-usergeneratedcontent.net: .nozomi id lists
  read by Range, galleries/<id>.js, page URLs from gg.js fetched at run time; images need Referer hitomi.la; the
  website is 404 in the UK), `Hentai2ReadSource` (Laravel + Inertia JSON, `X-Inertia` headers, 409 = new version),
  `HentaiFoxSource` (GENERATED from `_dev_tools\probe_hentaifox\HentaiFoxSource.kt.in` + its tag table by
  fill_kotlin.py), `JestfulSource` (raw manga, nsfw=false, in `Safety.MAINSTREAM`), `Hentai20Source` (WP
  MangaReader theme), `Manhwa18NetSource` (Inertia JSON), `Manhwa18CcSource`, `Manga18ClubSource` (pages = base64
  `slides_p_path`). Videos `HentaiMamaSource` (admin-ajax mirrors, r2 MP4 on gdvid.info; minors genre spelled
  "shoutacon", now in both filters), `NsfwR34Source` (StreamTube WP, Bunny HLS), `PorcoreSource` (ajax lists, HLS),
  `SfmCompileSource` (WP REST, MP4), `Rule34VideoSource` (KVS flashvars + license_code unscramble; in the UK the site
  puts an ageverif.com check in front: the source stops with "turn your VPN on" and never reads past it, so it is
  only testable on a VPN). manhwaread.com: dropped (its Cloudflare Turnstile box loops in embedded browsers; Exos said ignore it).
  Specs for all in `_dev_tools\specs\`. Live check of every source incl. every first-page cover through Coil:
  `python _dev_tools\run_tests.py NewSourcesTest -Psources=id1,id2` (no -P = all); screens: `NewScreensTest`
  (never `performScrollToNode` there: it waits for idle and the app animates forever; swipe instead).

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
  `MangaCracker-<version>.apk` in this folder and publish it as a GitHub release
  (`gh release create v<version> MangaCracker-<version>.apk --repo YoruCAnime/Manga`). Exos downloads it on the phone.
