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

Start it from MangaCracker.exe (or your shortcut). The title bar shows the version (MangaCracker 1.0.0).

- **Left rail**: Home, Library, Browse, Videos, Updates, More (the phone's bottom bar).
- **Going back**: Esc, Alt+←, the mouse's back button, or the ← arrows. **F11** = full screen.
- **Reader**: pages sit in a centred column (Reader settings › *Page width*, up to the whole window).
  ↓ ↑ or J K scroll a little, Page Down / Space and Page Up / Shift+Space a screen, Home / End; ← / P previous
  chapter, → / N next chapter. A small bar at the bottom is always there: ‹ Previous chapter · page counter · Next
  chapter › (greyed when there's no chapter that way).
  **Right-click** a page (or press and hold): a small menu opens right at the mouse - **Save image** (first), *Save a
  cutout of this character*, Copy image (to the clipboard).
- **Videos** play in mpv inside the window, with the controls under the picture: volume on the left (the app's own,
  remembered for every video); previous / −1s / play / +1s / next in the middle; speed, quality (Best = the highest the
  video has, 1080p on these sites, or one of the sizes it really has), Picture (saturation / contrast, remembered), snapshot, Record, GIF, subtitles and full
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
- **GIF**: opens the GIF editor in place of the video (GIFs up to 30 s). A small seeking strip of the 36 s around
  where you were (video times along it, the time under the mouse; a click or drag jumps there, ◀ Earlier / Later ▶
  move along the video), and under it **every frame one by one** (ScreenToGif-style), each with its own number (#) and
  time, the GIF's part bright with START / END marks, the rest dimmed, a scrollbar (the mouse wheel scrolls it too).
  A click shows the frame and picks / unpicks it, sliding across squares picks the run, the ✕ on a square deletes it;
  ← → step the shown frame (Shift 10). Buttons: **Set Start / Set End** (at the shown frame; keys [ ]), **« Delete all
  before #N / Delete all after #N »** (from the picked or shown frame), **Delete frames [first #] to [last #]** →
  Delete / Pick only, Select between, All, None, **Delete picked**, Undo (Ctrl+Z, also brings Start / End back),
  Restore all; the Delete key deletes the picked frames, or the shown one (again = the next). Any range can go: when
  the whole GIF part is deleted it moves to the frames left just after, same length. Numbers never change when frames go;
  deleted frames are skipped in the preview, red on the strip, and the GIF never blends across where they were. A looping
  preview of exactly the GIF, size (Tiny 2 MB / Small 5 MB / Big 10 MB), speed (0.25× to 2×), ping-pong, FPS (Auto /
  24 / 30 / 50: 50 makes smooth in-between frames), then the phone's GIF engine. The finished GIF then plays in a
  box (at most its own size, with its size / frames / length): Show in folder, Copy (paste into Discord, a chat,
  Explorer), **Delete** (not happy with it: the file goes and you're back in the editor on the same part), Make
  another, Done. A video you made a GIF of before opens on that GIF's part again
  ("Go to where the video is" goes back to where you are watching).
  Slow motion and 50 fps get their in-between frames from **RIFE** (rife-ncnn-vulkan, rife-v4.6 model, on the
  graphics card; the same as the ScreenToGif AnimeGif presets): one run per GIF draws every pair of drawings' in-betweens.
  Scene cuts are never blended. Shipped in `appResources/windows/rife` (not in git; copy `rife-ncnn-vulkan.exe`,
  `vcomp140.dll` and `rife-v4.6\` there); without it the built-in in-betweens are used.
- **Sounds while reading**: the 🎧 button in the reader (or Settings › Reader) plays a quiet loop under the pages:
  rain, café, fireplace, night crickets, five lo-fi tracks, or your own audio file. It fades out when you leave the
  reader and holds while the window is minimised; its own volume slider.
- **Sign-ins and Cloudflare checks** open a small Microsoft Edge window with the app's own profile (never your own
  Edge or Brave). Sign in there; the app picks the login up by itself. A login in your normal browser doesn't reach
  the app.
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

- `Build and install.bat`: builds and installs to `MangaCracker App\v<date-time>\` (the Desktop shortcut points at it)
  **while the app may be open**: `current.txt` names the newest copy, and a running copy switches to it by itself at a
  quiet moment (minimized, or in front and untouched for 3 minutes on a calm screen; never while reading, watching,
  making a GIF or downloading), on the same tab and in the same place, or at once from its "Update ready · Switch now"
  bar (`platform/SelfUpdate.kt`). Old copies are deleted once nothing in them is in use.
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

