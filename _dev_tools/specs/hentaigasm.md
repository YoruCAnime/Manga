# hentaigasm.com video source spec (tested 2026-11-02)

Tested live on 2026-11-02 from England with Python `requests`, anonymous, no cookies, one request every 1.5 s
(about 42 requests in total, never a whole video). Probe scripts: `_dev_tools\probe_hentaigasm\fetch.py` (one GET,
saves to `out\`, `--range` = first 64 KB only, `--referer`, `--ua`), `parse_list.py` (list-page parser, prints ids /
tags / views only), `mp4head.py` (reads the MP4 boxes in a 64 KB head). Saved replies in `probe_hentaigasm\out\`.
Every fact is marked **[LIVE <code>]** (seen in this probe) or **[GUESS]**.

## 0. The short version

* **Feasible with plain OkHttp, no WebView, no cookies, no Referer.** The site is a plain WordPress blog (Apache, no
  Cloudflare) and its **WordPress REST API is open**: `https://hentaigasm.com/wp-json/wp/v2/...` [LIVE 200]. One REST
  call returns a page of posts with title, slug, link, date, genre ids, series (category) ids, view count AND the
  post HTML that holds the video URL and poster. The HTML pages work too (Jsoup) and are needed only for the
  "most viewed" sort.
* **Not blocked from the UK**: `GET /` → 200, no 451, no Online Safety Act page, no age gate, no challenge [LIVE 200].
* **Stream = one direct progressive MP4 per episode** (H.264 `ftypisom`, `moov` at the front = fast start), on
  `https://hgasm1.com/` (or `hgasm3.com` for some posts), plain nginx, Range supported, **not signed, no expiry, no
  Referer needed** [LIVE 206]. One quality only per episode: 1280x720 for recent posts, 640x360 for 2013-era posts
  [LIVE 206]. No HLS, no iframe, no API call, no JS needed. ExoPlayer can play the URL as is.
* **Series / next episode**: each post's WordPress **category** is its series (`/hentai/<series-slug>/`); list a series
  with `posts?categories=<id>` [LIVE 200]. Episode number comes from the title (`... 6 Subbed`).
* **Content safety is weak on this site**: there are **no** loli / shota / minors tags at all (only 34 genres, none of
  them about age). See section 7 for what the app can still do.
* Hardest parts: (1) file URLs contain raw spaces and `!` and must be percent-encoded; (2) no minors tags, so
  filtering must be by a series / title blocklist; (3) "Latest" is polluted by re-dated old posts (use category
  `new-hentai-release` for real new episodes).

Headers used: `User-Agent: Mozilla/5.0 (Linux; Android 14; Pixel 7) ... Chrome/130 Mobile Safari/537.36` and also
tested `okhttp/4.12.0` [LIVE 200, same HTML structure]. A mobile UA gets `Set-Cookie: multi-device-switcher=smart`
(a theme switcher); the list markup was identical with both UAs [LIVE 200]. The cookie is not needed.

---

## 1. Listing

### 1a. REST (recommended for everything except "most viewed")

`GET https://hentaigasm.com/wp-json/wp/v2/posts?per_page=36&page=N&_fields=id,date,slug,link,title,content,categories,tags,views`

* [LIVE 200] `application/json`, headers `X-WP-Total: 2871`, `X-WP-TotalPages`, and `Link: <...page=2>; rel="next"`.
  `per_page` 1-100 (WordPress maximum) [LIVE 200 with 100]. Page past the end → WordPress 400
  `rest_post_invalid_page_number` [GUESS, standard WP]. Use `X-WP-TotalPages` to stop.
* `_fields` trims the reply (36 posts with `content` ≈ 90 KB; without `content` ≈ 6 KB per 36) [LIVE 200].
* Default order = `date` desc. Allowed `orderby` (from the 400 error text) [LIVE 400]: `author, date, id, include,
  modified, parent, relevance, slug, include_slugs, title`. **`orderby=views` is rejected** (400
  `rest_invalid_param`), so "popular" must come from HTML (1b).
* Trimmed real item (content shortened) [LIVE 200]:
```json
{"id":3425,"date":"2026-10-30T13:00:38","slug":"mahou-touki-lilustear-2-subbed",
 "link":"https://hentaigasm.com/mahou-touki-lilustear-2-subbed/",
 "title":{"rendered":"Mahou Touki Lilustear 2 Subbed"},
 "content":{"rendered":"<p ...>Subbed this on the same day as release!</p> ... <video id=\"my-video\" controls ...>\n    <source src=\"https://hgasm1.com/....mp4\" type=\"video/mp4\" />\n</video> ... posterImage: \"https://hgasm1.com/thumbnail/....jpg\", ..."},
 "categories":[918],"tags":[6,10,789,11,801,19,93,23],"views":312099}
```
* Field mapping:
  * **title** = `Jsoup.parse(title.rendered).text()` (may hold HTML entities) [GUESS for entities; all 100 tested were plain].
  * **video page URL** = `link` (also `https://hentaigasm.com/<slug>/`). Short link `https://hentaigasm.com/?p=<id>`.
  * **thumbnail / cover** = the `posterImage: "..."` string inside `content.rendered` (section 3).
  * **series** = the category ids minus the non-series ones (section 4); name from `/wp/v2/categories`.
  * **episode** = from the title (section 4).
  * **genres** = `tags` ids → names via the fixed table in section 2.
  * **views** = `views` (integer, a custom field the site exposes) [LIVE 200]. `excerpt.rendered` = a short blurb
    ("Subbed this within 2 hours of release ...") — site chatter, not a synopsis; there is **no synopsis**.
  * `date` = local time (site is UTC-4); `date_gmt` also present. **Warning**: the site re-dates old posts to keep
    the front page busy (post id 4353 is dated two days ago; posts with ids around 1000 show "4 days ago") [LIVE 200].
    So date order ≠ release order. `featured_media` is always 0 (no WP thumbnails) [LIVE 200].

### 1b. HTML lists (needed for "Most viewed"; also fine for everything else)

| feed | URL | notes |
|---|---|---|
| Home / latest | `https://hentaigasm.com/` , `/page/N/` | [LIVE 200] page 1 = 39 items: 14 `sticky` posts first, then 25 by date. Pagination says 80 pages |
| **Most viewed (popular)** | `/?orderby=views` , `/page/N/?orderby=views` | [LIVE 200] 36 per page, all-time view count desc (13.2M, 12.9M, 10.0M ...) |
| Random | `/?orderby=rand` | [LIVE link seen in the sort bar; not fetched] |
| **New releases** | `/hentai/new-hentai-release/` (category id **1475**, 94 posts) | [LIVE 200 for the category via REST]. Best "Latest" feed: REST `posts?categories=1475` |
| Uncensored | `/genre/uncensored/` | top-bar link [LIVE seen] |
| Genre | `/genre/<slug>/page/N/` , add `?orderby=views` for popular in a genre | [LIVE 200] both |
| Series | `/hentai/<series-slug>/` | [LIVE 200] |
| Search | `/?s=<q>` , `/page/N/?s=<q>` | [LIVE 200] |

There is **no "trending this week"** feed; only all-time views. For a "trending" row the app could take
`posts?categories=1475` (or the newest REST page) and sort it by the `views` field itself [GUESS, useful approximation].

Jsoup selectors (checked on home, views, search, series, genre pages) [LIVE 200]:
* list root: `#content .loop-content` ; items: `div.item.item-post` with `id="post-<id>"`.
  Only parse inside `.loop-content`: a video page also has a `.related-posts` box with the same item markup.
* classes on the item div: `sticky`, `category-<series-slug>` (one or more, includes `category-new-hentai-release`),
  `tag-<genre-slug>` (the genres! no extra request needed).
* link + title: `a.clip-link[href]`, title = its `title` attr (or `h2.title a` text). `data-id` = post id.
* image: `a.clip-link img[src]` = `https://hgasm1.com/preview/<Title>.jpg|.webp` (192x108; the `.webp` ones are
  **animated** 32-frame previews) [LIVE 200].
* views: `p.stats .views i.count` ("90.67K", "13.22M"); likes `p.stats .likes i.count`; comments `.comments i.count`.
* time: `p.meta span.time` (relative, "2 days ago").
* pagination: `.wp-pagenavi a.nextpostslink[href]` (absent on the last page), `a.last[href]` (= `/page/80/`).
  Page size 36 (home page 1 is longer because of stickies). **De-duplicate by post id** across pages (stickies) [GUESS].

## 2. Search and genre filters

* **REST search**: `posts?search=<q>&per_page=36&page=N&_fields=...` [LIVE 200, "sister" → 49 hits]. Searches title +
  content (WP default), relevance-ish order unless `orderby=date` [GUESS for order].
* **HTML search**: `/?s=<q>` , `/page/N/?s=<q>` [LIVE 200, 36 per page, same item markup].
* **Genre filter (REST)**:
  * one or more genres, **OR**: `posts?tags=6,10` [LIVE 200, 1801].
  * **AND**: `posts?tags[terms]=6,10&tags[operator]=AND` (brackets URL-encoded) [LIVE 200, 871]. (`tax_relation=AND`
    does NOT do this: still 1801 [LIVE 200].)
  * exclude: `tags_exclude=853` [LIVE 200], `categories_exclude=<ids>` [GUESS, standard WP; use it for the blocklist].
  * can be combined with `search=` [GUESS, standard WP].
* **Series list**: `GET /wp-json/wp/v2/categories?per_page=100&page=N&_fields=id,name,slug,count,parent` [LIVE 200]:
  1 411 categories, 15 pages, all `parent: 0`. Name search: `categories?search=<q>` [LIVE 200].
* **All genres** (`GET /wp-json/wp/v2/tags?per_page=100` → exactly 34, the complete list, 2026-11-02) [LIVE 200].
  URL is `/genre/<slug>/`. id = slug (count):

| id | slug | id | slug | id | slug |
|---|---|---|---|---|---|
| 781 | 3d (123) | 29 | incest (284) | 48 | swimsuit (186) |
| 39 | anal (434) | 61 | maid (156) | 60 | tentacle (126) |
| 6 | blowjob (1520) | 12 | masturbation (289) | 780 | uncensored (589) |
| 51 | bondage (294) | 93 | monster (141) | 319 | vanilla (1033) |
| 7 | boobjob (661) | 764 | motion-anime (332) | 8 | virgin (530) |
| 138 | cheating (278) | 98 | nurse (59) | 767 | x-ray (176) |
| 10 | creampie (1152) | 853 | preview (18) | 26 | yuri (238) |
| 31 | demon (99) | 23 | rape (792) | 11 | exhibition (502) |
| 789 | exclusive (409) | 799 | school (219) | 19 | gangbang (639) |
| 801 | fantasy (219) | 1024 | season (42) | 35 | harem (570) |
| 58 | footjob (87) | 1034 | series (477) | 27 | sex-toy (339) |
| 94 | futanari (65) | | | | |

Notes [GUESS from titles / tag combinations]: `season` = a whole season joined into one long video; `series` = seen
on the newer "Uncensored" multi-episode releases; `motion-anime` = motion comics; `3d` = 3D CG. `preview` posts
still carry a normal `<source>` MP4 URL (on hgasm3.com) [LIVE 200 for the HTML; that file was not range-checked].
Display names = capitalised slug ("Sex Toy", "X-Ray", "3D").

## 3. Video page → playable stream

Found in the post HTML (REST `content.rendered`, or the page's `.entry-content`) [LIVE 200]:
```html
<video id="my-video" controls ...>
    <source src="https://hgasm1.com/.Sister Breeder 6 Subbed.mp4" type="video/mp4" />
</video>
<script> var player = fluidPlayer("my-video", { layoutControls: {
            posterImage: "https://hgasm1.com/thumbnail/.Sister Breeder 6 Subbed.jpg", ... }, vastOptions: {...ads...} });</script>
<a class="btn btn-pink" href="https://hgasm3.com/.Sister Breeder 6 Subbed.mp4" download ...>DOWNLOAD</a>
```
* **Stream** = `video#my-video source[src]` (Jsoup on `content.rendered`). Fallback = `a[download][href]`
  (same file on `hgasm3.com`, sent with `Content-disposition: attachment`, also plays) [LIVE 206 both].
  Regex fallback: `<source src="([^"]+\.mp4)"`.
* Hosts seen: `hgasm1.com` (new posts), `hgasm3.com` (2013 posts and the "preview" posts) [LIVE 206]. File names are
  the post title, sometimes with a leading `.` (newer uploads); never rebuild them, always read them from the HTML.
* **Percent-encode the path**: the `src` holds raw spaces, `!`, etc. Build with
  `url.toHttpUrlOrNull()` / `HttpUrl.Builder` (OkHttp encodes spaces as `%20`), never string-concat into ExoPlayer
  unencoded. Tested form: `https://hgasm1.com/.Sister%20Breeder%206%20Subbed.mp4` [LIVE 206].
* Range check (`Range: bytes=0-65535`, nothing more downloaded) [LIVE 206]:
  * new post: `206`, `Content-Type: video/mp4`, `Content-Range: bytes 0-65535/159667077` (160 MB), nginx/1.18,
    `ETag`, first bytes `00 00 00 18 66 74 79 70 69 73 6f 6d` (`ftyp isom`), `moov` box at offset 24 (fast start),
    video track **1280x720**, duration 975 s.
  * 2013 post (`hgasm3.com/<Title>.mp4`, no dot): `206`, 124 MB, `ftyp isom`, `moov` first, **640x360**, 1736 s.
* **Qualities**: one file per episode, no quality choice [LIVE 200: one `<source>` only].
* **Headers**: none needed. Same `206` with and without `Referer: https://hentaigasm.com/` [LIVE 206 both]. No
  cookies. **Not signed, no expiry** (plain static path, `Last-Modified` + `ETag`) [LIVE 206]. Safe to store the URL
  in history / favourites [GUESS that files are never moved; the hgasm1/hgasm3 split suggests they move rarely].
* The player is Fluid Player with VAST pre/pause/post-roll ads from `s.magsrv.com`; the app skips all of that by
  playing the MP4 directly. No subtitles file: subs are burned in ("Subbed"); "Raw" = no subs.

**Images** [LIVE 200, no Referer]:
| image | where | size |
|---|---|---|
| **cover / poster (best)** | `posterImage: "..."` in the content → `https://hgasm1.com/thumbnail/<file>.jpg` | 620x349 JPEG for new posts (39 KB); only 192x108 for 2013 posts |
| grid preview | list HTML `a.clip-link img` → `https://hgasm1.com/preview/<file>.jpg|.webp` | 192x108; `.webp` = animated |
| (broken) | `<link rel=preload href="https://hentaigasm.com/thumbnail/<Title>.jpg">` in the page head | 404, ignore |

Use the poster for cards (16:9, landscape) and the animated preview only if the app wants hover/long-press previews.

## 4. Series grouping (next / previous episode)

* Series = WordPress category. Every post has 1-3 category ids [LIVE 200]; one is the series, others can be
  **non-series buckets**: `1475 new-hentai-release` [LIVE 200]. Treat a category as "not a series" if it is in a small
  fixed list (start with 1475) [GUESS: there may be more buckets; check categories with very high counts — the
  biggest real series have 12 posts, `new-hentai-release` has 94].
* All episodes of a series: `GET /wp-json/wp/v2/posts?categories=<id>&per_page=100&_fields=id,slug,link,title,content,views`
  [LIVE 200; e.g. category 1571 → 6 posts]. HTML: `/hentai/<series-slug>/` [LIVE 200, newest first, no paging for
  small series].
* Episode number: parse the title with
  `^(?<series>.+?)\s+(?:Season\s+)?(?<ep>\d+)(?:\s+3D)?\s+(?<kind>Subbed|Raw|Dubbed|Preview)$` (case-insensitive) —
  matched 100/100 recent titles [LIVE 200]. Sort by `ep`; fall back to post `id` order (episodes are uploaded in order).
  Censored and **Uncensored** releases are **separate series** ("<Name>" vs "<Name> Uncensored"); keep them apart, but a
  "switch to uncensored" link could match the names [GUESS].
* The video page also has WordPress post navigation inside the series: `nav.post-navigation .nav-previous a[rel=prev]`
  ("« PREV EPISODE") and `.nav-next a[rel=next]` [LIVE 200 for prev; next is standard WP markup, GUESS].
* `season` posts (genre 1024) are a whole season in one video; show them but do not count them as an episode
  [GUESS for how to present].

## 5. Blocking

* No Cloudflare (`Server: Apache` on the site, `nginx/1.18.0 (Ubuntu)` on the file hosts), no challenge page, no
  JS check, no cookies required [LIVE 200 / 206].
* **No UK block**: home, video pages, REST, images and MP4s all answered normally from England; no 451, no Online
  Safety Act / age-verification page [LIVE 200 / 206].
* Responses carry ad scripts (`a.magsrv.com`, `s.zlinkp.com`) and an outbound ad link at the top of every post
  ("HENTAIGASM EXCLUSIVE: UNCENSORED HENTAI" → gamingadlt.com). Strip all of it; never load the page in a visible
  WebView.

## 6. Rate limits and WebView

* No rate-limit headers (`X-RateLimit-*`, `Retry-After`) and no 429 in ~42 requests at 1.5 s spacing [LIVE 200].
  Unknown hard limit; be polite anyway: one queue, ≥ 1 s between page / REST calls, cache list pages for a few
  minutes and series lists for a day. MP4s are big (100-200 MB) and served with Range; let ExoPlayer stream them
  (no full downloads in the background).
* REST replies send `X-Robots-Tag: noindex`, `Allow: GET` [LIVE 200]. It could be switched off by the owner one day
  [GUESS]; the HTML fallback (sections 1b / 3) covers every feature, so keep both parsers.
* **No WebView is needed** for anything [LIVE]: lists, search, filters, the stream URL and images are all in
  static HTML / JSON.

## 7. Content safety (minors)

* **The site has no minors tags.** The full tag list is the 34 genres in section 2 (none is loli / shota / lolicon /
  shotacon / toddler / child) [LIVE 200]. Post search: `posts?search=loli` → 0 hits [LIVE 200].
* Term search found one series whose name contains "shota": category **1329** (1 post, id **4423**) [LIVE 200].
  It was not opened or looked at. Put it on the app's built-in blocklist.
* Because the site does not tag such content, the app should (all ids / words below come from this probe):
  1. Keep a built-in **series blocklist** (`categories_exclude=1329` on every REST list/search call, and drop items
     with `category-<blocked-slug>` in HTML lists). Start with id 1329.
  2. Drop any post whose title or series name matches (case-insensitive) `loli|lolicon|shota|shotacon|toddler|
     \bchild(ren)?\b|kid\b|elementary|shougaku|shogaku|youjo|yojo|\bjs\b|chuugaku|chugaku` — before anything is
     shown or prefetched [GUESS: word list; the site uses romaji Japanese titles]. Note "Bishoujo" ("pretty girl")
     contains "shoujo" but is not an age word, so do not block bare "shoujo".
  3. Re-check at app start / weekly with `categories?search=loli`, `categories?search=shota`, `posts?search=loli`,
     `posts?search=shota` and add new ids to the blocklist (cheap: 4 small REST calls).
  4. Refuse to play a deep-linked post whose `categories` or title hit the list.
