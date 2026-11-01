# MangaCracker

A personal Android manga reader in the spirit of Tachiyomi / Mihon, with an AniList-style dark look.
A personal project, shared as is. Not affiliated with any of the sites below.

## Download

**[Get the latest APK from Releases](https://github.com/YoruCAnime/Manga/releases/latest)** (Android 10 or newer).

## What it does

- **Home**: Continue reading (page and % of where you stopped), Updates for you (new chapters of your library),
  Recommended for you (built from the genres you favourite and read, plus "more like this" of what you read last),
  and a Popular row for every source. Pull down to check for new chapters.
- **Library**: your favourites (tap ♥ on a title). Search by title / author / genre, sort, filter by source,
  "N new" badges, a reading bar on each cover. Long-press a cover to jump straight back into it.
- **Browse**: every source, Popular / Latest, search, and a filter sheet: sort, type, status, author / artist, and
  genres / tags (tap once to include ✓, twice to exclude ✕, as many as you like). "Search every source" at the top.
- **Title page**: blurred cover banner, authors and artists (tap one to see their other works), genre pills (tap
  to search that genre), description, chapter list with read marks and %, a big Continue button, More like this.
- **Reader**: one smooth vertical strip (no page turns). It remembers the exact page when you leave and opens
  there again, flows straight into the next chapter, preloads the next pages, pinch or double-tap to zoom, tap for
  the bar (page n of N · %, a page slider, previous / next chapter, save the whole chapter, reader settings:
  background, page gap, side margin). Long-press a page to save or share it (Pictures/MangaCracker/<title>).
- **Updates**: new chapters grouped by day; checked in the background (every 6 h by default, Settings) with a
  notification.
- **Settings**: update interval, covers per row, reader options, switch sources on / off, ExHentai sign-in,
  Danbooru key, hidden tags, clear the picture cache.

## Sources

| source | notes |
|---|---|
| atsu.moe (Atsumaru) | JSON API + its search engine; all filters, author pages, recommendations |
| comix.to | details / home rows over plain HTTP; search, chapter lists and pages run the site's own scripts in a hidden web view (comix encrypts them). Chapter lists load in steps a few seconds apart (the site blocks fast paging) |
| nhentai | v2 JSON API (the website is blocked in the UK, the API is not). Low limits: searches are spaced ~6 s apart |
| E-Hentai / ExHentai | lists and search are blocked in the UK (Online Safety Act) and work on a VPN; galleries open by link (paste a gallery link into the search box) and from the library. ExHentai: sign in under Settings, then switch it on |
| Danbooru | pictures and comic pools; 2 tags per search without a login (add your key in Settings for more) |
| Gelbooru | pictures through its web pages (no key needed) |

Titles tagged as sexual content involving minors are always hidden on every source (not a setting). Your own
hidden tags (Settings) are added on top.

## Build

`gradlew.bat :app:assembleRelease` (JDK 21 + Android SDK, its path in `local.properties`), then copy
`app\build\outputs\apk\release\app-release.apk`. Signing: put your key at `keystore\mangacracker.jks` and its
passwords in `keystore\keystore.properties` (`storePassword=`, `keyAlias=`, `keyPassword=`). Without them the build
uses the debug key. Checks:
- `gradlew.bat :app:testDebugUnitTest --tests "*LiveSourcesTest*"`: every source against the real sites.
- `gradlew.bat :app:testDebugUnitTest --tests "*ScreensTest*"`: renders the app on the PC (Robolectric) and saves
  screenshots to `_dev_tools\out\screens`.
