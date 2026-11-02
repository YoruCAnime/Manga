# jestful.net ("JF") manga source spec (tested 2026-11-02)

Tested live on 2026-11-02 from England with Python `requests`, anonymous, no login, about 1 request per second,
39 requests in all. Probe scripts in `_dev_tools\probe_jestful\`: `probe.py` (single GET / capped Range GET),
`search.py` (list / search / genre parameters), `images.py` (covers and pages with / without Referer), `parse.py`
(the selectors the app uses, run against live pages). Saved replies in `probe_jestful\out\` (`requests.log` lists
every call). Images: at most 64 KB each.

Tags: **[LIVE nnn]** = seen in this probe with that HTTP status; **[GUESS]** = not tested.

## 0. The short version

* **Plain OkHttp + Jsoup. No WebView, no JS, no AJAX needed.** Custom PHP site ("modern" theme) behind Cloudflare
  with Google PageSpeed; every page came back 200 with no challenge [LIVE 200, `cf-ray …-LHR`]. Not blocked in the UK
  (no 451, no Online Safety Act page).
* Content: **Japanese raw manga** (9 445 titles), mostly all-ages; genres include Adult (249 titles), Smut, Mature,
  Ecchi. So the source is `nsfw = false`.
* The whole chapter list is in the details page (One Piece 199 rows, Kingdom 322 rows, matching the count pill).
* Page images are plain `<img class="reader-img">` with full https URLs in `src` and `data-src` (same value).
* **No Referer, cookie or special UA needed** for covers or pages [LIVE 200/206 with none, with the site, with a
  foreign Referer].
* Chapter times are **site-local UTC+7** (`2026-11-02 11:00:01` was "12 minutes ago" at 04:12 GMT).
* URLs carry odd prefixes: `/hwms-<slug>.html` (title), `/bsaq-<slug>-chapter-<n>.html` (chapter). Other prefixes
  404 for titles; chapters also answer under the old `/read-<slug>-chapter-<n>.html` [LIVE 200, same 15 pages].
  The prefixes may rotate some day, so the app learns the title prefix from list links and keeps chapter paths whole.

Headers used: Android Chrome UA (the app's default), `Accept-Language: en-GB`. The site sets a `PHPSESSID` cookie
(not needed).

---

## 1. Lists

All lists are `GET /manga-list.html?<params>` [LIVE 200], 24 per page:

| feed | URL |
|---|---|
| Popular ("Most read", all-time views) | `/manga-list.html?page=<n>&sort=views&sort_type=DESC` [LIVE 200] |
| Latest update | `/manga-list.html?page=<n>&sort=last_update&sort_type=DESC` [LIVE 200] (also the default) |
| A-Z / Z-A | `sort=name&sort_type=ASC` / `DESC` [LIVE link on the page; ASC GUESS-tested via the pill] |

The home page has a "Top Today" slider (`.top-today-item`, ~10 items, no paging); not used.

One item (trimmed real markup):
```html
<article class="manga-update-card">
  <a href="/hwms-isekai-mahou-shoujo-logicalharuna.html" class="update-cover-link"
     data-manga-slug="isekai-mahou-shoujo-logicalharuna">
    <img src="/app/manga/themes/modern/cover.php?u=aHR0cHM6Ly9qNC5qZm…&amp;w=120&amp;s=0b6081…"
         srcset="/app/…cover.php?u=…&amp;w=120&amp;s=… 120w, /app/…cover.php?u=…&amp;w=180&amp;s=… 180w"
         alt="Isekai Mahou Shoujo Logical☆Haruna" class="update-cover-img" loading="lazy"></a>
  <div class="update-content">
    <h2 class="update-title"><a href="/hwms-isekai-mahou-shoujo-logicalharuna.html"
        data-manga-slug="isekai-mahou-shoujo-logicalharuna" title="Isekai Mahou Shoujo Logical☆Haruna">…</a></h2>
    <ul class="update-chapter-list">
      <li class="update-chapter-item">
        <a href="/bsaq-isekai-mahou-shoujo-logicalharuna-chapter-9.3.html" class="update-chapter-link" title="… Chapter 9.3">
          <span class="chapter-diamond">&#9672;</span> Ch. 9.3 </a>
        <time class="update-chapter-time">10 minutes ago </time></li>
      … (3 newest chapters)
