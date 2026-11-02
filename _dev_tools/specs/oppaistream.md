# oppai.stream video source spec (tested 2026-11-02)

Tested live on 2026-11-02 (about 00:12-00:20 UTC) from England with Python `requests`, anonymous, about one request
every 1.5-2 s, 46 requests in total. Probe scripts: `_dev_tools\probe_oppaistream\probe.py` (steps `base`, `get`,
`search`, `count`, `post`, `range`), `filters.py` (filter lists -> `out\filters.json`), `ebml.py` (reads the WebM
header of a 64 KB head), `fresh.py` (cookie-less okhttp-UA checks). Raw responses in `probe_oppaistream\out\`
(git-ignored). Tags: **[LIVE nnn]** = seen in this probe with that HTTP status, **[GUESS]** = read from JS / not
proven.

## 0. The short version

* **Not blocked from the UK.** `GET https://oppai.stream/` → 200 from the Cloudflare London edge (`CF-RAY …-LHR`),
  no 451 and no Online Safety Act page **[LIVE 200]**. No Cloudflare challenge on any page, API call or video file
  **[LIVE]**. (Turnstile is only loaded for the login / register forms.)
* **Plain OkHttp + Jsoup is enough; no WebView needed.** There is no JSON API: pages and the search XHR
  (`POST /api/search.php`) return **server-rendered HTML fragments**, parsed with Jsoup. No cookies, no tokens; the
  okhttp UA works **[LIVE 200]**.
* **Stream = one direct WebM file per episode** (VP9 video + Opus audio + an embedded English WebVTT track),
  `https://oppai.stream/videos/<folder>/ep.webm`, given in the watch page's JSON-LD `contentUrl`. Not signed, does
  not expire (`Cache-Control: max-age=31536000, public, immutable`), Range requests work (206), no Referer needed
  **[LIVE 206]**. External subtitles: `https://oppai.stream/videos/<folder>/sub_en.vtt` **[LIVE 200]**.
* **Only one quality per episode.** The "4K" label is the site's: the tested file is 2558x1440 VP9 at about 3.9 Mbit/s
  (590 MB for 20 min) **[LIVE]**. No 720p/1080p alternative is served (the DASH multi-quality code exists but is
  switched off, `DASH_ENABLED = false`).
* **Hardest parts:** (1) text search ignores the blacklist (an SQL precedence bug, section 2.3), so the minors filter
  must also be applied by the app; (2) the single high-bitrate 1440p/4K VP9 file (heavy on old phones and on mobile
  data); (3) everything is HTML scraping, so markup changes will break the parser.

Headers used: `User-Agent: Mozilla/5.0 (Linux; Android 14; Pixel 7) … Chrome/130 Mobile Safari/537.36` and also
`okhttp/4.12.0` (both fine). For the search XHR send `Referer: https://oppai.stream/search` and
`X-Requested-With: XMLHttpRequest` to look like the site (the cookie-less okhttp test sent neither and still got
results **[LIVE 200]**). The site sets `PHPSESSID` and `cv=1` cookies on every page; they are not needed.

---

## 1. Lists

### 1.1 Episode card (the same markup everywhere: home, /popular, /new-releases, /series/…, /category/…, search, watch-page side lists)

```html
<a href="https://oppai.stream/watch/Hebi%20to%20Kumo-1/from/recent-home" class="episode " epid="1"
   data-folder="Hebi-to-Kumo-1" draggable="false">
  <div class="cover-wrap">
    <img class="cover lazy" data-src="https://oppai.stream/videos/Hebi-to-Kumo-1/thumbnail_1.png?v=1.00" alt="…">
    <noscript><img class="cover" src="…same…"></noscript>
    <div class="ep-tags bottom-tags"><div class="… ep-time"><p class="s5 white">20:17</p></div></div>
  </div>
  <div class="under-cover">
    <p class="s4 white bold episode-title">Hebi to Kumo</p>
    <p class="s4 white bold episode-number">1</p>
    <div class="… views …"><p class="s6 white">51K</p></div>
    <div class="… likes …"><p class="s6 white">177</p></div>
  </div>
</a>
```
Jsoup: `a.episode[data-folder]` (the class can be `episode`, `episode trending-center`, `episode other-episodes`,
`episode studio-episodes`…).

