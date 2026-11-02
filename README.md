# MangaCracker

A personal Android manga reader in the spirit of Tachiyomi / Mihon, with an AniList-style dark look.
A personal project, shared as is. Not affiliated with any of the sites below.

## Download

**[Get the latest APK from Releases](https://github.com/YoruCAnime/Manga/releases/latest)** (Android 10 or newer).
From 1.1.0 on the app updates itself: when a new release is out it shows an "Update ready" pop-up (Update now /
Later); Later keeps it under Settings → App updates.

## What it does

- **Home**: Continue reading (page and % of where you stopped), Updates for you (new chapters of your library),
  Recommended for you (built from the genres you favourite and read, plus "more like this" of what you read last),
  and a Popular row for every source. Pull down to check for new chapters.
- **Library** has three shelves: **Manga**, **Fav videos** (♥ in the player, or long-press any video) and
  **Watched** (every video you started, newest first, with % and time left; tap to resume, ✕ to remove, the bin
  clears the list after a Clear / Keep row at the top). **Manga**: your favourites (tap ♥ on a title). Search by title / author / genre, sort, filter by source,
  "N new" badges, a reading bar on each cover. Long-press a cover to jump straight back into it.
- **Browse**: every source, Popular / Latest, search, and a filter sheet: sort, type, status, author / artist, and
  genres / tags (tap once to include ✓, twice to exclude ✕, as many as you like). "Search every source" at the top.
  Typing in the search box suggests tags (e.g. "smi" → smile, smirk), most used first, with their kind (artist,
  character, series); tap one to add it as a filter, or just keep typing.
- **Title page**: blurred cover banner, authors and artists (tap one to see their other works), genre pills (tap
  to search that genre), description, chapter list with read marks and %, a big Continue button, More like this.
- **Reader**: one smooth vertical strip (no page turns). It remembers the exact page when you leave and opens
  there again, flows straight into the next chapter, preloads the next pages, pinch or double-tap to zoom, tap for
  the bar (page n of N · %, a page slider, previous / next chapter, save the whole chapter, reader settings:
  background, page gap, side margin). Long-press a page to save or share it (Pictures/MangaCracker/<title>).
  The grid button shows every page: tap one to jump there, hold to pick several and save them at once (galleries
  have the same Gallery button on their title page). Animated GIFs play (Danbooru / Gelbooru animated posts load the
  original moving file).
  - **Auto-scroll**: ▶ in the bottom bar starts it. The strip glides on by itself (smoothly, not in jumps); a small
    − / speed / + / ⏸ pill sits at the bottom right while it runs, and the bar has a speed slider (0.25× to 5×), so
    you can change the speed without stopping. Touching the screen pauses it; it carries on a moment after you let
    go. It flows into the next chapter and stops at the very end.
  - **Night filter** (moon button, or reader settings): darkens the pages and/or gives them a warm tint, each with
    its own slider, remembered. It never touches the phone's own brightness.
  - **Two pages side by side** (reader settings, for doujins / galleries): with the phone turned sideways, pages show
    in pairs like a book, fitted to the screen height. The cover and wide two-page spreads stay on their own.
    **Right to left** (on by default: Japanese order, page 2 on the right) can be switched off. Pinch zoom, the page
    slider, saving your place and the next chapter all still work.
- **Read badges**: covers everywhere (Browse, Home rows, search, Library, Recommended) show how far you are:
  "Ch. 12/40" with a thin bar, "Reading", or a green "✓ Read" once every chapter is read (a gallery: read to the
  end). **Hide titles I've finished** (Settings → Library, off by default) takes finished titles out of Browse, Home
  and search; Library and History keep them.
- **Videos** (own tab): Hentaigasm, Oppai.stream, MuchoHentai, HentaiMama, NSFW R34, Porcore, SFM Compile and Rule34Video. Latest / Popular, search with genre autocomplete,
  a genre sheet (include / exclude), Continue watching (resumes where you stopped). Tap a **series name ›** (on a
  card, the player's title or in Watched) for the series page: every episode in every language (Raw / English /
  Español / previews) with language filters. Long-press a video to add it to your favourites. covers that fall back to other
  sizes / the same series when a site refuses a picture. The player opens in landscape (forced: works with
  auto-rotate locked; Rotate switches to portrait and back), controls hide after 3 s, tap to show them. All the
  big play / pause sits in the middle of the picture between −1s / +1s and previous / next episode; the seek bar
  runs along the bottom with the app's own **volume** above it on the left (separate from the phone's volume, it is
  remembered; tap the speaker to mute) and Snapshot, Record, GIF, speed and Rotate on the right (all clear of the
  camera notch). The seek bar is easy to hit: touch anywhere on it to jump there and keep sliding; while you slide
  the dot grows, a time bubble rides above your finger and the picture follows (it plays on from there when you
  let go). Double-tap the left / right side to step 1 s (taps stack), double-tap the middle to pause, hold
  anywhere for 2×, speeds 0.25× to 3×, previous / next episode of the same series (and "Up next" at the end),
  subtitles on / off, quality for streams with several sizes (**Best** by default, or Auto / 1080p / 720p / 480p; your pick is remembered), **Snapshot** (the current frame to Pictures/MangaCracker/Snapshots) and
  **Record** (tap to start, tap to stop: that part is saved as an MP4 clip to Movies/MangaCracker).
