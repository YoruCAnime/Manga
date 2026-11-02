# muchohentai.com video source spec (tested 2026-11-02)

Tested live on 2026-11-02 from England with Python `requests`, anonymous, no login, about 1 request per 1.2 s,
33 requests in all. Probe scripts in `_dev_tools\probe_muchohentai\`: `probe.py` (single GET / capped Range GET),
`stream.py` (HLS + key flow), `keyprobe.py` (AES key with / without cookie, subtitles, download link), `extras.py`
(OkHttp-style key flow, thumbnails, sorting, search paging), `parse.py` (list parser the app can copy). Saved replies
in `probe_muchohentai\out\` (`requests.log` lists every call). No video was downloaded: playlists only, and at most
64 KB of one segment / one download link.

Tags: **[LIVE nnn]** = seen in this probe with that HTTP status; **[GUESS]** = not tested.

## 0. The short version

* **Feasible with plain OkHttp + Jsoup. No WebView needed.** The site is WordPress (theme "detube") behind
  Cloudflare; every HTML page came back 200 with no challenge [LIVE 200]. Not blocked in the UK: no 451, no Online
  Safety Act / age-check page [LIVE 200, `cf-ray ...-LHR`].
* **Stream = HLS (m3u8), AES-128 encrypted, 3 qualities (1080p / 720p / 486p)** on `va01|va02.edge.tmncdn.io`. The
  master URL is written in an inline `<script>` on the video page (`var files = [...]`).
* **The hard part:** the AES key URL (on `muchohentai.com`) answers 403 unless the request carries the cookies that
  an anonymous `POST https://keyserver.muchohentai.com/authorize` sets (Max-Age 720 s). So the player's OkHttp client
  needs a CookieJar, must POST `/authorize` before playback, and again after ~10 min or on a key 403 (section 3).
* English and Spanish posts play the **same Japanese video with a separate WebVTT subtitle file** (soft subs). The
  player must side-load the `.vtt`.
* The WordPress REST API (`/wp-json/...`) is firewalled (Cloudflare "Sorry, you have been blocked") [LIVE 403]. Use
  HTML pages only. One firewall hit did not block later HTML requests.
* Every episode exists up to 4 times as separate posts: **Raw** (Japanese, no subs), **English Subbed**,
  **En Español**, **【PV】** (preview trailer). The app should group them (section 4).

Headers: any normal UA works. Tested: Android Chrome UA for pages, `okhttp/4.12.0` for the authorize + key flow; no
Referer / Origin needed anywhere [LIVE 200].

---

## 1. Lists

All list pages share one HTML loop. 24 items per page [LIVE 200]. One item (trimmed real markup):
```html
<div id="post-202852" class="item cf item-video post-202852 post type-post ... category-<series-slug>
     category-eng-subbed tag-1080p tag-6963 tag-2026-english tag-english-subbed tag-mkv">
  <div class="thumb">
    <a class="clip-link" data-id="202852" title="<Series> Episode 4 English Subbed"
       href="https://muchohentai.com/avH6Dh/202852">
      <span class="clip"><img src="https://muchohentai.com/wp-content/uploads/en-56-320x180.jpg" .../></span></a></div>
  <div class="data">
    <h2 class="entry-title"><a href="https://muchohentai.com/avH6Dh/202852" ...>…title…</a></h2>
    <!--<p class="entry-meta">Posted <time class="entry-date" datetime="2026-10-25T23:11:18-04:00">…</time>…-->
    <p class="stats"><span class="views"><i class="count">13.91K</i> …Views</span>
                     <span class="comments"><i class="count">0</i> …</span></p>
  </div>
</div><!-- end #post-202852 -->
```

Parse each `div.item.item-video`:

