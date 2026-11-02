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
  clears the list). **Manga**: your favourites (tap ♥ on a title). Search by title / author / genre, sort, filter by source,
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
- **Videos** (own tab): Hentaigasm, Oppai.stream, MuchoHentai, HentaiMama, NSFW R34, Porcore, SFM Compile and Rule34Video. Latest / Popular, search with genre autocomplete,
  a genre sheet (include / exclude), Continue watching (resumes where you stopped). Tap a **series name ›** (on a
  card, the player's title or in Watched) for the series page: every episode in every language (Raw / English /
  Español / previews) with language filters. Long-press a video to add it to your favourites. covers that fall back to other
  sizes / the same series when a site refuses a picture. The player opens in landscape (forced: works with
  auto-rotate locked; Rotate switches to portrait and back), controls hide after 3 s, tap to show them:
  −1s / +1s buttons, double-tap the left / right side to step 1 s (taps stack), double-tap the middle to pause, hold
  anywhere for 2×, speeds 0.25× to 3×, previous / next episode of the same series (and "Up next" at the end),
  subtitles on / off, quality (MuchoHentai), **Snapshot** (the current frame to Pictures/MangaCracker/Snapshots) and
  **Record** (tap to start, tap to stop: that part is saved as an MP4 clip to Movies/MangaCracker).
- **Updates**: new chapters grouped by day; checked in the background (every 6 h by default, Settings) with a
  notification.
- **Settings**: app updates (check now, install, what's new, automatic check on / off), update interval, covers per
  row, reader options, switch sources on / off, ExHentai sign-in, Danbooru key, hidden tags, Hide AI-generated, clear the picture cache.
- **Recommended** uses the manga / doujin sources only (no image boards).

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

Releasing: bump `versionCode` / `versionName` in `app/build.gradle.kts`, build, then
`gh release create v<version> MangaCracker-<version>.apk --repo YoruCAnime/Manga --title "MangaCracker <version>" --notes-file NOTES.md`.
The release notes show in the app's pop-up ("**bold**", "- " lists are fine).
