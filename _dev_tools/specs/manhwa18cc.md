# manhwa18.cc source spec (tested 2026-11-02)

Tested live on 2026-11-02 from England with Python `requests`, anonymous, about 1 request per 1.1 s. Probe scripts in
`_dev_tools\probe_manhwa18\` (`cc_lists.py`, `cc_more.py`, `imgtest.py`, `parse.py cc`, `final_check.py`); saved
replies in `probe_manhwa18\out\cc_*.html`. Kotlin: `sources/Manhwa18CcSource.kt` (object `Manhwa18CcSource`).

Tags: **[LIVE nnn]** = seen with that HTTP status; **[GUESS]** = not tested.

## 0. The short version

* Plain server-rendered HTML (own PHP theme, Madara-like class names), Cloudflare without a challenge, not blocked in
  the UK [LIVE 200, `cf-ray …-LHR`]. Images are in `src` and `data-src` (both the same URL).
* Every chapter is on the title page (355 on "amai-seikatsu") — no AJAX [LIVE].
* Pictures need **no Referer** [LIVE 206 with and without].
* Many titles exist twice: English and "<title> Raw" (Korean). `/raw` lists only raws.
* Only single-genre browsing and plain text search; no exclude, no combined filters. No minors-related genre in the list.

## 1. Lists (24 per page) [LIVE 200]

| feed | URL |
|---|---|
| Latest update (default) | `/webtoons/<n>` |
| Trending (app's Popular) | `/webtoons/<n>?orderby=trending` |
| Rating / A-Z / new titles | `?orderby=rating` / `alphabet` / `latest` (= newest titles, chapter 1-ish) |
| Raw only / Completed | `/raw/<n>`, `/completed/<n>` (orderby ignored) |
| Genre | `/webtoon-genre/<slug>/<n>` (orderby accepted, made no visible difference) |
| Search | `/search?q=<text>&page=<n>` |
| Author / artist | `/author/<slug>`, `/artist/<slug>` (one page, no paging) [LIVE 200] |

Item (trimmed, real):
```html
<div class="manga-item"><div class="bsx wleft">
  <div class="thumb"><a href="/webtoon/the-worlds-best-martial-arts-instructor" title="The World’s Best Martial Arts Instructor">
      <span class="badge-adult">18+</span>
      <img data-src="https://manhwa18.cc/manga/the-worlds-best-martial-arts-instructorm.jpg" src="…same…" alt="…"></a></div>
  <div class="data wleft"><h3><a href="/webtoon/…" title="…">…</a></h3>
    <div class="item-rate wleft"><div class="my-rating jq-stars" data-rating="4.15"></div><span>4.15</span></div>
    <div class="list-chapter wleft">
      <div class="chapter-item wleft"><span class="chapter"><a href="/webtoon/…/chapter-107" class="btn-link"> Chapter 107 </a></span>
        <span class="post-on"><span class="c-new-tag"><img src="/images/new.gif"></span></span></div>
      <div class="chapter-item wleft">… Chapter 106 … <span class="post-on">16 Oct 2026</span></div>
```
Selectors: `div.manga-lists div.manga-item`; link `div.thumb a[href]` (slug = last path part, title = `title`);
cover `div.thumb img[data-src]` (small "…m.jpg" / "…czv.jpg"); subtitle = first `div.chapter-item a`. No genres in lists.

Paging: `ul.pagination li.next a` present = there is a next page. **Past the last page the site repeats the last
page** (`/webtoons/9999` = page 160), so never page by item count [LIVE].

## 2. Genres (40, from any page's menu `a[href*='/webtoon-genre/']`) [LIVE]

adult (shown "18+ Content"), ecchi, mature, romance, drama, seinen, yaoi, action, fantasy, shounen, supernatural,
school-life, horror, thriller, isekai, comedy, harem, smut, sports, adventure, historical, martial-arts, psychological,
slice-of-life, hentai, shoujo, sci-fi, gender-bender, yuri, mecha, tragedy, mystery, gl, josei, bl, comics, ntr,
doujinshi, magic, family. Slug = id.

## 3. Details: `/webtoon/<slug>` [LIVE 200]

```html
<meta property="og:image" content="https://manhwa18.cc/manga/the-worlds-best-martial-arts-instructor-3883.jpg">  <!-- big cover -->
<div class="post-title"><h1><span>18+</span> The World’s Best Martial Arts Instructor</h1></div>
<div class="post-content_item wleft"><div class="summary-heading"><h5>Alternative:</h5></div>
  <div class="summary-content">천하제일 무공교관 / The Best Martial Arts Instructor In The World / …</div></div>
… <h5>Author(s)</h5> <div class="author-content"><a href="https://manhwa18.cc/author/singa">Singa</a> …
… <h5>Artist(s)</h5> <div class="artist-content"><a href="/artist/seokbong">Seokbong</a>
… <h5>Genre(s)</h5> <div class="genres-content"><a href="https://manhwa18.cc/webtoon-genre/action">Action</a> …
… <h5>Type</h5> Manhwa   … <h5>Release</h5> 2024   … <h5>Status</h5> OnGoing
<span class="avgrate">4.15</span>
<div class="panel-story-description"><div class="dsct"><p>…</p><p>…</p></div></div>
```
Title = h1 without its `span`; status "OnGoing" / "Completed".

## 4. Chapters (inline, newest first) [LIVE]

```html
<ul class="row-content-chapter wleft">
  <li class="a-h wleft"><a class="chapter-name text-nowrap" href="/webtoon/…/chapter-107" title="…">Chapter 107</a>
      <img src="/images/new.gif"></li>                        <!-- newest: a "new" picture, no date -->
  <li class="a-h wleft"><a class="chapter-name text-nowrap" href="/webtoon/…/chapter-106">Chapter 106</a>
      <span class="chapter-time text-nowrap" title="">16 Oct 2026</span></li>
```
Date "d MMM yyyy" (parsed by hand, month by its first 3 letters); no date ("new") → the app uses now. Names like
"Chapter 1 : In Love With Lingerie", "Chapter 100.5". Chapter id = last path part.

## 5. Pages: `/webtoon/<slug>/<chapter>` [LIVE 200]

```html
<div class="read-content wleft tcenter">
  <img class="loading p1" data-src="https://img01.manhwa18.cc/online/5179/106/1-d2522.jpg" src="…same…" loading="lazy" …/>
```
Full size, in order. Hosts `img01` / `img02.manhwa18.cc` (some ".jpg" files are really WebP [LIVE magic bytes]).

## 6. Pictures and Http rules

Covers `manhwa18.cc/manga/…`, pages `imgNN.manhwa18.cc/…`: no Referer needed [LIVE 206]. App registers
`Http.referers["manhwa18.cc"]` anyway and rate limits (400 ms) only page paths `manhwa18.cc/webtoon` (covers
`/webtoon`, `/webtoons`, `/webtoon-genre`) and `manhwa18.cc/search`.