| field | where | notes |
|---|---|---|
| post id | `a.clip-link[data-id]` or `id="post-N"` | stable id; video page = `https://muchohentai.com/avH6Dh/<id>` [LIVE 200]. `avH6Dh` is a fixed path segment, keep it as written in the href |
| title | `a.clip-link[title]` | always `"<Series> Episode <N> <Lang>"`, PV = `"【PV】<Series> Episode <N>"`. Extras (intro, HMV) have no "Episode N" |
| episode no. | regex `\bEpisode\s+(\d+(?:\.\d+)?)` on the title | null for extras |
| language | title ending `Raw` / `English Subbed` / `En Español`, prefix `【PV】`; or the class `category-raw` / `category-eng-subbed` / `category-n` (Spanish) / `category-pv` / `category-h-mv` | |
| series slug | the class `category-<slug>` that is NOT one of `raw, eng-subbed, n, pv, h-mv` | series display name = title minus `" Episode N …"` (and minus `【PV】`); the exact name is on the video page / series list |
| tags (genres) | classes `tag-<slug>` | used for the minors filter (section 7) without opening the post. Mixed with technical tags: `1080p, mkv, hd, censored, uncensored, japanese, english-subbed, subtitulado-en-espanol, no-subs, preview, 2026-english, 2025-japanese, 2011-to-2020, 6963, 6514 …` |
| thumbnail | `img[src]` | **best quality: strip the size suffix** `-\d+x\d+(?=\.\w+$)`: `es-20-640x360.jpg` → `es-20.jpg` [LIVE 206, JPEG, 280 KB]. Home uses `-320x180`, list pages `-640x360`. Older posts point at `<Folder>/episode_N/en.jpg` (or `.png`, `ja.jpg`, `jaa.jpg`, `pv/pv.png`) with no suffix: use as is |
| views | `p.stats .views .count` | "13.91K" style |
| date | only inside an HTML comment (`<!--<p class="entry-meta">…<time datetime=…>`) | Jsoup does not select inside comments; regex the raw HTML or skip it [LIVE] |

Pagination: WordPress style `<base>/page/<n>` [LIVE 200]; `a.nextpostslink` = next page, `a.last` / the highest
`/page/N` link = last page (latest: 625 pages on 2026-11-02). **Page links drop the `?orderby=` query on
`/latest-hentai-posts`** (they keep it on genre pages), so build page URLs yourself and always append the sort.

| feed | URL | notes |
|---|---|---|
| **Home** | `GET /home` [LIVE 200] | sections `div.section-box` with `h2.section-title .name`: "Newest Releases", "Recent Japanese Raw …", "Recent English Subbed …", "Recientes … En Español", "Recent Hentai Previews"; ~20 items each, no paging. Not needed if Latest is used |
| **Latest** | `GET /latest-hentai-posts/page/<n>` (page 1 = `/latest-hentai-posts`) [LIVE 200] | newest first, 24/page |
| **Popular (all-time views)** | `GET /latest-hentai-posts/page/<n>?orderby=views` [LIVE 200] | views descending (page 2: 55.97K → 48.22K). There is **no "trending this week"** sort: only `orderby=date` (default) / `title` / `views` / `rand`, plus `order=asc` |
| Random | `?orderby=rand` [GUESS: link exists on the page] | |
| Most liked | `GET /likes` [GUESS: menu link, not fetched] | |
| Language feeds | `/g/english-subbed`, `/g/japanese`, `/g/subtitulado-en-espanol`, `/g/preview` [GUESS: the Home "More" links; probably the same tag archive as `/genre/<slug>`] | |

The sort parameters also work on genre archives (`/genre/vanilla/page/2?orderby=views` [LIVE 200]) and on series
pages (`/series/<slug>?orderby=title&order=asc` [LIVE 200]).

## 2. Search and genres

* **Search**: `GET /?s=<query>` (page 1) and `GET /page/<n>/?s=<query>` [LIVE 200]. 24/page, WordPress search over
  title + content ("episode" = 558 pages). Same item markup as section 1. `orderby=` probably works too [GUESS].
  No "no results" case was tested [GUESS: the loop is empty].
* **Genre archive**: `GET /genre/<slug>/page/<n>` (+ optional `?orderby=views|title|date|rand`) [LIVE 200]
  (`/genre/vanilla`: 22 pages). WordPress tags are called "genres" here; there are no numeric ids in the HTML
  (and `/wp-json` is firewalled), so the **slug is the id**.