```

| field | selector | notes |
|---|---|---|
| slug (id) | `a.update-cover-link[data-manga-slug]` | stable; title page = `/<prefix>-<slug>.html`, prefix `hwms` today |
| title | `h2.update-title a[title]` | |
| cover | widest entry of `img[srcset]` (180w in lists, 400w in Top Today / details) | `cover.php` is **signed** (`s=` HMAC over `u` + `w`), so the width cannot be changed. `u` = URL-safe base64 of the original file on `j4.jfimv2.xyz` (`https://j4.jfimv2.xyz/images/20200803/5f27876233111_5f27876542e6e.jpg`, a 400 KB JPEG [LIVE 206]); cover.php answers a WebP [LIVE 200] |
| latest chapter | first `a.update-chapter-link` text ("Ch. 9.3", strip the ◈) | |
| genres | **not in lists** | only on the details page |

**Paging**: `nav.modern-pagination`; next page exists when `a[title="Next Page"]` is present [LIVE: present on
page 1, absent on page 394 of 394]. Also `.library-sort-page-indicator` "Page 1 of 394" and `.library-count`
"9,445 titles found".

## 2. Search and genres

Same URL and markup; all filters combine [LIVE 200]:

| param | meaning | live check |
|---|---|---|
| `title=<text>` (or `name=` from the header box) | title / alt titles / author | `one piece` → 1, Japanese `ワンピース` → 1 (alt title) |
| `author=<name>` | author | `ODA Eiichiro` → One Piece |
| `group=<name>` | scanlation group / magazine (details "Magazine" links use it) | GUESS |
| `include_genres[]=<slug>` (repeatable) | genres, **AND** | romance + comedy − harem → 1 452 (romance alone 3 207) |
| `exclude_genres[]=<slug>` | exclude | as above |
| `m_status=` | `2` Ongoing, `1` Completed | Completed → 425 |
| `sort=`, `sort_type=` | `views` / `last_update` / `name`, `DESC` / `ASC` | |
| `page=<n>` | page | |

Single genre pages also exist: `/manga-list-genre-<slug>.html` [LIVE 200] (same markup).

**Genre list** (from `button.filter-genre-pill[data-slug][data-name]` on `/manga-list.html`, 34, 2026-11-02):
`action Action, adult Adult, adventure Adventure, comedy Comedy, demons Demons, drama Drama, ecchi Ecchi,
fantasy Fantasy, gender-bender Gender Bender, harem Harem, historical Historical, horror Horror, isekai Isekai,
josei Josei, magic Magic, mature Mature, mecha Mecha, mystery Mystery, psychological Psychological, romance Romance,
school-life School Life, sci-fi Sci-fi, seinen Seinen, shoujo Shoujo, shounen Shounen, shounen-ai Shounen Ai,
slice-of-life Slice of Life, smut Smut, sports Sports, super-power Super Power, supernatural Supernatural,
tragedy Tragedy, yaoi Yaoi, yuri Yuri`.

**Minors-related genres: none** on this site (no loli / shota / child tag). The app's Safety check still runs on
details genres.

## 3. Details page

`GET /hwms-<slug>.html` [LIVE 200]. Trimmed real markup:
```html
<div class="manga-page-container" data-manga-slug="one-piece-raw">
 <div class="manga-detail-poster">
  <img src="/app/manga/themes/modern/cover.php?u=…&amp;w=400&amp;s=…" srcset="… 200w, … 320w, … 400w" alt="One Piece - Raw">
  <div class="h0rating" id="manga-rating-stars" data-slug="one-piece-raw" data-score="2.2">…
 <h1 class="manga-detail-title">One Piece - Raw </h1>
 <div class="manga-detail-alt"><b>Alternative:</b> キングダム, 王者天下 </div>
 <div class="manga-metadata-grid">
  <div class="meta-row"><i …></i><b>Author:</b> <a href="/manga-author-ODA%20Eiichiro.html">ODA Eiichiro</a></div>
  <div class="meta-row"><i …></i><b>Artist:</b> <a href="/manga-artist-ODA%20Eiichiro.html">ODA Eiichiro</a></div>
  <div class="meta-row"><i …></i><b>Magazine:</b> <a href="/manga-list.html?group=Weekly%20Shonen%20Jump">Weekly Shonen Jump</a></div>
  <div class="meta-row"><i …></i><b>Status:</b> <span class="badge-status ongoing">Ongoing</span></div>
  <div class="meta-row"><i …></i><b>Views:</b> 4,729,288 </div> …
  <div class="meta-row" title="2026-11-01 22:30:01"><i …></i><b>Updated:</b> 13 hours ago </div>
 <div class="manga-genres-cloud">
  <a href="/manga-list-genre-action.html" class="genre-tag-pill">Action </a> …
 <button … id="btn-manga-bookmark" data-mid="4218" data-slug="one-piece-raw" data-title="One Piece - Raw"
         data-cover="https://j4.jfimv2.xyz/images/20200803/5f27876233111_5f27876542e6e.jpg">
 <div id="synopsis-content" class="synopsis-text is-clamped">Before the Pirate King was executed, …</div>
```

