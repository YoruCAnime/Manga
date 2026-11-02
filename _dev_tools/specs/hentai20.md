# hentai20.io manga source spec (tested 2026-11-02)

Tested live on 2026-11-02 from England with Python `requests`, anonymous, no login, about 1 request per second,
45 requests in all. Probe scripts in `_dev_tools\probe_hentai20\`: `probe.py` (single GET / capped Range GET),
`search.py` (list / search / genre parameters), `images.py` (covers and pages with / without Referer, filter
counts), `parse.py` (the selectors the app uses, run against live pages). Saved replies in `probe_hentai20\out\`
(`requests.log` lists every call). Images: at most 64 KB each.

Tags: **[LIVE nnn]** = seen in this probe with that HTTP status; **[GUESS]** = not tested.

## 0. The short version

* **Plain OkHttp + Jsoup.** WordPress with the Themesia **"MangaReader" theme 2.2.2** (not Madara) behind
  Cloudflare; every page came back 200 with no challenge [LIVE 200, `cf-ray …-LHR`]. Not blocked in the UK (no 451,
  no Online Safety Act page).
* 18+ manhwa / manga in English, ~1 730 titles (58 list pages of 30).
* Lists, filters, details, the **full** chapter list: plain HTML. Page images: the theme's `ts_reader.run({...})`
  JSON in the chapter page (also rendered as `#readerarea img`).
* **No Referer, cookie or special UA needed** for covers or pages [LIVE 206 with none, with the site, with a
  foreign Referer].
* Home is `/home1/`; the app does not need it (the `/manga/` archive has every list).