* Combining genres or genre + text: WordPress itself accepts `/?s=<q>&tag=<slug>` and `tag=a+b` (AND) /
  `tag=a,b` (OR) [GUESS, not tested]. Simplest: single-genre browse only.
* **Genre list** (`GET /genre-list` [LIVE 200], 89 entries, links `a[href*="/genre/"]`, name = link text). Slugs
  (2026-11-02):
  `ahegao anal bdsm bestiality big-breasts blackmail bonus bukakke censored cgi chikan classic comedy cosplay
  creampie cross-dressing cunnilingus dark-skin drugs ecchi eroge exhibitionism facial fantasy fellatio filmed
  foot-job futanari gangbang gender-bender guro hand-job hardcore harem historical horror hypnosis idol impregnation
  incest inflation kemonomimi lactation large-insertion maid masturbation mecha milf mind-break monster ninja ntr
  nun nurse orgy paizuri plot pov public-sex public-use rape rimjob ryona scat school sci-fi seinen softcore
  sports stockings succubus supernatural superpower swimsuit threesome toys ugly-bastard uncensored vanilla virgin
  voyeur waitress watersports x-ray yaoi yuri`
  plus the hidden ones in section 7 (`lolicon`, `shotacon`, and `pettanko`) which the app must never offer.
* **Series list**: `GET /hentai-series-list` [LIVE 200, 423 KB, one page]: ~2 400 `a[href*="/series/"]`, A to Z,
  name = `title` attribute. No covers, no tags. Skip the pseudo-series `raw`, `【ENG SUBBED】`, `【N】`, `【PV】`
  (slugs `raw`, `%e3%80%90eng-subbed%e3%80%91`, `%e3%80%90n%e3%80%91`, `%e3%80%90pv%e3%80%91`). Useful for an A-Z
  browser; cache for a day.

## 3. Video page → playable stream

`GET https://muchohentai.com/avH6Dh/<id>` [LIVE 200]. Everything is in one inline script after
`jwplayer.js` (JW Player 8.14):
```js
var servers = ['va01', 'va02'];
var server = "https://" + servers[choice-1] + ".edge.tmncdn.io";        // random of the two
var files = [{"file":"\/wp-content\/uploads\/<Folder>\/episode_4\/ja.m3u8"}];
var subs = "\/wp-content\/uploads\/<Folder>\/episode_4\/subs\/en.vtt";  // or null
var subName = "English";                                                // or ""
var hasThumbs = "1";   // -> "/wp-content/uploads/<Folder>/episode_4/thumbs.vtt" (seek-bar sprites)
jQuery.ajax({type:'post', url:'https://keyserver.muchohentai.com/authorize', xhrFields:{withCredentials:true}})
  .done(function(){ playerInstance.setup(playlist); ... });
```
Regexes: `var files = (\[.*?\]);` (JSON, `\/` escapes), `var subs = (null|"[^"]*");`, `var subName = "([^"]*)";`,
`var servers = \[([^\]]*)\];`. Master URL = `https://va01.edge.tmncdn.io` + `files[0].file` (on failure try `va02`).
Subtitle URL = `https://muchohentai.com` + `subs` (relative to the site, not the CDN).

Seen on three pages (2026 episode, 2019 episode, a PV): always one `files` entry; episodes `ja.m3u8`, PVs
`pv/pv.mp4.m3u8`. Subbed posts point at the **same `ja.m3u8`** as Raw plus `subs/en.vtt` (Spanish: `subs/es.vtt`
[GUESS]). Raw and PV: `subs = null`.

### The HLS chain (all tested on one 1080p episode)

