# manga18.club source spec (tested 2026-11-02)

Tested live on 2026-11-02 from England with Python `requests`, anonymous, about 1 request per 1.1 s. Probe scripts in
`_dev_tools\probe_manhwa18\` (`club_lists.py`, `club_more.py`, `imgtest.py`, `parse.py club`, `final_check.py`);
saved replies in `probe_manhwa18\out\club_*.html`. Kotlin: `sources/Manga18ClubSource.kt` (object `Manga18ClubSource`).

Tags: **[LIVE nnn]** = seen with that HTTP status; **[GUESS]** = not tested.

## 0. The short version

* Server-rendered HTML (CodeIgniter: `ci_session` cookie), Cloudflare without a challenge, not blocked in the UK
  [LIVE 200, `cf-ray …-LHR`].
* **Chapter pages are written by JavaScript** from `var slides_p_path = ["<base64 URL>", …];` — base64-decode each
  string to get the full picture URL [LIVE].
* Pictures need **no Referer** [LIVE 200/206 with and without].
* Single-genre browsing, text search, author / artist filters; no exclude. No minors-related genre in the list.
* Many titles exist twice (English and "… Raw"). "[MOTION] …" titles are also plain picture chapters [LIVE].

## 1. Lists (20 per page) [LIVE 200]

| feed | URL |
|---|---|
| Latest update | `/list-manga/<n>?order_by=lastest` (sic; = default) |
| Most views (app's Popular) | `/list-manga/<n>?order_by=views` |
| A-Z | `/list-manga/<n>?order_by=name` |
| Genre | `/manga-list/<slug>/<n>?order_by=…` (sort works) |
| Search | `/list-manga?search=<text>&page=<n>&order_by=…` |
| Author / artist | `/list-manga?author=<name>` / `?artist=<name>` (name as shown, not a slug) [LIVE 200] |
| Latest releases (other layout, 32/page) | `/latest-release/<n>` (not used) |

**The site's own page links drop `order_by`**, so the app builds every page URL itself.

Item (trimmed, real):
```html
<div class="recoment_box"> …
  <div class="story_item">
    <div class="story_images"><a href="https://manga18.club/manhwa/moby-dick" title="Moby Dick">
        <img src="https://cdn.manga18.club/manga/moby-dick/cover/cover_thumb_2.webp" class="img-responsive"></a></div>
    <div class="mg_info"><div class="mg_name"><a href="…">Moby Dick</a></div>
      <div class="mg_chapter"><div class="item">
          <div class="chapter_count"><a href="/manhwa/moby-dick/chapter-109">Chapter 109</a></div>
          <div class="chapter_time"><span>2 days ago</span></div></div> …
```
(The home page's carousels use `data-src` with a 1-px gif in `src`; the app reads `data-src` first.)
`cover_thumb_2.webp` is sometimes a JPEG or PNG with a .webp name [LIVE magic bytes]; decoders sniff, fine.

Paging: an `li a` with text `›` inside `div.section_pagination` = next page. Past the end: 0 items [LIVE].

## 2. Genres (41, menu `a[href*='/manga-list/']` on every page) [LIVE]

Slugs keep the site's capitals: action, mecha, 18 ("18+"), mystery, adult, one-shot, anime, psychological, comedy,
romance, comic, school-life, doujinshi, sci-fi, drama, seinen, Ecchi, Shoujo, Fantasy, shojou-ai, Gender-Bender,
Shounen, Harem, shounen-ai, historical, slice-of-life, Horror, Smut, Josei, Sports, live-action, supernatural, Manhua,
Tragedy, Manhwa, Adventure, Martial-art, Yaoi, Mature, raw, Uncensored.

## 3. Details: `/manhwa/<slug>` [LIVE 200]

```html
<div class="detail_avatar"><img src="https://cdn.manga18.club/manga/moby-dick/cover/cover_250x350.jpg"></div>
<div class="detail_name"><h1>Moby Dick</h1></div>
<div class="detail_listInfo">
  <div class="item"><div class="info_label">… Other name</div><div class="info_value"><span>모비딕</span></div></div>
  <div class="item"><div class="info_label">… Author</div><div class="info_value"><a href="/list-manga?author=Yansae">Yansae</a></div></div>
  <div class="item"><div class="info_label">… Artist</div><div class="info_value"><a href="/list-manga?artist=Yansae">Yansae</a></div></div>
  <div class="item"><div class="info_label">… Status</div><div class="info_value"><span class="label label-success">On Going</span></div></div>
  <div class="item"><div class="info_label">… Categories</div><div class="info_value">
      <a href="https://manga18.club/manga-list/romance">Romance</a> - <a href="…/drama">Drama</a> …</div></div>
  <div class="item"><div class="info_label">… Views</div><div class="info_value"><span>9,618,949</span></div></div>
<p>Rating: <span id="mgRatingText">4.48/5</span> out of <span id="mgVoteCount">601</span> total votes</p>
<div class="detail_reviewContent"> …description… </div>
```
`cover_250x350.jpg` is the biggest cover found (`cover.jpg` 404, `cover_thumb.jpg` is smaller) [LIVE].

## 4. Chapters (inline, newest first; 337 on "secret-class") [LIVE]

```html
<div class="chapter_box"><ul>
  <li class=""><div class="item">
      <a style="color: white;" href="/manhwa/moby-dick/chapter-109" class="chapter_num">Chapter 109 </a>
      <p class="chapter_info">30-10-2026</p>      <!-- dd-MM-yyyy, also on chapters added minutes ago -->
      <p class="chapter_info">24,350</p>          <!-- views -->
```
Slugs like `chapter-321-5` (321.5) and `chap-1`.

## 5. Pages: `/manhwa/<slug>/<chapter slug>` [LIVE 200]

```js
var slides_p_path = ["aHR0cHM6Ly9jZG4ubWFuZ2ExOC5jbHViL21hbmdhL21vYnktZGljay9jaGFwdGVycy9jaGFwdGVyLTEwOC8wMS5qcGc=", …];
// -> https://cdn.manga18.club/manga/moby-dick/chapters/chapter-108/01.jpg
```
An obfuscated script then writes `<img src='…' class="img-responsive image-chapter">` into `#chapter_boxImages`; the
app skips that and decodes the array itself (regex on the raw HTML, `java.util.Base64`).

## 6. Pictures and Http rules

Covers and pages on `cdn.manga18.club`: no Referer needed [LIVE]. App registers `Http.referers["manga18.club"]`
anyway; rate limits (400 ms) only page paths `manga18.club/list-manga`, `manga18.club/manga-list`,
`manga18.club/manhwa`, so the picture host is not slowed.