- **GIF maker** (the GIF button in the player): tap GIF to start capturing (red dot + timer, up to 30 s), tap again
  to stop. A panel opens at the bottom of the player (no pop-up): drag the two handles to pick the part you want
  (up to 10 s; the video behind shows the frame under the handle, small pictures show the start and the end), pick
  a speed (0.25× to 2×: slow motion makes real in-between frames from the motion, so it stays smooth and sharp, no
  blurry cross-fades; fast skips frames), **Ping-pong** (plays forward then back, looping) and a size (**Tiny** up
  to 2 MB, **Small** up to 5 MB, the default, **Big** up to 10 MB), then **Make GIF**. It shows its progress, then
  the GIF playing with its size in MB, picture size and length: **Save** (Pictures/MangaCracker/GIFs), **Share** or
  **Change** to try other settings. The GIF always fits the size you picked: if it would be bigger the app makes it
  a bit smaller or lowers the frame rate, keeping it as sharp as it can. Long slow-motion GIFs come out smaller /
  choppier, so pick a short part for 0.25×.
- **Updates**: new chapters grouped by day; checked in the background (every 6 h by default, Settings) with a
  notification.
- **Settings**: app updates (check now, install, what's new, automatic check on / off), update interval, covers per
  row, Hide titles I've finished, reader options, downloads (Wi-Fi only, delete after reading, video size), switch sources on / off, ExHentai sign-in, Danbooru key, hidden
  tags, Hide AI-generated, backup & restore, clear the picture cache.
- **Backup & restore** (Settings): **Back up now** writes one file, `MangaCracker-backup-<date>.json`, to
  Download/MangaCracker; **Save to…** lets you pick the place (Drive, a folder...). It holds your library, history,
  reading progress, settings, favourite videos and video resume points; logins and API keys are never in it.
  **Restore…** opens a backup and first shows what's inside (how many titles, chapters, videos, settings), right in
  the settings page. **Merge** adds it to what's on the phone (where both have the same title, chapter or video, the
  newer one wins); **Replace** swaps the phone's reading for the backup's (tap twice to be sure). Files from older or
  newer app versions open too; damaged entries are skipped and counted. **Weekly backup** (off by default) puts a
  file in the same folder once a week and keeps the last 4.
- **Test sources** (More, or Settings → Sources → Test all): checks every switched-on manga source and every video
  source, two at a time so no site is hammered: the latest page loads, the first cover answers, one title / video
  page opens. Each gets a row: OK, Slow, **Needs VPN** (UK blocks and age checks, e.g. E-Hentai, Rule34Video), Browser
  check (Cloudflare: open the site once from Browse) or Broken, with a short reason and the time it took. Cancel
  stops it.