| step | request | result |
|---|---|---|
| master | `GET https://va01.edge.tmncdn.io/wp-content/uploads/<Folder>/episode_4/ja.m3u8`, no cookie, no Referer | [LIVE 200] `application/vnd.apple.mpegurl`, nginx, CORS `https://muchohentai.com` |
| qualities | master `#EXT-X-STREAM-INF` | `media-1/stream.m3u8` 1920x1080 (avc1.640032, ~5.6 Mb/s peak), `media-2` 1280x720, `media-3` 864x486; audio is a separate rendition `#EXT-X-MEDIA:TYPE=AUDIO … URI="audio/aac/ja/stream.m3u8"`; I-frame playlists too |
| variant | `GET …/media-1/stream.m3u8`, no cookie | [LIVE 200] VOD, 6 s `segment-N.ts`, `#EXT-X-ENDLIST`, plus `#EXT-X-KEY:METHOD=AES-128,URI="https://muchohentai.com/wp-content/uploads/<Folder>/episode_4/media-1/key.bin?key=<token>"` |
| segment | `GET …/media-1/segment-0.ts` with `Range: bytes=0-65535`, no cookie | [LIVE 206] `video/mp2t`, `Content-Range: bytes 0-65535/2231760`; bytes are encrypted (no 0x47 sync byte), as expected |
| key, no cookie | `GET <key URI>` | [LIVE 403] HTML troll text ("…the download would be done by now…"); also 403 with a Referer |
| authorize | `POST https://keyserver.muchohentai.com/authorize`, empty body, no Origin / Referer, UA `okhttp/4.12.0` | [LIVE 200] `{"success":true}`, sets 2 cookies on `.muchohentai.com`: `keyserver_session` and one with a **random name** (40 chars), both `Max-Age=720` |
| key, with cookies | `GET <key URI>` with those cookies, UA `okhttp/4.12.0`, no Referer | [LIVE 200] `application/octet-stream`, 16 bytes |

* **Signed / expiring?** The `?key=<token>` in the variant playlist **changes over time** (two different tokens 25 min
  apart) while the 16-byte key stayed the same. So always use the variant playlist fresh (ExoPlayer does) and never
  store key URLs. The master / variant / segment URLs themselves are plain static paths with no signature [LIVE 200
  without cookies]. The cookie lifetime is 12 minutes.
* Whether `media-2` / `media-3` / the audio rendition use the same key [GUESS: each has its own `key.bin` URI under
  its folder; the cookie covers all of them].

**App recipe (ExoPlayer / Media3):**
1. One OkHttp client with a `CookieJar` (in-memory is enough) for the whole source.
2. Before `prepare()`: `POST /authorize` (empty body). Then `HlsMediaSource` with `OkHttpDataSource.Factory(client)`.
3. Interceptor: if a request to `*/key.bin*` returns 403, POST `/authorize` once and retry. This also covers a quality
   switch or a seek after the 12-minute cookie ran out.
4. Side-load the subtitle: `MediaItem.SubtitleConfiguration(Uri.parse("https://muchohentai.com"+subs))
   .setMimeType(MimeTypes.TEXT_VTT).setLanguage("en"|"es")`. The `.vtt` needs no cookie [LIVE 200, served as
   `application/octet-stream`, so set the MIME type explicitly].
5. No Referer, Origin or special UA needed for any HLS request [LIVE].
The site's own note says "Android users: if the video gives you an error, log in; this bypasses the hot-linking
script". With the authorize call above no login was needed.

### Download link (do not use)

The page also has `a.btn-blue` → `https://downloads.muchohentai.com/wp-content/uploads/<Folder>/episode_4/download.mkv`.
Anonymous: [LIVE 206] `video/mp4`, `Content-Disposition: attachment; filename="hentai.mp4"`, total size only
**415 933 bytes** for a ~7-minute 1080p episode, so it is a decoy file for hot-linkers (probably real only when logged
in [GUESS]). Use the HLS stream.

## 4. Series grouping (next / previous episode)

* A post's series = its `category-<slug>` class (list) or the first `#extras a[href*="/series/"]` on the video page
  (`<h4>Hentai Series:</h4> <a href=".../series/<slug>" rel="category tag">Name</a>, <a …>【ENG SUBBED】</a>`).
* **All posts of a series**: `GET /series/<slug>` [LIVE 200] (+ `/page/<n>` for long series [GUESS: 24 per page
  like every archive; the tested series had 18 posts on one page]). It returns Raw, English, Spanish, PV and extras
  mixed, newest first. `?orderby=title&order=asc` sorts them as "Episode 1 En Español, Episode 1 English Subbed,
  Episode 1 Raw, Episode 2 …" [LIVE 200], but sort in the app anyway: parse episode number + language from each
  title.