| field | selector |
|---|---|
| title | `h1.manga-detail-title` |
| alt titles | `.manga-detail-alt` text minus "Alternative:", split on `, ` |
| authors / artists | `.meta-row` whose `b` is `Author:` / `Artist:`, its `a` texts |
| magazine | `.meta-row` `Magazine:` |
| status | `.badge-status` (Ongoing / Completed) |
| genres | `.manga-genres-cloud a.genre-tag-pill` |
| description | `#synopsis-content` |
| full cover | `#btn-manga-bookmark[data-cover]` (original file), else widest `srcset` of `.manga-detail-poster img` |
| rating | `#manga-rating-stars[data-score]` (out of 5) |
| type | none (all raw manga) |
| related | `a.related-card` (href `/hwms-<slug>.html`, `title`, `img`, `.related-card-chapter`) |

## 4. Chapter list

In the details page, newest first, complete (no AJAX, no paging) [LIVE: 199 / 322 rows = count pill]:
```html
<div id="chapter-rows-container" class="chapters-grid-container">
 <a href="/bsaq-one-piece-raw-chapter-1197.html" class="chapter-card-item chapter-row" data-chapter="1197">
  <div class="chapter-title-col"><i …></i><span class="chapter-name-text">Chapter 1197 </span></div>
  <div class="chapter-meta-col"><span title="2026-11-01 22:30:01">13 hours ago </span></div></a>
```
* id = href without `/` and `.html` (`bsaq-one-piece-raw-chapter-1197`), number = `data-chapter` (decimals like
  `43.3`, `419.5`), name = `.chapter-name-text`.
* date = `span[title]` `yyyy-MM-dd HH:mm:ss` in **UTC+7** [LIVE, see section 0].

## 5. Chapter pages

`GET /bsaq-<slug>-chapter-<n>.html` [LIVE 200]:
```html
<img src="https://j2.jfimv2.xyz/images4/20261101/a95288288df3dd44c2bd2e179f490016067.jpg"
     data-src="https://j2.jfimv2.xyz/images4/20261101/a95288288df3dd44c2bd2e179f490016067.jpg"
     class="reader-img" loading="eager" alt="Page 1" data-page-index="1"/>
```
All pages are in the HTML (15 for One Piece 1197), in order. Use `data-src`, else `src`. Hosts seen:
`j2.jfimv2.xyz` (pages), `j4.jfimv2.xyz` (covers). JPEG, 300-550 KB.

## 6. Images and headers

| picture | no Referer | Referer jestful.net | Referer foreign |
|---|---|---|---|
| `jestful.net/app/…/cover.php?…` | [LIVE 200 WebP] | [LIVE 200] | [LIVE 200] |
| `j4.jfimv2.xyz/images/…jpg` (original cover) | [LIVE 206 JPEG] | [LIVE 206] | [LIVE 206] |
| `j2.jfimv2.xyz/images4/…jpg` (page) | [LIVE 206 JPEG] | [LIVE 206] | [LIVE 206] |

No Referer rule needed. `parse.py` fetched 3 list covers (WebP) + 3 pages (JPEG): all real images by magic bytes.

## 7. App notes

* Rate limit only the list endpoint (`jestful.net/manga-list`, 500 ms): covers are on `jestful.net` too, and a
  host-wide limit would queue 24 covers behind each other.
* If a title page answers 404, refetch a list to learn the current title prefix and retry once; chapters fall back
  to the `read-` prefix.
