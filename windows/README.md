# MangaCracker for Windows

The phone app's code running as a Windows program: same sites, library, reader, videos, downloads, cutout and
settings, made to use little memory.

## Install

1. Download **MangaCracker-Windows-…zip** from the [Windows release](https://github.com/YoruCAnime/Manga/releases?q=windows).
2. Unzip it anywhere (for example `Documents\MangaCracker`) and run **MangaCracker.exe**. For a Desktop shortcut:
   right-click it › Send to › Desktop.
3. Once, for AVIF pages, GIFs and recording: install ffmpeg (`winget install Gyan.FFmpeg` in a terminal).

Windows 10 / 11, 64-bit. Sign-ins use Microsoft Edge (already in Windows). If Windows says "Windows protected your
PC" (the app isn't signed): More info › Run anyway. Your data stays in `%LOCALAPPDATA%\MangaCracker`, so a new
version is just unzipped over the old folder.

## Using it

Start it from MangaCracker.exe (or your shortcut). The title bar shows the version (MangaCracker 1.3.2).

- **Left rail**: Home, Library, Browse, Videos, Music, Updates, More (the phone's bottom bar).
- **Going back**: Esc, Alt+←, the mouse's back button, or the ← arrows. **F11** = full screen.
- **Reader**: pages sit in a centred column (Reader settings › *Page width*, up to the whole window).
  ↓ ↑ or J K scroll a little, Page Down / Space and Page Up / Shift+Space a screen, Home / End; ← / P previous
  chapter, → / N next chapter. A small bar at the bottom is always there: ‹ Previous chapter · page counter · Next
  chapter › (greyed when there's no chapter that way).
  **Right-click** a page (or press and hold): a small menu opens right at the mouse - **Save image** (first), *Save a
  cutout of this character*, Copy image (to the clipboard).
- **Videos** play in mpv inside the window, with the controls under the picture: volume on the left (the app's own,
  remembered for every video); previous / −1s / play / +1s / next in the middle; speed, quality (Best = the highest the
  video has, 1080p on these sites, or one of the sizes it really has), Picture (saturation / contrast, remembered), snapshot, Record, GIF, **Loop** (blue while on: the video starts again at its end instead of going to the next episode; stays on until you switch it off), subtitles and full
  screen on the right. The title opens every episode of the show (so does the name on a Continue watching card; its
  picture plays). Keys: Space / K play-pause, ← → 1 s (keeps playing), [ ] one frame back / forward, J / L 10 s, ↑ ↓ volume, M mute, F full
  screen, N / P next / previous episode, R record, G GIF.
- **Anime** (Videos tab, first two sites): **Anikoto** (1080p, Sub and Dub, subtitles in up to 9 languages) and
  **AnimeHeaven** (720p, English subs in the picture, no dub). Latest lists new episodes; Popular and search list
  whole shows: click one for all its episodes with **Sub** / **Dub** filters. The player line under the title says
  which one plays ("Episode 5 · Dub · Anikoto").
- **Recommended for you** (Videos tab, under Continue watching): picked from the genres of what you favourite, finish
  and watch for a while (quick peeks don't count), on every site you use plus similar sites you haven't tried;
  nothing you've seen, one card per show (seasons count as one). Refresh finds new ones (otherwise kept 3 hours).
  The picture plays it, the name opens the show. Code shared with the phone (`video/VideoRecommender.kt`).
- **Record**: press to start, press again to stop; the stretch is saved as an MP4 (graphics card encoder) in the
  recordings folder (Settings › Videos: recordings and snapshots folders, default Videos\MangaCracker and
  Pictures\MangaCracker\Snapshots).
- **GIF**: opens the GIF editor in place of the video, like ScreenToGif: **every frame from where you paused, in a row of
  boxes**, each with its own number (#) and time. **Delete what you don't want; whatever is left is your GIF**,
  and the preview above loops exactly that. No start or end to set. A click shows a frame and picks / unpicks it,
  sliding across boxes picks the run, the ✕ on a box deletes it; ← → step the shown frame (Shift 10). Buttons:
  **« Delete all before #N**, **Delete**, **Delete all after #N »** (from the frame you clicked last), **Select between**
  (click two frames, this picks all between them), None, **Delete frames [first #] to [last #]**, Undo (Ctrl+Z), Restore
  all; the Delete key deletes the picked frames, or the shown one (again = the next). You can delete anywhere, also in
  the middle: the rest stay in order and the GIF never blends across where they were. Numbers never change when frames
  go. The row is the 30 s from where you paused; for other frames, close it, go to that spot in the video and press GIF.
  Size (Tiny 2 MB / Small 5 MB / Big 10 MB), speed (0.25× to 2×), ping-pong, FPS (Auto /
  24 / 30 / 50: 50 makes smooth in-between frames), then the phone's GIF engine. The finished GIF then plays in a
  box (at most its own size, with its size / frames / length): Show in folder, Copy (paste into Discord, a chat,
  Explorer), **Move to <folder>** (a folder the recordings folder links to with a Windows shortcut, e.g. "old": the
  GIF moves there and the other buttons follow it), **Delete** (not happy with it: the file goes and you're back in the editor with the same frames), Make
  another, Done.
  Slow motion and 50 fps get their in-between frames from **RIFE** (rife-ncnn-vulkan, rife-v4.6 model, on the
  graphics card; the same as the ScreenToGif AnimeGif presets): one run per GIF draws every pair of drawings' in-betweens.
  Scene cuts are never blended. Shipped in `appResources/windows/rife` (not in git; copy `rife-ncnn-vulkan.exe`,
  `vcomp140.dll` and `rife-v4.6\` there); without it the built-in in-betweens are used.
- **Seek bar pictures**: point the mouse at the player's seek bar and a small picture of that moment shows above it,
  with its time, so you can find a scene without jumping around. Pictures come from the video's smallest size (quick:
  well under a second a picture on the sites, at once for downloads) and the ones already seen show straight away.
- **My GIFs & clips** (More): every GIF, snapshot and recording the player made, newest first, from the recordings and
  snapshots folders (Settings › Videos), and from folders those folders link to with a Windows shortcut (e.g. an
  "old - Shortcut" to where you keep older GIFs; those show the folder's name). Filters: All / GIFs / Snapshots / Clips, with the space they use. A GIF plays
  while the mouse is over it; the buttons on a picture copy it (paste into Discord, a chat or a folder), show it in
  Explorer or delete it (to the Recycle Bin). Click one to see it big: ← → (or the arrows on screen) go to the
  previous / next one, Delete deletes it, Esc goes back; clips play in Windows' own player.
- **Your stats** (More): your streak (days in a row you read or watched something), the last 7 days (chapters,
  reading time, videos, watching time, compared with the week before, and a bar for each day), an activity calendar
  of the last months, favourite manga and video genres, the titles you read most this month, and all-time totals.
  Reading time counts from the day you first open this version; the days before come from your history (chapters
  finished, videos watched).
- **Music** (own tab): type an anime and pick it from the suggestions (AniList, as you type: covers, format, year).
  Its page plays **every opening and ending in full**: **This season** or **All seasons** (the whole show in one
  list, season after season, each song once), all of them or only Openings / Endings, in order or **Shuffle**. The
  song list is MyAnimeList's (every OP / ED with its singer); each song plays in full from YouTube (YouTube Music's
  official audio first; covers, remixes and TV-size cuts are passed over), Opus 160 kbps, found when it starts. Below:
  **Soundtracks & albums**: official soundtracks and character-song albums from YouTube Music, and uploads from
  archive.org (FLAC or MP3, the quality on each track); open one or **Play all albums**. **Random anime songs**: the
  LISTEN.moe radio (Opus 192 kbps) with what's on now. A bar at the bottom (every screen but the reader and the video
  player) shows what plays, with previous / play-pause / next, stop, a line to jump in the song, **Loop** ("Loop all seasons" /
  "Loop all": after the last song back to the first, the default; "Loop 1 song"; "No loop") and the music **volume**
  (a slider in the bar). Switching tabs and coming back keeps the anime or album you had open. It keeps playing on other
  tabs and with the app in the background (also minimised); a video pauses it. Its own volume in Settings › Music.
- **Sounds while reading**: the 🎧 button in the reader (or Settings › Reader) plays a quiet loop under the pages:
  rain, café, fireplace, night crickets, or your own audio file (music: the Music tab). It fades out when you leave the
  reader and holds while the window is minimised; its own volume slider.
- **Sign-ins and Cloudflare checks** open a small Microsoft Edge window with the app's own profile (never your own
  Edge or Brave). Sign in there; the app picks the login up by itself. A login in your normal browser doesn't reach
  the app.
- **VPN with split tunnelling** (for sites that ask for an age check in your country, e.g. Rule34Video): add
  `MangaCracker App\app\MangaCracker.exe` to the VPN's list of apps (the place stays the same through updates),
  connect the VPN, then close and reopen MangaCracker (an app that was open before the VPN connected keeps going
  around it). The app uses plain IPv4 connections, which such VPNs need (else every list says "Cannot assign
  requested address").
- **Saved files**: pages and cutouts in Pictures\MangaCracker\<title>, snapshots in Pictures\MangaCracker\Snapshots,
  backups in Downloads\MangaCracker. The app's own data (library, downloads, cookies, crash.log) is in
  %LOCALAPPDATA%\MangaCracker.
- **New chapters** are checked while the app is open; Windows notifications come from its tray icon.

## How it differs from the phone app

| Phone | Windows |
|---|---|
| Android web view (logins, Cloudflare, comix) | Microsoft Edge driven over DevTools (`platform/Edge.kt`, `ComixEdge.kt`): a visible app window for sign-ins, a headless phone-sized page for comix |
| ExoPlayer | libmpv in a native panel (`platform/Mpv.kt`); every request goes through a 127.0.0.1 relay that uses the site's own player client (`platform/StreamProxy.kt`), so cookies, keys and re-unlocking match the phone |
| Coil 2 + Android decoders | Coil 3 + Skia; AVIF (atsu) through ffmpeg (`platform/Avif.kt`) |
| WorkManager, notifications | coroutines while the app runs, tray notifications |
| MediaStore / share sheet | Pictures / Downloads folders, clipboard |
| ExoPlayer for sounds while reading | a second, sound-only mpv (`core/Ambient.kt`); the loops are unpacked to the app's data folder |
| Media3 HLS downloader | own HLS downloader: one variant, AES-128 pieces decrypted and joined into video.ts |

The shared code (every site, safety filters, library, downloads, backup, cutout, GIF engine) is the phone app's,
unchanged where possible: small stand-ins in `src/main/kotlin/android/...` (Context, Toast, Uri, LocalContext,
BackHandler) let those files compile as they are, so phone fixes can be copied over.

## Memory

Java runs with a 200 MB heap that shrinks when idle, the serial collector, the quick compiler only and small stacks
(`build.gradle.kts`). Pictures decode at the size they are shown; read-ahead pages only fill the disk cache;
`platform/MemoryKeeper` runs a quick collection past 280 MB so pictures held in native memory really go.
Measured (Task Manager working set): Home ~250 MB, Browse ~270 MB, reading ~300 MB, atsu's 900 x 16000 px strips
~500 MB while on screen, playing a 1080p video ~450-550 MB (mostly mpv).

## Building

- `Build and install.bat`: builds and installs to ONE fixed place, `MangaCracker App\app\MangaCracker.exe` (the
  Desktop shortcut, a VPN's app list or a firewall rule keep working after updates), **also while the app is open**:
  then the new build waits in `MangaCracker App
ext` (`current.txt` says so) and the running copy switches by itself
  at a quiet moment (minimized, or in front and untouched for 3 minutes on a calm screen; never while reading,
  watching, making a GIF or downloading), or at once from its "Update ready · Switch now" bar: it closes, a small
  hidden helper (`switch.ps1`, log in `switch.log`) swaps `next` in as `app` and starts it, on the same tab and in
  the same place (`platform/SelfUpdate.kt`). If the swap can't be done the copy in `next` starts instead, and moves
  into `app` at its next quiet moment.
- Needs JDK 21 (Gradle uses it) and Python (the copy step). Not in git, put them in `appResources\windows\` first:
  `libmpv-2.dll` (mpv's Windows build, 120 MB) and `rife\` (from the
  [rife-ncnn-vulkan release](https://github.com/nihui/rife-ncnn-vulkan/releases): `rife-ncnn-vulkan.exe`,
  `vcomp140.dll`, `rife-v4.6\`). ffmpeg is found on the PATH (winget's Gyan.FFmpeg).
- Icon: the phone app's (`appIcon/icon.ico` for the exe / shortcut, `src/main/resources/icon.png` for the window and
  tray), made from the phone's launcher layers.
- Tests: `gradlew test --tests '*SmokeTest'` (every site's first list), `*PageTest` (one page per site decoded),
  `-Psources=a,b` to pick sites.
- Off-screen runs: env `MC_OFFSCREEN=1` keeps the window far off the screen, never focused, no sound;
  `MC_OPEN=demo:read:atsu`, `demo:settings:<item>` opens Settings scrolled, `MC_READER_OPEN=bar|sounds` opens the
  reader's top bar / sounds sheet, `MC_DEMO_GIF=1` the GIF editor once a video plays.
- The phone's Home season effects are left out on purpose (not wanted on the PC).

## Not done yet

- Splitting giant strips (atsu) into pieces to bring their memory down.

## Tabs and who made it

- **Tabs:** right-click any manga, manhwa, doujin, chapter or video and choose *Open in new tab* (or Ctrl+click / middle-click). They sit in a strip along the top.
- Only the tab on screen is loaded. The others keep your page or second, and come back after you restart the app.
- **Keys:** Ctrl+1 to 9 jump to a tab, Ctrl+Tab goes to the next, Ctrl+W closes, Ctrl+T keeps the page you're on as a tab.
- **Author, Artist, Circle:** shown on each title's page. Click a name to see their other works on that site.

## License

GPL-3.0 (see LICENSE). The Music tab uses [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor)
(GPL-3.0) for YouTube.