| field | where | notes |
|---|---|---|
| video page URL | `href`, cut off `/from/<source>` | canonical `https://oppai.stream/watch/<Series Title, %-encoded>-<ep>` **[LIVE 200]** (the `/from/…` suffix is only for the site's stats; both forms load) |
| episode key | `data-folder` | e.g. `Natsu-to-Hako-2`. Use it as the stable id. Do not rebuild it from the title: `:` → `-DoppelPunkt-`, `?` → `-FrageZeichen-`, `!` → `_`, `-` and `~` in titles → `~` were all seen **[LIVE]** |
| series name | `p.episode-title` | the title without the episode number |
| episode number | `p.episode-number` (also the `epid` attribute) | |
| thumbnail | `img.cover` `data-src` (search results use `src`; `noscript img` always has `src`) | `https://oppai.stream/videos/<folder>/thumbnail_<ep>.png?v=1.00`: a **1280x720 PNG, 201 KB** **[LIVE 200]**, 16:9 landscape. This is the only (and best) picture; there is no portrait poster. Cache 3 days (`max-age=259200`) |
| duration | `.ep-time p` | `mm:ss` |
| views / likes | `.views p`, `.likes p` | "51K" style |
| tags / genres | **not in cards** | only on the watch page (section 3.3). Lists can be kept clean with the search blacklist (section 2) |

The site's internal episode number id (`var episodeId = 8168`) appears only on the watch page.

### 1.2 Feeds

| feed | request | notes |
|---|---|---|
| **Latest (paged)** | `POST /api/search.php` with `order=recent` (section 2) | best "Latest": 15 per page, any page, blacklist works **[LIVE 200]** |
| **Popular (paged)** | `POST /api/search.php` with `order=views` (all-time views) or `order=rating` (most likes) | **[LIVE 200]** |
| Trending | `GET https://oppai.stream/` → `#trending-wrap a.episode` | 6 items (`/from/trending-home`), not paged **[LIVE 200]** |
| Home recent / random | `#recent-wrap a.episode` (4), `#random-wrap` / `data-category="random"` (8) | **[LIVE 200]** |
| Most popular page | `GET /popular` → 48 cards, no paging | **[LIVE 200]**; ordering not documented ("Most Popular Hentai") **[GUESS: views over a recent period]** |
| New releases page | `GET /new-releases` → 48 cards, no paging | **[LIVE 200]** |
| Category page | `GET /category/<slug>` (e.g. `vanilla`) → 12 "Popular <Tag> Episodes" + "More Categories" | **[LIVE 200]**; only 12 items, so use search `tags=` instead |
| Studio page | `GET /studio/<Name>` (`/studio/Pink Pineapple`) | **[GUESS]** like category; use search `studios=` |
| Year page | `GET /year/2026` | **[GUESS]** |
| Series index | `GET /series` → 636 `a[href^=https://oppai.stream/series/]`, one page, A-Z hub tabs, no pictures | **[LIVE 200]**; the series slug format varies (`15%20Bishoujo%20Hyouryuuki` vs `1ldk-jk-ikinari-doukyo-micchaku-hatsu-ecchi`), so always take series URLs from the page |

The /popular, /new-releases, home and category pages have **no blacklist**; drop cards there with the app's own
deny-list (section 7) or prefer the search-based feeds, which apply the blacklist on the server.

## 2. Search: `POST https://oppai.stream/api/search.php` (form-encoded) → HTML fragment

Found in `/js/search.js` (`performSearch()`). Form fields (all strings) **[LIVE 200]**:

| field | value | notes |
|---|---|---|
| `text` | free text | matched with `LIKE %text%` on `title`, `alternate` (alt titles), `tags`, `studio`. **Fewer than 3 characters is ignored** (the clause disappears) **[LIVE]** |
| `tags` | comma list of genre names, e.g. `Vanilla,Uncensored` | **AND** (every tag must match), `tags LIKE %name%` each, case-insensitive **[LIVE]** |
| `blacklist` | comma list, e.g. `Loli,Shota` | exclude: `tags NOT LIKE %name%` each **[LIVE]** (but see 2.3) |
| `studios` | comma list of studio names | **OR** between studios **[LIVE]** (`Pink Pineapple,Bunnywalker` → 224) |
| `order` | `recent` (default), `old`, `az`, `za`, `views`, `views-asc`, `rating`, `rating-asc`, `random` | labels: Newest, Oldest, A-Z, Z-A, Most Views, Least Views, Most Likes, Least Likes, Random. `recent` = `ORDER BY released DESC, ep DESC` |
| `page` | 1-based | `OFFSET (page-1)*15` |
| `limit` | ignored | the server always uses **15** (sent 16 and 40, got `LIMIT 15`) **[LIVE]** |
| `character_filters` | JSON object, e.g. `{"boobs":["Big"],"hair-color":["Black"]}` | "Female Details" filters (2.2). Send `{}` when unused |
| `classes` | `smaller-episode-gap` | only CSS; optional |

Reply: `<div class='results-num' style='display: none;'>1404</div>` (total hits), then a hidden debug `<div>`
with the SQL `WHERE` clause (handy for testing; do not rely on it), then 0-15 `a.episode` cards (section 1.1).
Pages = `ceil(results-num / 15)`. Totals seen: everything 1464; with `blacklist=Loli,Shota` 1404. With `text`
**and** `tags` set together the reply had no `results-num` and no cards **[LIVE 200]**, so do not combine them
(**[GUESS]** the same OR/AND precedence problem).

The site's own URL form `https://oppai.stream/search?s=…&categories=…&blacklist=…&studios=…&order=…&page=…&cf=…`
only fills the page; the results always come from the POST.

Quick search (type-ahead): `POST /api/search-quick.php` with `term=<text>` → small HTML with
`a.search-result-hit` (title + watch URL), no pictures, no tags **[LIVE 200]**. Not useful beyond suggestions.

### 2.1 Genre / tag names (`out\filters.json`)

Tags are referenced by **name** (as below; case does not matter because the server uses `LIKE`). Slugs (lower case,
no spaces) are only for `/category/<slug>` URLs and the watch-page tag links.

* **Generic** (include + exclude): 4k, 3D, Ahegao, Beach, Blood, Censored, Comedy, Fantasy, Filmed, Gore, Harem,
  Horror, Incest, Lactation, Mind Break, Mind Control, Monster, POV, Plot, Softcore, Tentacle, Uncensored, Vanilla,
  Watersports, X-Ray, Yaoi, Yuri.
* **Action** (include + exclude): Anal, Armpit Masturbation, BlowJob, BoobJob, Cowgirl, Cunnilingus, Doggy, FootJob,
  Groping, HandJob, Masturbation, Missionary, Prone Bone, Rimjob, Threesome, Double Penetration, Tripple Penetration,
  Side Fucking, 69, Golden Shower, BDSM, Bondage, Creampie, Facial, Gangbang, Girls Only, NTR, Orgy, Public Sex, Rape,
  Reverse Gangbang, Reverse Rape, Toys, Voyeurism, Scat, Inflation.
* **Girl** (the site offers these only in the blacklist, but `tags=` accepts any name): Big Boobs, Elf, Vampire,
  Demon, Small Boobs, Loli, Milf, Dark Skin, Dominant Girl, Black / Blonde / Blue / Brown / Green / Purple / Pink /
  Red / White Hair, Gyaru, Cosplay, Glasses, Inverted Nipples, Maid, Nurse, School Girl, Succubus, Short Hair,
  Swimsuit, Teacher, Futanari, Nekomimi, Pregnant, Tsundere, Virgin, Muscles, Ponytail, Tomboy.
* **Boy** (blacklist only on the site): Old, Shota, Trap, Ugly Bastard.
* Also seen on episodes: `hd`, `stepmom`, `stepsister`, `stepbrother`, `removed` (category slugs from `/categories`).
* **Studios**: 291 values in the search page's studio box (`.box-studio .filter-option[data-value]`), some combined
  ("Active,Media Blasters"); load the list from `GET /search` once and cache it.

### 2.2 "Female Details" filters: `GET /api/female-filters.php` → HTML **[LIVE 200]**

`.filter-option[data-key][data-value]`; send as `character_filters={"<key>":["<value>",…]}` (include only, AND across
keys; one combined test returned 1 hit). Keys and values:
race (Human, Elf, Demon, Succubus, Animal, Other), age (Teen, Adult, Milf, Cougar (Old), and a minors value, see 7),
type (Teen, Milf, Other, and a minors value), boobs (Big, Medium, Small, Flat), weight (Skinny, Regular, Fat),
skin (White, Brown, Black), hair-color (Red, Pink, Ginger (Orange), Blonde (Yellow), Green, Blue, Purple, Brown, Black,
White), hair-length (Long, Short), hair-style (Down, Ponytail, Twintails, Bun, Wavy, Straight), personality (Tomboy,
Tsundere, Yandere, Nerd, Diva, Mature, Horny, Shy, Gyaru), clothes (Casual, Cosplay, Schoolgirl, School Swimsuit,
Regular Swimsuit, Teacher, Maid, Office, Nurse, Nun, Latex, Idol, Kimono, Pajama, Night Gown), accessories (Glasses,
Handcuffs, Gag, Rope, Ears, Tail), relationship (Friend, Girlfriend, Wife, Mom, Stepmom, Sister, Stepsister,
Daughter, Stepdaughter, Aunt, Cousin, Relative, Teacher, Student).

### 2.3 The text-search bug (important for the minors filter)

With `text` set, the server builds
`(title LIKE ? OR alternate LIKE ? OR tags LIKE ? OR studio LIKE ? AND (tags NOT LIKE ? …))`: the `AND` binds only to
the `studio` term, so **the blacklist is ignored for anything matched by title, alternate title or tags**
**[LIVE 200]**: `text=the` gave 289 with and without `blacklist=Loli,Shota`, and `text=shota` with
`blacklist=Shota` still returned 60 hits. Without `text` the blacklist is wrapped correctly and works.
So for text search the app must drop results itself (section 7).

## 3. Watch page: `GET https://oppai.stream/watch/<Title>-<ep>` → HTML **[LIVE 200]**

404 (146 B, no player) for an unknown episode **[LIVE 404]**.

### 3.1 The stream

Three equivalent places, in order of preference:
1. **JSON-LD** in `<script type="application/ld+json">` (`@graph[0]`, `@type: VideoObject`):
   `"contentUrl":"https://oppai.stream/videos/Hebi-to-Kumo-1/ep.webm"`, plus `name`, `description` (cut at ~200
   chars), `thumbnailUrl[0]`, `duration` (`PT20M17S`), `uploadDate`; and `@graph[1]` (`TVEpisode`):
   `episodeNumber`, `partOfSeries {name, url}` **[LIVE]**.
2. Inline script vars: `var folder = "Hebi-to-Kumo-1"; var storageBase = "https:\/\/oppai.stream\/videos";
   var ep = "1"; var res = "4k-2"; var episodeId = 8168;` **[LIVE]**.
   URL rule from `/js/watch.js` `getVideoURL()`: `storageBase + "/" + encodeURIComponent(folder) + "/ep." + ext`,
   `ext = "mp4"` if `res` has `4k-1`, `"webm"` if `4k-2` (format codes `1 = mp4`, `2 = webm`). Every page seen had
   `res = "4k-2"`; `ep.mp4` for such an episode is 404 **[LIVE 404]**. Fallback in the JS when `res` is missing:
   `{720: "mp4"}` **[GUESS]**.
3. `<video id="episode">` has **no** `src` in the HTML (the JS sets it), so do not look there.

File check (Range `bytes=0-65535`, never more) **[LIVE 206]**:
```
206 video/webm  Content-Range: bytes 0-65535/590283504  Accept-Ranges via 206
Cache-Control: max-age=31536000, public, immutable   etag "6adabfee-232f02f0"   x-storage: s1.myspacecat.pictures
first bytes: 1a 45 df a3 … "webm"  (EBML / Matroska)
Tracks: V_VP9 2558x1440 (display 2560x1440) | A_OPUS jpn | D_WEBVTT/SUBTITLES eng   Duration 1217.89 s
```
A second, older episode: 206, 739 709 574 bytes, VP9 + Opus too **[LIVE 206]**.

* **Headers**: none needed. Same 206 with and without `Referer` **[LIVE]**; no cookies.
* **Expiry / signing**: none; plain static path, one-year immutable cache. Safe to store in history / resume lists.
* **Qualities**: one file. The quality menu on the page offers only "4K". The tested "4K" file is really 1440p. The
  (disabled) DASH path is `https://s2.myspacecat.pictures/<folder>/<res>/E<ep 2 digits>_dash/E<ep>_dash.mpd`
  **[GUESS: not tested; `DASH_ENABLED = false` in watch.js]**. Do not use it.
* **Player (Media3/ExoPlayer)**: `ProgressiveMediaSource` with `MatroskaExtractor` handles VP9 + Opus WebM and seeks
  via Range **[GUESS: standard ExoPlayer support, not run on a device]**. 1440p VP9 needs a hardware VP9 decoder
  (most phones since ~2016 have one) **[GUESS]**. About 3.9 Mbit/s ≈ 1.75 GB per hour: warn on mobile data.

### 3.2 Subtitles

* External: `<track id="sub-en" label="EN" src="https://oppai.stream/videos/<folder>/sub_en.vtt" kind="subtitles"
  srclang="en" default>` inside `<video id="episode">` **[LIVE]**. `GET` → 200 **`application/octet-stream`** (15 KB,
  real WebVTT text) **[LIVE 200]**, so give ExoPlayer `MimeTypes.TEXT_VTT` explicitly in the
  `SubtitleConfiguration`.
* Embedded: the same English WebVTT is also a track inside the WebM (`D_WEBVTT/SUBTITLES`, lang `eng`)
  **[LIVE]**, so ExoPlayer may show it with no extra setup. Use one of the two, not both.
* If an episode has no `<track>`, treat it as raw **[GUESS]**.

### 3.3 Metadata on the watch page

| field | selector | notes |
|---|---|---|
| title | `h1#episode-title` ("Hebi to Kumo Episode 1") or JSON-LD `name` | |
| alternate titles | `h1#episode-title + h2` | English / Japanese titles joined with `↹` |
| release date | `#stats-released p` ("Apr 12, 2026"), JSON-LD `uploadDate` | |
| views / likes / comments | `#stats-views p`, `#stats-likes p`, `#stats-comments p` | |
| studio | `.wrap-genres.tags a[href*="/studio/"] p` | |
| **tags** | `.wrap-genres.tags a[href*="?src=episode-tag"]` → slug from `/category/<slug>` | slugs, lower case, no spaces (`blowjob`, `censored`, `cowgirl`, `hd`, `4k`…). **Only take links with `?src=episode-tag`**: every watch page also has six `a.category-card` links (`/category/<slug>` without `?src=`, the same six on every page, one of them `shota`) **[LIVE]** |
| synopsis | `#episode-description p.s5` (first paragraph; the 2nd and 3rd are SEO text) | |
| episode thumbnail | JSON-LD `thumbnailUrl[0]` | same PNG as the card |
| seek preview frames | `<storageBase>/<folder>/pics<ep if >1>/output_<n>.png` (from watch.js) | **[GUESS]**, not needed |

## 4. Series grouping (next / previous episode)

* **On the watch page**: `div.all-episodes a.episode` = every episode of the same series including the current one
  (`/from/other-episodes`), with `epid` and `data-folder` **[LIVE 200]** (2 cards for a 2-episode series; empty for
  a 1-episode series). Sort by `epid`; next = `epid + 1`, previous = `epid - 1`. No extra request needed.
* **Series page**: JSON-LD `partOfSeries.url` → `GET https://oppai.stream/series/<Series Title>` → "All N Episodes"
  and `a.episode` cards (`/from/series`) **[LIVE 200]**. Use it for a series details screen.
* A guess-free shortcut does not exist: the watch URL `…/<Title>-<ep+1>` usually works, but titles with special
  characters do not map 1:1 to folders, so always use the hrefs.

## 5. Blocking

* No UK block: every page, the search API and the video files answered from the London Cloudflare edge with 200 / 206
  **[LIVE]**. No HTTP 451, no age-verification / Online Safety Act page, no consent wall.
* Cloudflare: `server: cloudflare` everywhere, but no challenge (`cf-mitigated` never seen), not even for the
  cookie-less okhttp client **[LIVE]**. Turnstile is loaded on every page but only used by the login / register
  forms **[GUESS from the script tags]**.
* No VPN / proxy / mirror was used or is needed.

## 6. Rate limits, WebView

* 46 requests at 1.5-2 s spacing: no 429, no rate-limit headers, no `Retry-After` **[LIVE]**. Nothing published.
  Keep about 1 request per second for pages / search; video Range requests are static files on a CDN.
* Page sizes are large (watch page 120-134 KB, /popular 164 KB, /series 138 KB): cache list pages for a few minutes
  and watch pages for a day (only views / likes change).
* **No WebView needed for anything**: the stream URL is in the static HTML (JSON-LD), search is a plain form POST,
  no JS challenge, no signed URLs. A WebView would only be needed if Cloudflare starts challenging, which it did not.
* Watch-page `var userLoggedIn = true` appeared even anonymously; the site's `track-watch.php` / `like-episode.php`
  calls are not needed and should not be sent.

## 7. Hide-by-default (minors)

Tag names / slugs that mark content involving minors. None of these entries were opened; only totals were counted.

| where | name (send in `blacklist`) | slug (watch-page tag link) | episodes now |
|---|---|---|---|
| girl tag | `Loli` | `loli` | 0 (`tags=Loli` → 0) **[LIVE 200]** |
| boy tag | `Shota` | `shota` | 60 (`tags=Shota` → 60; 1464 - 1404 = 60) **[LIVE 200]** |
| Female Details `age` | `Loli` | n/a | 0 (`{"age":["Loli"]}` → 0) **[LIVE 200]** |
| Female Details `type` | `Loli` | n/a | 0 with blacklist on **[LIVE 200]** |

Other values to consider hiding (not minors by definition, the user's choice): girl tag `School Girl`
(`schoolgirl`), clothes `Schoolgirl` / `School Swimsuit`, age / type `Teen`. `Trap` is an adult cross-dressing tag,
not a minors tag.

How to apply them in the app:
1. **Every search POST** gets `blacklist=Loli,Shota` (plus any user blacklist). This works for all searches
   **without** `text` **[LIVE]**.
2. **Text search** (blacklist ignored, section 2.3): keep a local deny-list of `data-folder` values built from
   `POST search.php tags=Shota` and `tags=Loli` (4 pages of 15 today; read only the `data-folder` attributes, never
   show them), refreshed daily, and drop any result whose `data-folder` is in it. As an alternative or extra guard,
   search the text **without** the server blacklist and filter with that same deny-list.
3. **Non-search lists** (home, /popular, /new-releases, series pages, watch-page side lists) have no server filter:
   apply the same `data-folder` deny-list.
4. **Before playing** (and on any deep link): refuse the episode if its watch page has an
   `a[href*="?src=episode-tag"]` whose slug is `loli` or `shota`. This also catches new episodes missing from a stale
   deny-list.
5. Re-check the tag lists now and then (`GET /search` filter boxes, `GET /api/female-filters.php`) for new
   minors-related names.
