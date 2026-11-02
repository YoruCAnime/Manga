# hentaimama.io video source spec (tested 2026-11-02)

Tested live on 2026-11-02 from England with Python `requests`, anonymous, no login, about 1 request per 1.1 s,
~65 requests in all. Probe scripts in `_dev_tools\probe_hentaimama\`: `probe.py` (single GET / form POST / capped
Range GET), `parse.py` (the parser the app copies, run live end to end), `embeds_offline.py` (the embed parser over
every saved embed page, no network). Saved replies in `probe_hentaimama\out\` (`requests.log` lists every call,
`parse_run.txt` is the last `parse.py` run). No video was downloaded: playlists and at most 64 KB of each file.

Tags: **[LIVE nnn]** = seen in this probe with that HTTP status; **[GUESS]** = not tested.

## 0. The short version

* **Feasible with plain OkHttp + Jsoup. No WebView, no login, no cookies.** WordPress, theme **DooPlay** (heavily
  customised), behind Cloudflare; every page came back 200, no challenge [LIVE 200, `cf-ray …-LHR`]. **Not blocked in
  the UK**: no 451, no age-verification wall.
* Two kinds of list: **episode cards** (latest: `/episodes/`) and **series cards** (popular, genre, search). Episode
  cards carry no genres; series cards do.
* **Stream**: the episode page asks `POST /wp-admin/admin-ajax.php action=get_player_contents a=<post id> i=<mirror>`
  for an iframe; the iframe (`/?dt_embed=<kind>&p=<base64 path>&ep=<id>`) holds a JW Player setup with the file URLs.
  Best mirror = **`r2-mp4`: plain MP4 on `gdvid.info`, 1080p / 720p / 480p, no expiry, no Referer or cookie needed**
  [LIVE 206 video/mp4, moov at the front].
* **Subtitles are burned in** (English hard subs): no caption tracks on any mirror (only a seek-bar `thumbnail.vtt`).
* Images: `hentaimama.io/wp-content/uploads/…webp`, no Referer needed [LIVE 206 image/webp ×4].
* Minors: the site's genre is spelled **`shoutacon`** (51 series, 3 pages). `/genre/loli/`, `/genre/lolicon/`,
  `/genre/shotacon/` do not exist [LIVE 404]. Section 7.

Headers: Android Chrome UA for everything; the ajax POST was sent with `Referer: <episode page>` and
`X-Requested-With: XMLHttpRequest` (as the page does; not tested without).

---

## 1. Lists

### Episode cards (latest)

`GET /episodes/` and `/episodes/page/<n>/` [LIVE 200]: newest first, 24 a page, 145 pages on 2026-11-02 (the last page
had 19). `/recent-episodes/` is the same list [LIVE 200]. `?filter=…` is ignored here [LIVE 200, same order].

```html
<article class="item se episodes infinite-item pop_info" id="post-81433" rel="81433">
  <div class="poster">
    <img alt="Sister Breeder OVA Episode 6" src="…/2026/10/sister-breeder-ova-6_preview_098-dtp1280x720sq85w.webp"
         srcset="…-w300.webp 300w, …-w400.webp 400w, …dtp1280x720sq85w.webp 1280w"/>
    <div class="season_m"><a href="https://hentaimama.io/episodes/sister-breeder-ova-episode-6/">
      <span class="b">Sister Breeder OVA</span><span class="c">Episode 6</span></a></div>
    <div class="rating">8.1</div><span class="dt-ep-badge">EP 6</span> …
  </div>
  <div class="data"><h3>…<a href="…/episodes/sister-breeder-ova-episode-6/">Sister Breeder OVA</a></h3>
    <div class="card-tags"><span class="tag">10/2026</span><span class="tag tag-sub">SUB</span></div></div>