- **Recommended** uses the manga / doujin sources only (no image boards).
- **Other sites** (button on a title page): the title (and its other names) is looked up on every other switched-on
  manga site. Each site gets a box: the matches with cover, chapter count, latest chapter and how close the title is
  ("100% match"), or why there is nothing ("Not found", **Needs VPN**, "Browser check", "Not working"). Tap a match to
  open it there. **Move here** (for titles in your library or history) moves your library entry, history and read
  chapters to that site: chapters are matched by number (chapter 12 read here = chapter 12 read there, 10.5 stays
  10.5, "Extra" chapters only by the same name), the chapter you were in carries over at the same place, and the
  library keeps the date you added it. A note at the bottom says how many chapters were marked read (and how many
  that site doesn't have), with **Undo** for a few seconds. When a library title's site is switched off, not working
  or gone from the app, its page shows **Find on other sites** at the top.
- **Downloads** (read and watch with no connection):
  - Manga: on a title page **Download** → *Next 5 chapters* (from where you are), *All unread* or *All chapters*;
    a gallery has one Download button; long-press a chapter → *Download* for just that one. Chapters with a green ↓
    are on the phone, a chapter in the queue shows its pages ("↓ 12/40"), covers in grids get a small ↓ badge. The
    reader opens downloaded chapters from the phone (no data used, works offline) and the rest from the site as
    before; with no connection the title page shows the saved chapters.
  - Videos: the ↓ button at the top of the player. MP4 videos are saved as the file; HLS streams (MuchoHentai,
    Porcore...) are saved in the size picked in Settings → Downloads (720p by default). Downloaded videos play from
    the phone in the same player (Snapshot, Record and GIF still work).
  - **More → Downloads**: space used and free, the queue (pause / resume / cancel each one, pause or resume all;
    failed ones say why and retry with one tap), downloaded titles with size (tap to see the chapters, ✕ deletes a
    chapter, the bin deletes the whole title: tap it twice), downloaded videos (tap to play, bin to delete).
    **Wi-Fi only** (on by default: the queue waits for Wi-Fi) and **Delete chapters after reading** (off) are there and
    in Settings → Downloads. A notification shows the progress while it downloads.
  - Downloads stay inside the app (not in your gallery). Nothing starts when the phone is nearly full (it says how
    much space is left) and a running queue pauses itself. Titles and videos hidden by the safety filter are never
    downloaded. A failed page or file is tried again a few times, waiting longer each time.

## Sources

| source | notes |
|---|---|
| atsu.moe (Atsumaru) | JSON API + its search engine; all filters, author pages, recommendations |
| comix.to | details / home rows over plain HTTP; search, chapter lists and pages run the site's own scripts in a hidden web view (comix encrypts them). Chapter lists load in steps a few seconds apart (the site blocks fast paging) |
| nhentai | v2 JSON API (the website is blocked in the UK, the API is not). Low limits: searches are spaced ~6 s apart |
| E-Hentai / ExHentai | lists and search are blocked in the UK (Online Safety Act) and work on a VPN; galleries open by link (paste a gallery link into the search box) and from the library. ExHentai: sign in under Settings, then switch it on |
| Danbooru | pictures and comic pools; 2 tags per search without a login (add your key in Settings for more) |
| Gelbooru | pictures through its web pages (no key needed) |
| hitomi.la | its data server (the website itself answers "not found" in the UK; its gallery files and pictures do not); lists, popular today / week / month / year, word and tag search (include / exclude), tag suggestions with counts |
| Hentai2Read | its search (tags include / exclude, type, status, sort); chapters |
| HentaiFox | galleries; search with tags (include / exclude) and language |
| Jestful (JF) | Japanese raw manga; genres include / exclude, status, sort; chapters |
| Hentai20 | adult webtoons; genres include / exclude, sort; chapters |
| Manhwa18.net | adult manhwa; genres include / exclude, status, sort, tags, artists |
| Manhwa18.cc | adult manhwa; one genre at a time, sort, Raw / Completed lists |
| Manga18.club | adult manga / manhwa; one genre at a time, sort |
| Hentaigasm (videos) | its WordPress feed; one MP4 per episode (720p) |
| Oppai.stream (videos) | its pages + search form; one WebM per episode (1440p, heavy on mobile data), English subtitles |
| MuchoHentai (videos) | its pages; HLS up to 1080p (the stream key is unlocked the way the site's own player does), Raw / English / Spanish subtitles |
| HentaiMama (videos) | its pages; MP4 1080p (subtitles in the picture), series pages with every episode |
| NSFW R34 (videos) | 3D / 2D rule 34; HLS up to 1080p; tags include / exclude; more from the same animator |
| Porcore (videos) | 3D / cartoon; HLS up to 1080p; related videos |
| SFM Compile (videos) | SFM clips (MP4); games / characters / tags; more of the same character |
| Rule34Video (videos) | 3D / animated MP4 up to 1080p. In the UK the site asks for an age check: the app then says so and works with a VPN on |

Titles tagged as sexual content involving minors are always hidden on every source (not a setting). Your own
hidden tags (Settings) are added on top.

**Hide AI-generated** (Settings → Hidden tags, on by default) hides manga and pictures (videos are not filtered) whose title says
"AI generated" ("[AI Generated]", "AI-generated", "generated by AI", "[AI]" / "(AI Art)" in brackets, "AI生成") or
that carry an AI tag ("ai generated", "ai art", "stable diffusion", "novelai"...). A plain "Ai" in a title (a name)
never counts. AI tags also leave the tag pickers while it is on. nhentai, HentaiFox and atsu.moe lists are
checked by their AI tag too, so those titles are gone from the grid, not only blocked when opened. E-Hentai /
ExHentai lists and searches ask the site itself to leave out "ai generated" galleries. Recommended never suggests AI
titles either (AI tags don't count as your taste, and "more like this" skips AI titles you read).

## Build

`gradlew.bat :app:assembleRelease` (JDK 21 + Android SDK, its path in `local.properties`), then copy
`app\build\outputs\apk\release\app-release.apk`. Signing: put your key at `keystore\mangacracker.jks` and its
passwords in `keystore\keystore.properties` (`storePassword=`, `keyAlias=`, `keyPassword=`). Without them the build
uses the debug key. Checks:
- `gradlew.bat :app:testDebugUnitTest --tests "*LiveSourcesTest*"`: every source against the real sites.
- `gradlew.bat :app:testDebugUnitTest --tests "*ScreensTest*"`: renders the app on the PC (Robolectric) and saves
  screenshots to `_dev_tools\out\screens`.
- `NewSourcesTest` (every source: lists, page 2, search, tags, sorts, every first-page cover decoded, chapters,
  pages, video streams; `-Psources=id1,id2` with run_tests.py picks some), `NewScreensTest` (screens of the newer sources).
- `AppUpdateTest` (GitHub release check, download, installer hand-off, pop-up shots), `GifAndTagsTest` (animated
  posts give real GIFs, autocomplete on every source). `python _dev_tools\run_tests.py <Test> ...` runs tests and
  prints only results.
- `GifEngineTest` (the GIF maker on the PC: writer checked against ffmpeg's decoder, slow-motion in-betweens
  against the real frames, sizes / quality against ffmpeg's GIF recipes; the measuring parts need frames made with
  ffmpeg in `_dev_tools\out\gif\frames`), `GifScreensTest` (the player's bottom controls, the seek bar and the GIF
  panel in landscape).
- `BackupTest` (backup round trip, merge, bad / old / newer files, weekly files), `ReaderLogicTest` (two-page pairing,
  read badges, hide finished), `FeatureScreensTest` (badges, backup panel, auto-scroll, night filter, and "Test
  sources" run live against every site), `TwoPageShotTest` (the two-page view in landscape).
- `SourceMoveTest` (title matching, chapter-number mapping, move + undo) and `DownloadLogicTest` (download queue
  rules, files on disk, offline lookup, sizes) run with no network; `DownloadLiveTest` (real downloads from a few
  sites read back with the network blocked, the "Other sites" table, an MP4 and an HLS video checked offline;
  `-Psources=` picks the sites) and `DownloadScreensTest` (Other sites, move + Undo, downloads, the offline reader,
  shots 80-89) use the real sites.

Releasing: bump `versionCode` / `versionName` in `app/build.gradle.kts`, build, then
`gh release create v<version> MangaCracker-<version>.apk --repo YoruCAnime/Manga --title "MangaCracker <version>" --notes-file NOTES.md`.
The release notes show in the app's pop-up ("**bold**", "- " lists are fine).
