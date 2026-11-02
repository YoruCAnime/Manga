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

- 1.5.0 (2026-11-02): Settings › Hidden tags › "Hide AI-generated" (`Settings.hideAi`, on by default -> `Safety.hideAi`).
  READING ONLY (manga / pictures); Exos said not videos, so `VideoSafety` is untouched. `Safety.isAiTitle` = "AI
  generated" in any case / spacing, "generated by AI", capital "AI" in brackets ("[AI]", "(AI Art)"), "AI生成"; a bare
  "Ai" (a name) never counts. `Safety.isAiTag` = AI_TAGS ("ai generated", "ai art", "stable diffusion", "novelai"...).
  Lists that only carry tag ids get the name added: nhentai id 145703, HentaiFox 3298 (also in the .kt.in template);
  atsu (no tags in lists) adds its "AI Generated" tag 1061 to `excludedTags` while the switch is on. hitomi's AI tag
  list holds 1 gallery (titles do the work there). AI tags leave the tag pickers while on (`cleanTags`). Live check:
  `run_tests.py AiFilterTest` (searching each site's AI tag with the switch on shows 0 cards on all of them);
  `AiSettingShotTest` = the switch (shots 42 / 43). Id probe: `_dev_tools\probe_ai\ai_ids.py`.
- 1.5.1: E-Hentai / ExHentai add `-other:"ai generated$"` to the search guard while the switch is on (Compact rows show
  only some tags; untestable here: lists are 451 in the UK). Recommender: AI tags never become taste, "more like this"
  skips AI titles read, `cached()` filters again. `RecommendAiTest` (4 AI favourites on nhentai -> 41 recs, 0 AI).

- 1.6.1 (2026-11-02, Exos: "make play and seek be in middle for video player and default highest quality also have
  separate volume slider for this app only"): big play / pause with -1s / +1s and prev / next in the MIDDLE of the
  screen (this replaces the "nothing in the middle" layout below); the seek bar stays full width at the bottom. App
  volume = `player.volume` (`ui/SeekBar.kt` VolumeBar, white, saved on release in `Settings.videoVolume` /
  `videoMuted`; the phone's media volume is never touched; portrait hides the %). Quality: `Settings.videoQuality`
  -1 = Best (`setForceHighestSupportedBitrate`), 0 = Auto, else a max height; picked in the HQ menu and remembered.
  Test: `GifScreensTest.volumeAndQuality` (shots 59 / 59b / 59c).
- Player controls + GIF maker (2026-11-02, not released yet). Controls: nothing in the middle of the picture any more;
  bottom = a button row (prev / -1s / +1s / next ... Snapshot, Record, GIF, speed, Rotate; labels hidden under 560 dp)
  and play / pause beside the seek bar. `ui/SeekBar.kt`: 48 dp touch strip, slim track, touch anywhere = jump + drag,
  thumb grows, time bubble; while dragging the player is paused and seeks CLOSEST_SYNC every 120 ms, release = EXACT
  seek (+ play again if it was playing). Controls / panel use `safePad` (safeDrawing: cutout + bars). In-window notes
  (`toast()` in PlayerScreen) for the GIF flow, no system toasts there.
  GIF: `ui/GifPanel.kt` (GifEdit state, range bar, thumbs from `TextureView.getBitmap`, speed / ping-pong / size chips,
  progress, result with Coil + Save / Share / Change); capture = `gifStart` (30 s cap), range max 10 s.
  `video/GifClip.kt`: `ClipSaver.cut(forGif = true)` (Transformer, video only, <= 720p via Presentation, 16 Mb/s,
  key frame every 0.5 s, same playerClient + beforePlay) -> MediaMetadataRetriever.getFramesAtIndex in chunks of 8
  (60 fps halved) -> Lanczos to 640 long side -> `gif/FileFrames` (cache file, 3 B/px) -> `GifMaker` -> cache/gif;
  `ClipSaver.saveGif` (Pictures/MangaCracker/GIFs), `shareGif` (FileProvider share/). Temp files go on close.
  Pure Kotlin in `gif/` (JVM-testable): `GifWriter` (GIF89a, LZW, loop), `Palette` (5-bit histogram, variance split +
  k-means, 7-bit lazy LUT; plain RGB beat OKLab by 1-5 dB here), `GifMaker` (timeline: fast = even frame picks,
  slow = in-betweens between KEY frames (anime holds deduped, so "on twos" slows smoothly); ping-pong without repeated
  ends; delays by cumulative rounding (30 fps = 3,4,3 cs); pattern dither only between two CLOSE palette colours
  (flat areas and edges untouched, no shimmer); `FrameWriter`: changed-rect crop, unchanged (within threshold 6, then
  10) = transparent, no-change frames merged; auto-fit: threshold 10 -> 160 colours -> long side to 3/4 -> fps to 15 ->
  smaller -> 10 fps; a threshold-only retry reuses the quantized frames), `Motion` (hierarchical block matching
  4 px cells / 8 px blocks on a 3-level pyramid of a ~240 px luma plane, smoothness bias, median filter, sub-pixel;
  both directions; per pixel the better of the 2 vectors, Catmull-Rom warp, blend ONLY where the moved frames agree,
  else the nearer frame's moved pixel; > 30 % mismatch or a cut (block error / luma histogram) = nearest real frame),
  `Resample` (Lanczos-3), `Par` (row bands on <= 4 low-priority threads).
  Tests: `GifEngineTest` (writer vs ffmpeg decode, timing, in-betweens vs real frames, dither / threshold sweep,
  vs ffmpeg palettegen recipes, speed, make every size), frames from `GifSampleTest -Psources=gifsample` +
  `_dev_tools\gif_frames.py`; `GifScreensTest` (landscape shots 50-58, PlayerTestHooks = fake clock / GIF job).
  Measured (PC, 480 px): our GIF vs ffmpeg's best (palettegen diff + paletteuse dither=none): anime talk 2.59 MB /
  40.2 dB vs 2.91 MB / 38.6 dB, anime action 6.12 / 39.4 vs 7.16 / 39.0, 3D 3.22 / 40.7 vs 5.83 / 42.8 (gradient
  dither adds ~20 % and removes visible banding). In-betweens vs the dropped real frame: pan 33.4 dB (blend 25.1),
  3D 41.3 (blend 33.7), testsrc 25.7 (24.9); anime with random speed lines 21.0 (blend 22.0, but sharp, no ghosts).
  Speed (PC JVM, 1 thread, 480 x 270): resize 7 ms, quantize 2, LZW 2, flow both ways 11, warp 15 per in-between;
  whole make (2 threads): 1x Small 0.5-3 s, 0.5x Small 5-16 s, 0.25x Small 15-29 s (guess a phone at 2-4x that).
  Not checked without a phone: Transformer cut + MMR decoding of real streams, MediaStore save, share, real speed,
  the Coil preview of the finished GIF (black under Robolectric).

- Backup, read badges, reader extras, source test (2026-11-02, not released yet).
  Backup: `core/Backup.kt` = one JSON file `{"app":"MangaCracker backup","format":1,created,appVersion,library,history,
  progress,settings,videoWatch,videoFavs}`. Logins / keys / update bookkeeping are never written and always stay the
  phone's (`withDeviceOnly`). `read()` refuses non-JSON / other apps / format < 1 (BackupException, message shown),
  reads newer formats (unknown keys ignored, "made by a newer app" note), decodes entry by entry (a broken one is
  skipped and counted). `apply(MERGE)` = newer wins per key (library by max(added, lastChecked, lastUpdate), history /
  progress / watch by time; favs union; hidden tags joined; other settings from the file), REPLACE = the file's data.
  `Repo.restore` / `VideoRepo.restore` put it in place. Writes: MediaStore Download/MangaCracker (`saveToDownloads`),
  SAF CreateDocument / OpenDocument in `ui/BackupUi.kt` (Settings card + in-window restore preview, Replace needs a
  second tap, no dialogs). Weekly: `Settings.autoBackup` (off), `autoIfDue` from App start and UpdateWorker, keeps 4
  `MangaCracker-auto-*` files. Test seams: `Backup.folderOverride`, `BackupTestHooks.openText`.
  Read badges: `core/ReadMarks.kt` (combine of library / history / progress -> map key -> ReadMark(read, total,
  percent, finished); total = max(library chapterCount, `HistoryEntry.chapterCount` (new field, the reader passes
  `vm.chapters.size` to `saveRead`), chaptersCache); galleries (`hasChapters=false`) = 1). `MangaCard` draws the pill
  ("✓ Read" / "Ch. 12/40" / "Reading") + bar for every caller (`readMark=false` to opt out). `Settings.hideFinished`
  filters BrowseScreen (load-more keyed on the raw item count so hidden pages keep loading) and `CardRow` (Home rows,
  Recommended, global search, More like this); Library / History unchanged.
  Reader (`ui/ReaderScreen.kt`): the LazyColumn now shows `rows` (`readerRows`: one item per line, or in the two-page
  view each chapter's pages paired by `ui/Spreads.kt` `spreads()`: first page alone, wide (> 1.05) alone, a page
  before a wide one alone; `screenOrder` = RTL). All index work (start spot, slider, page grid, long-press, preload,
  current page) goes through rows; `rows` is ONE remembered derivedStateOf reading `rememberUpdatedState(twoPage)`
  (effects / gestures started once must see the current lines). `vm.ratios` is a snapshot map so pairs re-form when
  a page turns out wide. Two-page only when `readerTwoPage` and the window is wider than tall; lines fit the screen
  height (`SpreadRow`). Auto-scroll: `withFrameNanos` loop + `dispatchRawDelta` (60 dp/s x `autoScrollSpeed` / zoom),
  held while a finger is down (+1.2 s), a fling, the grid / sheet / page menu; waits for an appended chapter, stops at
  the real end. Progress saving switched from debounce(350) to sample(400) (auto-scroll never stands still).
  Night filter = a dim + warm layer drawn over the list (`readerNight/readerDim/readerWarm`), never system brightness.
  Source test: `core/SourceCheck.kt` (latest page -> first covers through Coil with caches off -> details; videos the
  same; 45 s timeout, > 8 s = slow; message with "VPN" or a 451 = VPN, CloudflareException = browser check) +
  `ui/SourceTestScreen.kt` (route `sourcetest`, from More and Settings › Sources "Test all"; 2 at a time; Cancel).
  `SourceCheck.unavailable` marks sources that can't run here (tests: comix).
  Tests: `BackupTest` (round trip, merge, bad / old / newer files, weekly prune), `ReaderLogicTest` (pairs, RTL, rows
  per chapter, read marks incl. '#' in chapter ids, hide finished), `FeatureScreensTest` (shots 60-70, 73-75; the
  live source check), `TwoPageShotTest` (landscape shots 71, 72, 72b). Live check 2026-11-02 (PC, UK): 19 OK, E-Hentai
  and Rule34Video "needs VPN", comix skipped (no WebView in Robolectric); slowest SFM Compile 5.8 s, HentaiMama 5.0 s.
  Harness gotcha: Coil's cross-fade freezes part way under Robolectric, so freshly loaded pages can look black / dim in
  shots (TwoPageShotTest re-draws from the memory cache). Not checked without a phone: SAF pickers, MediaStore
  Downloads write / prune, real auto-scroll smoothness and speed, rotation into the two-page view.

- Other sites + offline downloads (2026-11-02, not released yet).
  Other sites: `core/SourceMatch.kt` (pure): `similarity` = half word Dice + half edit distance on normalised titles
  (accents, punctuation, noise words), also without [..] / (..) parts; a 2+ word prefix scores >= 0.8 minus 0.05 per
  extra word (so a sequel ranks under the same title). `number(chapter)` = Chapter.number, else "Ch. / Chapter /
  Episode / # N(.5)" or a bare number; `numberFromId` for slugs ("chapter-10-5"); no number ("Extra") = same name only.
  `carry()` = read marks by number (duplicate uploads all marked), galleries one to one, the history chapter by number
  with page scaled by the page counts; counts `unmatched`. Old chapters come from chaptersCache / the download's
  title.json / progress keys (when the old site is broken the IDs are all there is). `Repo.moveTitle` (keeps `added`,
  old cover when the new one is unknown, a mark the new title already had wins when further on) returns `MoveUndo`
  (old values of both keys) -> `Repo.undoMove`. The app has no categories: library = favourites, so "favourite
  state" = staying in the library. UI `ui/OtherSitesScreen.kt` (route `othersites/{key}`, `OtherSitesVM`: every
  enabled manga site except boorus, 4 at a time, title then alt titles (Latin first) while nothing scores >= 0.8,
  keeps >= 0.5, top 3, chapter lists of the best 2 >= 0.6; per-site state via `SourceCheck.classify`: Needs VPN /
  Browser check / Not working / Skipped (`SourceCheck.unavailable`)). Details: "Other sites" chip, orange "Find on
  other sites" card when the site is switched off / failing (not when simply offline), `GoneSourcePage` when the
  source id no longer exists. In-window notes with an action: `ui/Notes.kt` (`Notes.show(text, "Undo") {}`, drawn by
  `NoteHost` in AppRoot above the bottom bar); Undo also goes back to the old title's page.
  Downloads: `core/DownloadQueue.kt` (pure rules: FIFO, one task per site, PAUSED / FAILED stay, failed = retry after
  15 s / 1 min / 4 min then FAILED, permanent errors at once; `load` turns RUNNING into QUEUED), `core/Downloads.kt`
  (store + index + actions; files in `noBackupFilesDir/downloads` so Android's cloud backup never tries to copy them:
  manga/<site>/<title>/title.json (ref, details, whole chapter list) + cover.* + <chapter>/0001.jpg.. + chapter.json
  written LAST (no chapter.json = not downloaded); folder names = readable part + CRC32 (`safe`); the queue in
  data/downloads.json), `core/DownloadWorker.kt` (`DownloadWorker` = WorkManager, unique work "downloads",
  APPEND_OR_REPLACE, UNMETERED when `Settings.downloadWifiOnly` (default on; switching it REPLACEs), foreground
  dataSync notification, channel "downloads"; `DownloadRunner.run` = 2 tasks at once from different sites, pages 2 at a
  time through `Http.imageClient` + `Http.request` (the reader's Coil request: same cookies / UA / Referer rules, comix
  without Referer), page addresses resolved at download time (`src.pages` / `imageUrl`: hitomi's gg.js), a page that
  isn't a picture (magic bytes) fails the try; pages already on disk are kept on a retry; free space < 300 MB = refuse
  to queue, < 150 MB = the runner pauses everything (`lowSpace`)). Reader: `ReaderVM.pagesOf` = `Downloads.localPages`
  ("file:" + path, Coil gets a File via `Downloads.model`; also PageGrid / preload / Http.bytes for save + share) else
  the site; a downloaded start chapter opens with the saved chapter list and asks the site in the background.
  Details offline: saved chapters + details, "Offline" hint. "Delete chapters after reading" (off) deletes the read
  downloaded chapters of the visit when the reader closes. Markers: green ↓ on chapter rows / "↓ 12/40" / "↓ paused",
  ↓ badge on `MangaCard`. Videos: `video/VideoDownloads.kt`: MP4 / WebM = the stream file via `src.playerClient` with
  Range resume (first bytes must be ftyp / EBML); HLS = our own master parse (`pickVariant`: tallest <=
  `Settings.videoDownloadHeight` (720 default; 0 = best) + its default audio rendition) then Media3 `HlsDownloader`
  into a SimpleCache of the video's own (deprecated no-database constructor; one instance per folder, kept in a map,
  released on delete) with `OkHttpDataSource(src.playerClient)`; the key files are segments too, so MuchoHentai's
  AES streams play from the cache. `VideoStream.local` (video key) + `streamKeys`; `dataSourceFactory` (player,
  ClipSaver.cut -> Record / GIF) = file / read-only cache / network; PlayerVM plays a download first (episode list
  from the site in the background); GifClip skips `beforePlay` for downloads. UI: `ui/DownloadsScreen.kt` (route
  `downloads`, More row, notification opens it), player ↓ button. Safety: `Safety.blocked` / `VideoSafety` at enqueue
  and again in the worker (details checked before any file). Test seams: `Downloads.autoStart` (no WorkManager),
  `Downloads.freeOverride`, `Http.blockNetwork` (every request fails as offline), `VideoDownloads.testByteCap`.
  Also: Library "Clear watched" = an in-window Keep / Clear row (no AlertDialog); CI runs BackupTest, ReaderLogicTest,
  SourceMoveTest, DownloadLogicTest offline.
  Tests: `SourceMoveTest` (7: similarity, numbers, carry incl. 10.5 / missing / Extra / IDs only / galleries, move +
  undo, move onto a kept title), `DownloadLogicTest` (6: queue rules + backoff, disk layout + offline lookup + sizes +
  delete, enqueue rules incl. minors / low space / next 5, runner pause on a full disk, picture sniffing, HLS variant
  pick). Live `DownloadLiveTest`: nhentai gallery (4 p), atsu chapter 1 (9 p), manhwa18cc chapter (60 p) through the
  real queue in 4-13 s, then ReaderVM + Coil decode every page with the network blocked; Other sites for a manhwa18cc
  title: Hentai20 + Manga18.club 100 % (112 ch), others "Not found", E-Hentai "Needs VPN", comix skipped; videos:
  SFM Compile MP4 3 MB (ftyp, moov, mdat; read offline), Porcore HLS 720p (capped 24 MB, first segment MPEG-TS from
  the cache offline), MuchoHentai HLS 720p (key from the cache, first segment AES-128 decrypted -> MPEG-TS offline).
  NSFW R34's Bunny HLS answered 403 to everything on 2026-11-02 (playback too, with or without Referer): source issue.
  `DownloadScreensTest` shots 80-89 (picks a manhwa18cc title with no school / young words, grown-up stories first).
  Harness: the offline reader shot reopens the chapter so pages come from Coil's memory cache (cross-fade freeze).
  Not checked without a phone: WorkManager scheduling, the Wi-Fi constraint, the foreground notification and
  Android 14 background limits, real offline playback (ExoPlayer from file / cache), Transformer cuts of downloads.
  Regression after it: ScreensTest, NewScreensTest, FeatureScreensTest, GifScreensTest, VideoScreensTest, BackupTest,
  ReaderLogicTest, GifAndTagsTest, AiFilterTest (nhentai) all pass; assembleRelease (R8) OK.

- 1.7.0 (2026-11-02):
  - **Character cutout** (`cutout/`): reader long-press → "Save a cutout of this character" → `ui/CutoutScreen.kt`
    (Dialog). Pipeline `Cutter`: `Panels.find` (XY cut along flat white / black gutter rows and columns, keeps the
    piece under the tap; strips without gutters → a 2x-width window), anime-seg isnetis (1024 letterbox) = clean
    figure outlines, the part under the tap (or nearest within 8 % of the diagonal; a missed tap = that part as it is),
    MobileSAM (encoder once, decoder per tap) picks WHICH figure: its last ("whole") mask if >= 70 % on the figure,
    else its confident masks (score >= best - 0.05) on the figure, biggest; IoU >= 0.85 → the anime-seg part alone,
    else part x (SAM mask grown by 1.5 % of the diagonal). `Bubbles.clean`: paper-white (grey >= 225, chroma < 14)
    round-ish blobs (>= 80 % of hull) with >= 8 letter marks inside are erased with their outline, pieces < 4 % of
    the biggest dropped. Add tap = SAM point + the anime-seg part under it (+ SAM's own pixels for props); Remove tap
    on a light round-ish blob erases it whole, else a SAM negative point. Models (`CutoutModels`, 220 MB, pinned HF
    revisions + SHA-256, resumable) in filesDir/cutout. ONNX Runtime 1.28.0, arm64-v8a only, native libs compressed
    (`jniLibs.useLegacyPackaging`): APK 13.5 MB. Study: ~20 pages (B&W doujin, full colour, manhwa) with SAM 2.1
    tiny / small, MobileSAM and anime-seg compared side by side; SAM alone fails on B&W line art, anime-seg alone
    can't split touching figures, the mix wins. Busy full-bleed B&W pages (tone backgrounds, no gutters) and
    silhouettes stay hard: the Add / Remove taps cover them.
  - Tests: `CutoutTest.rules` (offline, CI) and, with `-PcutoutData=<folder>` (parity/ cases from the study +
    app_models/), `CutoutTest.models` (the app's pipeline with the real models on the PC via the desktop ONNX Runtime
    jar: IoU >= 0.98 with the study on 5 pages, ~11 s one thread) and `CutoutScreenTest` (shots 60-63).
  - **ExHentai sign-in** (`core/ExLogin.kt`, `ui/ExLoginScreen.kt`, route `exlogin`): exhentai.org without login
    cookies bounces through forums.e-hentai.org/remoteapi.php (Cloudflare-checked), which the old flow hit; now the
    forum login is copied onto .exhentai.org (a year) before every ExHentai request, the sign-in page opens
    exhentai.org in the same WebView after the forum login and reads the answer (list = OK and the switch turns on;
    empty / igneous=mystery = no access; 451 = VPN). Or paste ipb_member_id / ipb_pass_hash / igneous. A login in
    the phone's browser never reaches the app (separate cookie stores); the Settings card says so.
  - Reader long-press undoes the strip zoom before finding the page (zoomed in, it used to pick the wrong page) and
    records where on the page the finger was (two-page spreads included).

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