</article>
```

| field | where |
|---|---|
| id | the episode slug (`sister-breeder-ova-episode-6`): stable, and the page URL is `/episodes/<slug>/`. The post id (`id="post-81433"`) is only needed for the player and is read from the episode page |
| series name | `.season_m .b` |
| episode | number in `.season_m .c` ("Episode 6") |
| cover | `img[src]` 1280x720 webp (16:9); `srcset` has `-w300` / `-w400` |
| language | `.card-tags .tag-sub` "SUB" → English, `.tag-raw` "RAW" → raw, `.tag-unc` "UNC" = uncensored [LIVE] |
| genres | **none** on these cards |

### Series cards (popular, genre, search)

```html
<article class="series-card">
  <div class="sc-left"><a class="sc-poster" href="…/tvshows/kanojo-saimin/"><img src="…-dtp500x750sq85w.webp" srcset="…-w175 …, …-w350 …"/></a> …</div>
  <div class="sc-body"><h3 class="sc-title"><a href="https://hentaimama.io/tvshows/kanojo-saimin/">Kanojo Saimin</a></h3>
    <div class="sc-alt">…</div><div class="sc-meta sc-meta-studios">…</div>
    <div class="sc-meta"><span class="sc-tag">2026</span><span class="sc-tag">3513k views</span><span class="sc-tag">2 Episodes</span></div>
    <p class="sc-desc">…</p><div class="sc-genres"><a href="…/genre/ahegao/">Ahegao</a>…</div></div>
</article>
```
Series slug = last part of the `/tvshows/<slug>/` link; covers are **portrait 500x750**; `sc-genres` = full genre list
(good for the minors filter and for filtering more genres on the client).

| feed | URL | notes |
|---|---|---|
| Popular | `GET /hentai-series/page/<n>/?filter=alltime` [LIVE 200] | series by all-time views (4913k, 3519k, …), 57 pages. Also `weekly` [LIVE 200], `monthly`, `rating`, `recent` [GUESS: links on the page], `alphabet` (= the default A-Z) [LIVE] |
| Genre | `GET /genre/<slug>/page/<n>/` [LIVE 200] | default sort = all time views [LIVE: "All Time" marked current]; same `?filter=` links |
| Popular widget | in every page's sidebar (`#dtw_content_views-2`, Week / Month / All Time tabs) | ~10 series, no paging; not used |

Paging: `<div class="pagination dt-pg" data-page="1" data-pages="145" data-tpl=".../page/987654321/">`;
**next page exists = `a.dt-pg-next[href]`** (absent on the last page [LIVE]). Page URLs keep the `?filter=` query.

## 2. Search and genres

* **Search**: `GET /?s=<q>` and `/page/<n>/?s=<q>` [LIVE 200] → **series cards**, 24 a page ("sister": 8 pages). No
  results: no cards, no pagination, text "No results to show with …" [LIVE 200].
* `/advance-search/?genres_filter[]=Vanilla&genres_filter[]=Maid` [LIVE 200] answered **no cards**: the form may need
  more fields; not used. So: one genre per request, extra genres / exclusions filtered on the cards' `sc-genres`.
* A-Z: `/?letter=true&s=title-a` [GUESS, links on the page].
* **Genre list**: `GET /genres-filter/` [LIVE 200], `section#genres a.genreitem` (123, name = link text, id = slug).
  No counts anywhere (the REST API has no genre taxonomy: `/wp-json/wp/v2/taxonomies` = category, post_tag only
  [LIVE 200]). Slugs on 2026-11-02 (note the site's own spellings `double-penatration`, `animal-ears` = "Animal Girls",
  `orc` = "Orc/Goblin"):
  `3d action adventure ahegao anal animal-ears armpit-fetish bdsm blackmail blonde blowjob bondage brainwashed cat-girl
  comedy condom cosplay creampie cross-dressing cunnilingus cutefunny dark-skin deepthroat demi-human demons doctor
  domination double-penatration drama dubbed ecchi elf facesitting facial family fantasy female-doctor female-teacher
  femdom filmed fisting footjob furry futanari gangbang gyaru hand-job harem historical horny-slut horror housewife
  humiliation incest inflation internal-cumshot lactation large-breasts magical-girls maid martial-arts masturbation
  megane milf mind-break molestation monsters nekomimi nipple-fuck non-japanese ntr nuns nurses office-ladies orc orgy
  piercing police pov pregnant princess public-sex rape rim-job romance scat school-girls sci-fi sci-fi-fantasy shimapan
  short shoutacon sleeping small-breasts sports squirting step-daughter step-mother step-sister stocking strap-on
  succubus super-power supernatural swimsuit tentacles three-some tits-fuck toys train-molestation trap tsundere
  ugly-bastard uncensored urination vampire vanilla virgins watersports widow womb-tattoo x-ray yuri`
  (+ studios `/studio/<slug>/` and release years on the same page).

