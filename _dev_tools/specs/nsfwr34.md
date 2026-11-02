# nsfwr34.com video source spec (tested 2026-11-02)

Tested live on 2026-11-02 from England with Python `requests`, anonymous, no login, about 1 request per 1.1 s
(~45 requests in all). Scripts in `_dev_tools\probe_nsfwr34\`: `probe.py` (single GET / capped Range GET),
`sorts.py` (list URLs: sort / date range / search), `survey.py` .. `survey4.py` (REST API, stream kinds, tags, minors
tags, AND filters), `parse.py` (the parser the app copies; ends with `ALL OK`). Saved replies in
`probe_nsfwr34\out\`. No video was downloaded: playlists only, and at most 64 KB of one segment / one MP4.

Tags: **[LIVE nnn]** = seen in this probe with that HTTP status; **[GUESS]** = not tested.

## 0. The short version

* **WordPress + the StreamTube theme** (custom post type `video`) behind Cloudflare. Every page answered 200 with no
  challenge [LIVE 200, `cf-ray ...-LHR`]. **No age-verification wall and no 451 from England** [LIVE].
* **Plain OkHttp + Jsoup, no WebView, no Referer, no cookies** anywhere (pages, API, pictures, streams) [LIVE].
* **The WordPress REST API is open** (`/wp-json/wp/v2/video`, `video_tag`, `video_category`) [LIVE 200] and each video
  carries a `playbacks` field = the stream address. It cannot sort by views and has no duration / view count.
* **HTML search page** `/?s=<text>&post_type=video&orderby=<sort>&date=<range>` = every list with views, duration,
  thumbnail, tag slugs; it sorts by views with time ranges [LIVE 200].
* **Stream = HLS on Bunny Stream** (`vz-2222604c-50b.b-cdn.net/<guid>/playlist.m3u8`), 480p + 1080p (some 720p),
  plain TS segments, no key [LIVE 200 / 206 `video/mp2t`]. A few old posts are a direct MP4 in `wp-content/uploads`
  [LIVE 206 `video/mp4`]. Some old posts are dead (section 4).
* 5 064 videos, 2 559 tags, 5 categories (2026-11-02) [LIVE].

## 1. Lists (HTML)

URL (page 1 has no `page/N/`):
```
https://nsfwr34.com/page/<n>/?s=<text>&post_type=video&orderby=<sort>[&date=<range>]
```
* `orderby`: `date` (latest), `post_view` (most viewed) [LIVE, views go 1.1M, 887.7K, 641.9K ...], `relevance` (text
  search), `post_like`, `comment_count`, `name` [LIVE 200; `post_like` + `date` did not look sorted by likes].
* `date` (time range, any sort): `today`, `7daysago`, `30daysago`, `this_month`, `this_year` [LIVE 200 for
  `7daysago`, `30daysago`, `this_year`; the others are the site's own filter radio values].
* 16 items a page. **Next page**: `<link rel="next" href=...>` in `<head>` [LIVE]. Past the end: **404** [LIVE 404].
  No results: 200 with 0 items [LIVE].
* `video_tag=` on this URL is ignored [LIVE] (use the API or `/video_tag/<slug>/`).
* Other list pages (20 items, but **no view counts** in their markup): `/video/page/<n>/?orderby=post_view|date`,
  `/video_tag/<slug>/page/<n>/?orderby=...`, `/categories/<slug>/` [LIVE 200]. `date=` is ignored on `/video/`
  [LIVE].

One card (trimmed real markup):
```html
<article class="post-55166 video type-video ... categories-3d-porn video_tag-tifa-lockhart-rule-34 video_tag-blowjob
         video_tag-final-fantasy video_tag-lorgegucas ..." data-embed-url=".../embed/"
         data-thumbnail-image-2="https://vz-2222604c-50b.b-cdn.net/<guid>/preview.webp">
  <a class="post-permalink" title="Tifa having sex on the beach [LorgeGucas]" href="https://nsfwr34.com/video/<slug>/">
    <img src="https://nsfwr34.com/wp-content/uploads/2026/10/<name>-560x315.jpg" ...>
    <div class="video-length badge">03:30</div></a>
  <h2 class="post-meta__title post-title"><a ...>title</a></h2>
  <div class="post-meta__author"><a href="https://nsfwr34.com/author/admin/">...Admin</a></div>
  <div class="post-meta__views"><span class="icon-eye"></span> 106 views</div>
  <div class="post-meta__date"><time datetime="2026-10-31 20:27:49">1 day</time> ago</div>
</article>
```

| field | where | notes |
|---|---|---|
| id | class `post-<id>` | `https://nsfwr34.com/?p=<id>` 301s to the page [LIVE 301] |
| title | `a.post-permalink[title]` | HTML entities; the animator is usually the last `[...]` (skip `[4K]`) |
| page URL | `a.post-permalink[href]` | slug can hold emoji (percent-encoded) |
| thumbnail | `.post-thumbnail img[src]` | 560x315 jpg / webp; full size = strip `-560x315` [GUESS: same WP pattern]; Bunny `thumbnail.jpg` next to `preview.webp` [LIVE 206 image/jpeg] |
| duration | `.video-length` | "03:30"; missing on a few |
| views | `.post-meta__views` | "106 views", "1.1M views", "2.3k views"; missing on some new posts |
| tags | classes `video_tag-<slug>` | slugs only; categories = `categories-<slug>` |
| uploader | `.post-meta__author a` | site accounts (Admin, goddessevajoi ...) |

## 2. REST API (tag filters, tag list)