* For the player's next / previous: keep the **same language** as the current post (Raw / English / Spanish), order
  by episode number, skip PV and extras. Fall back to Raw if the next episode has no post in that language yet
  (new episodes appear as Raw first, subs later).
* The video page's own `rel="prev"` / `rel="next"` buttons (`⇠PV`, `NX⇢`) point at neighbouring posts **outside**
  the series (tested: neither was an Amakano post), so do not use them.
* Video page extras worth showing: `#extras` has "Alternative Names:" (Japanese title), release date as `YYMMDD`
  ("Originally Released: 261025"), "Genres:" links (`a[href*="/genre/"]`). `.entry-content` (synopsis) was empty on
  every tested page.

## 5. Blocking

* **No UK block**: every page and the HLS / key hosts answered normally from a UK IP (Cloudflare edge LHR) [LIVE 200].
  No 451, no age-verification page.
* **No Cloudflare challenge** on HTML, search, genre, series or video pages (33 requests, plain `requests`, no
  cookies) [LIVE 200]. `cf-cache-status: DYNAMIC` (pages are not edge-cached).
* **Firewalled**: `GET /wp-json/wp/v2/posts` → [LIVE 403] Cloudflare "Attention Required / Sorry, you have been
  blocked" page (a firewall rule, not a JS challenge). Do not use the REST API. `robots.txt` only disallows
  `/wp-admin/`.
* The CDN `va01|va02.edge.tmncdn.io` is plain nginx, no Cloudflare.

## 6. Rate limits and WebView

* No rate-limit headers and no 429 in 33 requests at ~1.2 s spacing [LIVE]. Pages are 100 to 180 KB of HTML each (the
  series list is 423 KB). Be polite anyway: one HTML request at a time, cache list pages for a few minutes, the series
  list and genre list for a day.
* HLS segments come from the CDN without limits seen (only one 64 KB range was fetched).
* **No WebView needed**: listing, search, genres, series and the stream all work with OkHttp + Jsoup + a CookieJar.
  The only JavaScript on the page that matters (the `/authorize` POST) is trivial to replay. Only if the site ever adds
  a Cloudflare JS challenge on pages would a hidden WebView (to get `cf_clearance`) be needed [GUESS].
* The page also loads a VAST pre-roll ad and other ad scripts; the app skips all of that.

## 7. Hide-by-default tags (minors)

Genre slugs on this site that mark content involving minors (from `/genre-list`, 2026-11-02):

| slug | name | rule |
|---|---|---|
| `lolicon` | LOLICON | always hide |
| `shotacon` | SHOTACON | always hide |
| `pettanko` | PETTANKO ("flat chest") | hide by default; on this kind of site it is mostly used together with / instead of lolicon |

No `loli`, `shota`, `toddlercon` or `kodomo` genres exist here. These entries were not opened; the probe dropped any
item carrying them before printing (`parse.py`). The genre archive for `vanilla` page 2 had 9 lolicon + 6 shotacon
items out of 24, so filtering is really needed.

How to apply them:
1. **Lists / search / genre / series pages**: drop every `div.item` whose class list contains `tag-lolicon`,
   `tag-shotacon` or `tag-pettanko` (works without opening the post).
2. **Never offer** those three slugs in the genre picker, and refuse `/genre/<hidden>` URLs.
3. **Video page / deep link**: refuse to open a post whose `#extras a[href*="/genre/"]` contains a hidden slug.
4. **Brand-new posts often carry only technical tags** (`1080p, mkv, 2026-english …`, no genres yet), so tags alone
   are not enough for them. Also hide by **series**: remember every series slug that was ever seen on a hidden post
   (lists show it in the item's `category-<slug>` class) and hide all posts of that series; and drop titles that
   contain the words `loli`, `lolicon`, `shota`, `shotacon` (case-insensitive) as a last net.
5. Ignore the "Recent Comments" sidebar entirely (user text can mention anything).