## 3. Episode page → playable stream

`GET /episodes/<slug>/` [LIVE 200]:

| what | where |
|---|---|
| post id | inline script `$.post("…/admin-ajax.php", {action: 'get_player_contents',a: '81433',i: n}` ; also `link[rel=shortlink]` `?p=81433` |
| mirror tabs | `.dt-mi-tabs a[href^="#option-"]`: `#option-5` "mi-1", `#option-3` "mi-2", `#option-1` "mi-3" (same on a 2026 and a 2008 episode) |
| title | `h1.epih1` "Sister Breeder OVA - Episode 6" |
| series | `.episode-series-img a[href*="/tvshows/"]` (`title` = series name); also `#pag_episodes` "Episodes list" |
| genres | `.sgeneros a[href*="/genre/"]` (**a subset** of the series' genres: 9 vs 25) |
| description | `#info .episode-desc` |
| views / studio / dates | `#info .ep-meta-field` (`.ep-meta-label` + `.ep-meta-value`) |
| prev / next | `#pag_episodes a` ("Previous Ep" / "Next Ep", `.nonex` when none) |
| poster | `meta[property=og:image]` (1280x720 webp) |

**Mirrors**: `POST https://hentaimama.io/wp-admin/admin-ajax.php` form `action=get_player_contents&a=<post id>&i=<n>`
[LIVE 200, `text/html` holding a JSON array of 5 strings]. Only slot `n-1` is filled; the others are "". One call per
mirror. Slot = `<iframe … src="https:\/\/hentaimama.io\/?dt_embed=<kind>&#038;p=<base64>&#038;ep=<post id>">`.

| i | kind (both episodes tried) | `p` decodes to | embed page → file | works |
|---|---|---|---|---|
| 5 (tab mi-1) | `r2-mp4` | `output/S/sister-breeder-ova/6/mp4/1080p/sister-breeder-ova-6-1080p.mp4` | `https://gdvid.info/hls/<p>` + 720p + 480p siblings | **[LIVE 206 video/mp4, 205 MB / 377 MB files, no Referer needed, with Referer too]**, never expires: **used** |
| 1 (tab mi-3) | `rtmp` | `S/sister-breeder-ova-6.mp4` | `https://gdvid.info/S/sister-breeder-ova-6.mp4` (one size, ~half the bytes) | [LIVE 206, `application/octet-stream`, MP4 inside]: fallback 1 |
| 4 (hidden) | `hls-mp4` | same path as r2 | `https://hentaidoge.org/s/<expiry>/<token>/<p>` | signed; expiry = request time + 1 h [GUESS: not range-read] |
| 3 (tab mi-2) | `hls` | `output/S/sister-breeder-ova/6/hls/master.m3u8` | `https://hentaidoge.org/s/<expiry>/<token>/…/master.m3u8` | [LIVE 206 `application/vnd.apple.mpegurl`, 1080p / 720p / 480p variants], expiry + 1 h |
| 2 (hidden) | `newjav.php?p=<base64 "A/aneimo-1.mp4">` | | `https://na-01.javprovider.com/A/aneimo-1.mp4?st=…&e=<expiry>` | [LIVE 206 video/mp4, 2013 file, lower quality], expiry ~+1 h |

Embed pages (`GET` with `Referer: <episode page>` [LIVE 200]): `jwplayer('…').setup({sources: [{"file":"https:\/\/…",
"label":"1080p","type":"mp4","default":true}, …],tracks: [{file: "…/thumbnail.vtt", kind: "thumbnails"}], …})`.
newjav.php uses bare JS keys: `sources: [{ type: "mp4", file: "https://…" }]`. Parser: take the `sources: [ … ]` block,
each `{…}`, `"?file"?\s*:\s*"…"` and `"?type"?…`; first entry = 1080p default.

The r2 file URL is also `https://gdvid.info/hls/` + base64-decoded `p` (true on both episodes) [LIVE]: the app uses that
only if the embed page fails.

Cost per play: episode page + series page + 1 POST + 1 embed GET (4 requests).

## 4. Series page

`GET /tvshows/<slug>/` [LIVE 200]: `h1.dsc-title`, `.dsc-alt`, `.dsc-poster img` (500x750), `.dsc-stats` (score,
views, episodes, duration "15m", aired, studio), `.dsc-genres a` (full genre list), `.dsc-desc` (synopsis),
`a.dsc-play` "Watch Ep 1", and **every episode in `#episodes a.dt-se-item`** (one page, "Show more" only unfolds with
CSS) [LIVE: 6 of 6, 2 of 2]:
```html
<a class="dt-se-item" href="…/episodes/sister-breeder-ova-episode-1/"><span class="dt-se-thumb"><img alt="Episode 1"
  src="…dtp1280x720sq80w.webp" srcset="…-w300.webp 300w"/><b class="dt-se-num">EP 1</b></span>
  <span class="dt-se-body"><span class="dt-se-toprow"><b class="dt-se-title">Episode 1</b>…</span>
  <span class="dt-se-desc">…</span><span class="dt-se-date">Oct 02, 2025</span></span></a>
```
Episode slugs usually = `<series slug>-episode-<n>`, but not always (series `300-yen-no-otsuki-ai-anime-edition`,
names differ too), so the app keys series by the link on the episode page.

## 5. Images

`hentaimama.io/wp-content/uploads/<y>/<m>/<name>-dtp<W>x<H>sq<Q>w[-w<size>].webp` [LIVE 206 image/webp, 8-33 KB,
no Referer needed ×3, with Referer ×1]. Some series backgrounds come from `image.tmdb.org` (not used).

## 6. Rate / blocking

~65 requests at ~1/s: no 429, no challenge. HTML pages are Cloudflare-cached (`cf-cache-status: HIT`). The app uses a
700 ms gap for pages; video files are not paced.

## 7. Minors

* Genre **`shoutacon`** ("Shoutacon"): 51 series on 2026-11-02 (`/genre/shoutacon/` pages 1-3 [LIVE 200]).
  No `loli` / `lolicon` / `shotacon` genres [LIVE 404].
* Series cards carry their genres → drop the card. **Episode cards carry none** → the app reads the shoutacon genre
  once per run (3 requests; the 51 slugs are also seeded in the code) and drops episodes whose series name or slug
  prefix (`<series>-episode…`) matches. Seen: "A Forbidden Time" episodes are in the latest list's last page,
  "Uchi no Otouto Maji de Dekain…" is in the weekly popular list.
* details(): the episode page genres are a subset, so the **series page's genres are checked too**; then
  `VideoSafety.check`.
* Note: some synopses use "Shota" as a character name (Sister Breeder OVA); the app only checks titles / series names
  / genres for words, not synopses.

## 8. App notes (HentaiMamaSource.kt)

* latest = episode cards; popular / genre / search = **series cards** (`id = "tv:<series slug>"`, portrait covers,
  `episode` empty). `details()` of a series card plays its first episode and returns that episode as `ref`, so the
  player's next / previous works; `series()` = the series page's episodes.
* Stream kind `"mp4"` (r2-mp4 1080p). Fallbacks: rtmp MP4, then hls-mp4 / HLS / newjav (these expire after ~1 h, so a
  cached VideoDetails older than that would fail for them; r2 / rtmp do not expire).
* `Http.referers["gdvid.info"]` and `["hentaidoge.org"]` = `https://hentaimama.io/` (what the browser sends; not
  needed by gdvid.info). No other Http rules.