* `GET /wp-json/wp/v2/video?per_page=20&page=<n>&_embed=wp:featuredmedia&_fields=id,title,link,playbacks,class_list,_links,_embedded`
  [LIVE 200]. Headers `X-WP-Total`, `X-WP-TotalPages`; past the end = **400** `rest_post_invalid_page_number` [LIVE].
  The embedded picture: `_embedded["wp:featuredmedia"][0].source_url` (960x540) and
  `.media_details.sizes["streamtube-image-medium"].source_url` [LIVE]. ~4 KB a video with the embed.
* Filters: `search=<text>`, **AND of tags** `video_tag%5Bterms%5D=<id>,<id>&video_tag%5Boperator%5D=AND` [LIVE: 5
  results, all with both tags] (`video_tag=a,b&tax_relation=AND` is OR [LIVE]), `video_tag_exclude=<ids>`,
  `video_category%5Bterms%5D=<id>` [LIVE: all results in that category, with `video_tag_exclude=36237`], `orderby` = date / title / relevance / id ... (no views).
* `class_list` gives the same `video_tag-<slug>` / `categories-<slug>` classes as the HTML cards.
* Tags: `GET /wp-json/wp/v2/video_tag?per_page=100&orderby=count&order=desc&_fields=id,name,slug,count` [LIVE]: top
  = Overwatch Rule 34 (549), 3d (343), FUTA (305), Hentai (287), Final Fantasy Rule 34 (224) ... Slug lookup:
  `video_tag?slug=final-fantasy,blowjob` [LIVE].
* Categories: `GET /wp-json/wp/v2/video_category?per_page=100` [LIVE]: 2D Rule 34 (140, 1349), 3D Rule 34 (129, 3665),
  4K (261, 1441), Featured (142, 61), Vertical Videos (389, 274).

## 3. Video page

`GET /video/<slug>/` [LIVE 200]:
* Player: `<video-js data-settings="{...}">` (HTML-escaped JSON): `sources[0].src` + `type`
  (`application/x-mpegURL`), `poster` (wp-content jpg), `poster2` (Bunny `preview.webp`) [LIVE].
  Older posts have an `<iframe>` in `.player-embed` instead (section 4).
* Main card: `article#post-<id>` with the same classes as a list card (tags for the minors check) [LIVE].
* Title `h1.post-title`; views `.post-bottom__meta .post-meta__views`; category `.post-categories a[rel=tag]`;
  tags with names `.post-tags a[rel=tag]` (href `/video_tag/<slug>/`); description `.post-content .js-read`;
  duration in the ld+json `"duration":"P0DT0H3M30S"` [LIVE].
* **Related videos**: the `div.widget` whose `.widget-title` says "Related Videos": 6 cards, same markup as lists
  [LIVE].
* A download button links the original MP4 at Bunny `.../<guid>/original?token&download=true&name=...` [LIVE, not
  fetched].

## 4. Streams

`playbacks` (API) / the player on the page, sampled over API pages 1, 5, 15, 25, 35, 45, 51 [LIVE]:

| kind | share | how to play |
|---|---|---|
| `https://vz-2222604c-50b.b-cdn.net/<guid>/playlist.m3u8` | all posts since mid 2023, ~75 % of 2023 | HLS master: `480p/video.m3u8`, `1080p/video.m3u8` (some 720p), TS segments [LIVE 200 / 206 `video/mp2t`]. No Referer / cookie / token [LIVE: with and without Referer] |
| iframe `https://iframe.mediadelivery.net/embed/191273/<guid>` | ~15-28 % of 2022-23 | same pull zone: `https://vz-2222604c-50b.b-cdn.net/<guid>/playlist.m3u8` [LIVE 200, 480p + 1080p] |
| iframe `https://iframe.mediadelivery.net/embed/18848/<guid>` | most of late 2022 | **dead**: the embed shows 404 [LIVE 200 with a 404 page] and the guid is not on the new zone [LIVE 404] |
| iframe `https://rule34video.com/embed/<id>` | ~3-10 % of 2022-23 | not used (rule34video has an age-verification wall from the UK, see `rule34video.md`) |
| `https://nsfwr34.com/wp-content/uploads/....mp4` | rare (2022) | direct MP4 [LIVE 206 `video/mp4`, Range OK] |
| text (`Video removed by DMCA`, a bare number) | a few | nothing to play (the site's own page is empty too [LIVE]) |

Thumbnails: `wp-content/uploads/...-560x315.jpg|webp` [LIVE 206 image/jpeg ×2, image/webp ×1, no Referer]. Bunny
`thumbnail.jpg` [LIVE 206 image/jpeg]. Some file names hold emoji: percent-encode before use.

## 5. Minors

* Tags found with the API's tag search [LIVE]: **`lolicon`** (id 36237, 16 videos), artist names `nekololisama` (23)
  and `deathbylolis` (1). No tags for shota / child / toddler / underage / petite. `kidmo`, `zenokidon`,
  `youngiesed`, `babymoonart` are artist names (not blocked). `hololive` contains "loli" as letters: do not
  substring-match.
* Hide by the `video_tag-<slug>` classes (lists, video page) and `class_list` (API), plus `VideoSafety`; the API
  queries also send `video_tag_exclude=` with the hidden tags' ids (looked up once by slug).

## 6. App mapping

* latest = HTML `orderby=date`; popular = HTML `orderby=post_view` (all time; ranges via `date=`); text search = HTML
  `orderby=relevance`; tag / category / exclude filters = the API (with or without text).
* details = the video page (stream, tag names, description, duration, related = `episodes`).
* `series` = the animator in the title's last `[...]`; `series()` = that animator's tag (when the post has one) through
  the API, else a text search for the name.