Headers used: Android Chrome UA (the app's default), `Accept-Language: en-GB`.

---

## 1. Lists

`GET /manga/?page=<n>&order=<order>` [LIVE 200], 30 per page.

| feed | `order=` |
|---|---|
| Popular | `popular` [LIVE: secret-class, milf-hunting-in-another-world, …] |
| Latest update | `update` [LIVE] |
| Recently added | `latest` [form option, GUESS] |
| A-Z / Z-A | `title` / `titlereverse` [form options, GUESS] |

One item (trimmed real markup):
```html
<div class="bs"><div class="bsx">
  <a href="https://hentai20.io/manga/secret-class/" title="Secret Class">
    <div class="limit"><div class="ply"></div><span class="type Manga"></span>
      <span class="hotx"><i class="fab fa-hotjar"></i></span>
      <img src="https://hentai20.io/wp-content/uploads/2024/08/secret-class-01-193x278.jpg"
           class="ts-post-image wp-post-image attachment-full size-full" loading="lazy" title="Secret Class" …/></div>
    <div class="bigor"><div class="tt">Secret Class</div>
      <div class="adds"><div class="epxs">Chapter 322</div>
        <div class="rt"><div class="rating">…<div class="numscore">7.00</div></div></div></div></div>
  </a></div></div>
```

| field | selector | notes |
|---|---|---|
| slug (id) | `.listupd .bs .bsx > a[href]` last path segment | title page = `/manga/<slug>/` |
| title | `a[title]` (or `.tt`) | |
| cover | `img[src]` | usually `-193x278` thumbnails; **stripping the size suffix can 404** (`secret-class-01.jpg` [LIVE 404]), so keep `src` as is. Some covers have no suffix (full file) |
| latest chapter | `.epxs` | "Chapter 322" |
| genres | **not in lists** | |

**Paging**: `.pagination a.next` present = next page [LIVE: absent on page 58 of 58, which has 1 item].
Page links look like `/manga/?order=popular&page=2`.

## 2. Search and genres

* **Text search**: `GET /page/<n>/?s=<text>` (page 1 works as `/page/1/?s=`) [LIVE 200], 20 per page, same item
  markup, next = `.pagination a.next` (absolute `https://hentai20.io/page/2/?s=…`). `/manga/?title=` is ignored by
  the site [LIVE: unfiltered list], so text and genre filters cannot be combined.
* **Genre filter**: `/manga/?genre[]=<id>&genre[]=<id>&order=…&page=<n>` (URL-encode the brackets) [LIVE 200].
  * several genres = **AND** [LIVE: Romance 37 pages, Harem 13, both 9];
  * **exclude with a minus id** `genre[]=-<id>` [LIVE: all 58 pages, minus Harem 45 = 58 − 13; Romance minus Harem
    29 ≈ 37 − 9].
* `status=` / `type=`: the form has them (`ongoing completed hiatus`, `manga manhwa manhua comic novel`) but the
  site sets nothing: `status=completed` → 0, `type=manhwa` → 0, `status=ongoing` = all 58 pages, `type=manga` → 42
  pages [LIVE]. Not offered in the app.
* Single genre archive: `/genres/<slug>/` [LIVE link, not fetched].
* Authors: the site has none (the info table only names the uploader "Posted By"). A tapped name is searched as text.

**Genre list** (`.quickfilter input.genre-item[name="genre[]"]` + `label[for=genre-<id>]` on `/manga/`, 41,
2026-11-02), `id Name`:
`16 Action, 2274 Adult, 2232 Adventure, 3 Comedy, 3594 Comics, 4647 Doujinshi, 4 Drama, 2285 Ecchi, 5 Fantasy,
3423 Gender Bender, 2297 GL, 6 Harem, 3351 Hentai, 3348 Historical, 3349 Horror, 2891 Isekai, 2250 Josei,
4694 Kimono, 4973 Magic, 20 Manga Hentai, 3346 Manhwa, 21 Manhwa Hentai, 2246 Martial Arts, 12 Mature,
2236 Mystery, 2719 Psychological, 3735 Raw, 7 Romance, 146 School life, 3462 Sci-Fi, 3345 Seinen, 3438 Shoujo,
3352 Shounen, 9 Slice of Life, 14 Smut, 3350 Sports, 1673 Supernatural, 3353 Thriller, 3356 Tragedy,
4761 Uncensored, 2724 Yuri`.

**Minors-related genres: none** in the list (no loli / shota / child tag). Genres are sparse: many titles have
none at all (Secret Class), so the Safety check on details catches only what the site tags.

## 3. Details page

`GET /manga/<slug>/` [LIVE 200]. Trimmed real markup:
```html
<article id="post-387256" class="post-387256 hentry" itemscope itemtype="http://schema.org/CreativeWorkSeries">
 <div class="seriestucon">
  <div class="seriestuheader"><h1 class="entry-title" itemprop="name">MILF Hunting In Another World</h1></div>
  <div class="seriestucontl">
   <div class="thumb" itemprop="image"><img src="https://hentai20.io/wp-content/uploads/2024/08/milf-hunting-in-another-world-01.jpg" …/></div>
   <div class="bookmark" data-id="378670">…</div><div class="bmc">Followed by … people</div>
   <div class="rating bixbox">…<div class="num" itemprop="ratingValue" content="7.00">7.00</div></div>
  <div class="seriestucontentr"><div class="seriestuhead">
   <div class="entry-content entry-content-single" itemprop="description"><p>After being caught …</p></div>
   <div class="lastend">… First / Latest chapter links …</div></div>
   <div class="seriestucont"><div class="seriestucontr">
    <table class="infotable"><tbody>
     <tr><td>Status</td><td>Ongoing</td></tr>
     <tr><td>Type</td><td>Manga</td></tr>
     <tr><td>Posted By</td><td><span class="author vcard"><i itemprop="name">…</i></span></td></tr>
     <tr><td>Posted On</td><td><time datetime="2024-08-11T17:54:22+00:00">August 11, 2024</time></td></tr>
     <tr><td>Updated On</td><td><time datetime="2026-10-29T16:01:16+00:00">October 29, 2026</time></td></tr>
     <tr><td>Views</td><td><span class="ts-views-count">?</span></td></tr></tbody></table>
    <div class="seriestugenre"><a href="https://hentai20.io/genres/adventure/" rel="tag">Adventure</a></div>
```

| field | selector |
|---|---|
| title | `.seriestuheader h1.entry-title` |
| alt titles | `.seriestualt` [GUESS: theme field, empty on the titles seen] |
| authors / artists | infotable rows `Author` / `Artist` [GUESS: none on this site] |
| status / type | infotable rows `Status` / `Type` (always Ongoing / Manga today) |
| genres | `.seriestugenre a` (may be missing) |
| description | `.seriestuhead .entry-content` |
| cover | `.seriestucontl .thumb img[src]` |
| rating | `.rating .num[itemprop=ratingValue]` (out of 10, mostly 7.00) |

## 4. Chapter list

In the details page, newest first, **complete** (no AJAX) [LIVE: Secret Class 336 rows, MILF Hunting 154 rows with
decimals like 122.7]:
```html
<div class="eplister" id="chapterlist"><ul class="clstyle">
 <li data-num="321.5"><div class="chbox"><div class="eph-num">
  <a href="https://hentai20.io/secret-class-chapter-321-5/">
   <span class="chapternum">Chapter 321.5</span><span class="chapterdate">October 23, 2026</span></a></div></div></li>
```
* id = last path segment of the href (`secret-class-chapter-321-5`); chapter URL = `/<id>/` (no `/manga/`).
* number = `li[data-num]`; date = `.chapterdate` `MMMM d, yyyy` (English month names), date only → midnight UTC.

## 5. Chapter pages

`GET /<chapter-slug>/` [LIVE 200]. Inline script:
```js
ts_reader.run({"post_id":488287,"noimagehtml":"<center><h4>NO IMAGE YET<\/h4><\/center>",
  "prevUrl":"https:\/\/hentai20.io\/secret-class-chapter-321-5\/","nextUrl":"","mode":"full",
  "sources":[{"source":"Server 1","images":["https:\/\/img.hentai1.io\/secret-class-01\/chapter-322\/001-615a2cd9d3d1.jpg", …]}], …});
```
Regex `ts_reader\.run\((\{.*?\})\);` (dot matches newlines), JSON → `sources[0].images`. Only "Server 1" seen. The
same list is rendered as `#readerarea img[src]` (identical, 24 / 31 pages) [LIVE]. JPEG ~300 KB on `img.hentai1.io`.

## 6. Images and headers

| picture | no Referer | Referer hentai20.io | Referer foreign |
|---|---|---|---|
| `hentai20.io/wp-content/uploads/…-193x278.jpg` (cover) | [LIVE 206 JPEG] | [LIVE 206] | [LIVE 206] |
| `img.hentai1.io/<series>/chapter-N/001-….jpg` (page) | [LIVE 206 JPEG] | [LIVE 206] | [LIVE 206] |

No Referer rule needed. `parse.py` fetched 3 covers + 3 pages: all real JPEGs by magic bytes.

## 7. App notes

* Rate limit the page paths only (`hentai20.io/manga/` and `hentai20.io/page/`, 500 ms): covers are on the same
  host.
* `nsfw = true`. The grid covers are small (193x278); there is no bigger file to point at.
