# porcore.com video source spec (tested 2026-11-02)

Tested live on 2026-11-02 from England with Python `requests`, anonymous, no login, about 1 request per 1.1 s.
Probe scripts in `_dev_tools\probe_porcore\`: `probe.py` (single GET / capped 64 KB Range GET), `parse.py` (list,
search, video page -> HLS -> one segment, thumbnails: the parser the app copies). Saved replies in
`probe_porcore\out\` (`requests.log` lists every call). No video was downloaded: playlists only, and 64 KB of one
segment.

Tags: **[LIVE nnn]** = seen in this probe with that HTTP status; **[GUESS]** = not tested.

## 0. The short version

* **Plain OkHttp + Jsoup. No WebView, no cookies, no Referer.** Custom PHP site (nginx, part of the Gamcore /
  69games network), not WordPress, no JSON API. Every page answered 200 from a UK IP; no 451, no age wall [LIVE 200].
  It sets a `PHPSESSID` cookie that nothing needs.
* **Stream = HLS** (`master.m3u8`, nginx-vod "urlset") on `fgnvideo.cdn.serverable.com`, up to **4 qualities
  (1080p / 720p / 480p / 360p)**, plain TS segments, no encryption, no signature, no Referer [LIVE 206]. The direct
  `.mp4` behind the urlset answers 400 [LIVE 400], so HLS only.
* ~3 100 videos (ids 1 .. 3096 on 2026-11-02, some deleted: `/video/1/` = 404). **19 per page.**
* Lists have **no tags**; tags are on the video page only.

## 1. Lists

Every list exists as a full page and as an **ajax fragment** (just the items + the next link, ~18 KB instead of
~90 KB). Use the fragments.

| feed | URL | notes |
|---|---|---|
| **Latest** | `GET /&ajax&p=<n>` (n from 1) [LIVE 200] | newest first (id descending) |
| **Most viewed (all time)** | `GET /&ajax&p=<n>&sort=mostviewed` [LIVE 200] | same as `/videos/most-viewed/&ajax&p=<n>` [LIVE 200]. Page 1: 5.7M views, page 2: 1.65M |
| Top rated | `&sort=toprated` or `/videos/top-rated/&ajax&p=<n>` [LIVE 200] | |
| other sorts | `newest` (default), `oldest`, `longest`, `shortest`, `relevancy` [GUESS: from the site's sort box] | **no time ranges** (no "this week") |

**The next link drops `&sort=`** on `/&ajax` pages (`<nav id="page_nav"><a href="https://porcore.com/&ajax&p=2">`),
so build page URLs yourself and append the sort. On `/show/` it keeps it.

**Next-page detection**: `#page_nav a[href]` present = more. Past the end the fragment is just
`<div class="p12">Sorry but there are no results for +…</div>` (no items, no `#page_nav`) [LIVE 200].

One item (trimmed real markup, identical in fragments, full pages and the "Related videos" box):
```html
<div class="onevideothumb">
  <a class="clip-link js-pop" data-id="3096" title="Peni X Magik - Brand New Mate" href="/video/3096/peni-x-magik-brand-new-mate/">
    <h5 title="…">…</h5>
    <img src="https://porcore.com/res.php?q=91&w=410&h=228&src=media/thumbs/3096/i101.jpg" class="flipbookimages"
         data-thumbs="3" data-prefix="/res.php?q=91&w=410&h=228&src=media/thumbs/3096/i" />
    <div class="videoparams"><div class="floatlefttop" title="Duration"><span class="glyphicon glyphicon-time"></span> 03:21</div></div>
    <div class="rating"><div class="floatleft" title="Rating">… 84%</div>
                        <div class="floatright" title="Views">… 7,376 views</div></div>
  </a>
</div>
```

| field | where |
|---|---|
| id | `a.clip-link[data-id]` (numeric) |
| title | `a.clip-link[title]` (HTML entities: `&amp;`) |
| page | `a[href]` = `/video/<id>/<slug>/` (relative in lists, absolute in Related). `/video/<id>/` alone works too [LIVE 200] |
| thumbnail | `img[src]` = `res.php?q=91&w=410&h=228&src=media/thumbs/<id>/i101.jpg` [LIVE 206 image/jpeg, 19-28 KB]. Fallback: the raw `https://porcore.com/media/thumbs/<id>/i101.jpg` [LIVE 206 image/jpeg]. Hover-preview frames `i102.jpg`… up to `data-thumbs` |
| duration | `.floatlefttop` text ("03:21", "20:04") |
| views | `.rating .floatright` text ("7,376 views") |
| rating | `.rating .floatleft` text ("84%") |

## 2. Search, tags, authors

* **Search**: `GET /show/<words>?ajax&p=<n>[&sort=…]` [LIVE 200], words URL-encoded with spaces (the site's own JS:
  `'/show/' + encodeURIComponent(q) + '?ajax'`); next links come back as `/show/tifa+lockhart?ajax&p=2`. It is a
  keyword / relevance search over titles and tags: `tifa-lockhart anal-sex` mostly returns videos with both, but with
  `sort=mostviewed` Lara Croft videos came in too (OR-like). No exclusion syntax (`-word` did not exclude) [LIVE].
* **Tag page** = the same search with the tag slug: `/show/<slug>` [LIVE 200]. The heading names the tag
  ("tifa lockhart - Final Fantasy").
* **Authors**: the search word `from:<Name>` (`/show/from:Bouquetman?ajax&p=1` [LIVE 200, 10 items]).
* **Duration filter words**: `duration:micro|short|medium|long|movie` [GUESS: from the filter box].
* **Tag list**: the "All tags" cloud at the bottom of every full page: `div.tagcloud a[href^="/show/"]`, 188 tags,
  name = link text, **no counts** [LIVE 200]. **Author list** with counts: `select.author option[value^="from:"]`
  (`data-subtext="61 videos"`), 282 authors [LIVE 200].
* **Minors**: the site refuses `loli` and `shota` itself ("Your search could be for illegal and abusive sexual
  material", no results) [LIVE 200]. In the tag cloud the only age word is **`teenage-sex`** (hidden by the app);
  also `schoolgirls` and `tiny-tits` (adult costume / body tags, left as on the other sources); `teen-titans` is a
  franchise.

## 3. Video page -> stream

`GET /video/<id>/<slug>/` [LIVE 200, ~70 KB]:
```html
<h1>Peni X Magik - Brand New Mate</h1>
<video id="currentvideo" class="video-js …" poster="https://porcore.com/media/thumbs/3096/i101.jpg">
<source src="https://fgnvideo.cdn.serverable.com/<32 hex>-,1080hd,360,480,720,.mp4.urlset/master.m3u8" type="application/x-mpegURL"/>
…
<p id="longD" class="small">description…</p>
<p><strong>Categories:</strong> <a class="label label-default" href="https://porcore.com/show/3d">3d</a> …</p>
<div class="related video-related …"><strong>Related videos:</strong><div class="videoitems"> 12 × div.onevideothumb </div></div>
```
* Stream: `video#currentvideo source[src]` (type `application/x-mpegURL`). Older uploads have fewer sizes
  (`-,360,480,480hd,.mp4.urlset`).
* master [LIVE 206 `application/vnd.apple.mpegurl`, no cookie, no Referer]: `#EXT-X-STREAM-INF` 1920x1080 /
  640x360 / 852x480 / 1280x720 with absolute variant URLs `…-720.mp4/index-v1-a1.m3u8`; I-frame playlists too.
* variant [LIVE 206]: VOD, 1.6-3.2 s `segment-N-v1-a1.ts`, no `#EXT-X-KEY`.
* segment [LIVE 206 `video/MP2T`, TS sync byte 0x47 first]. `cache-control: max-age=600` on playlists; no expiry
  seen in the URLs.
* Tags: `a.label.label-default[href*="/show/"]`, slug = last path part. Description `#longD`.
* Related: the 12 `div.onevideothumb` in `.related` (same item markup).
* The player sets a `preroll` cookie for ads only; not needed.

## 4. App notes

* `VideoStream(master, "hls")`; the player's default `Http.imageClient` works (no headers needed).
* `series`: none (one-off clips). `details().episodes` = this video + its related videos.
* Exclude tags cannot be applied (lists carry no tags); include tags are added as search words.
