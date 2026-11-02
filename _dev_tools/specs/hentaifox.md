# hentaifox.com source spec (tested 2026-11-02)

Tested live on 2026-11-02 with Python `requests` + BeautifulSoup, anonymous, about one request a second, the app's
Android Chrome UA. Probe scripts: `_dev_tools\probe_hentaifox\` (`fetch.py` helper, `probe_lists.py`, `probe_tags.py`,
`probe_search.py`, `probe_gallery.py`, `make_tags.py`, `parse.py` = the Kotlin source's steps end to end); saved replies
in `out\` (`tags.tsv` = the whole tag list with counts, `tags_kotlin.txt`, `parse_run.txt`). Kotlin:
`sources\HentaiFoxSource.kt`.

## 0. The short version

* Old-style PHP site (same family as IMHentai / nhentai.xxx), plain server-rendered HTML + jQuery. Plain OkHttp works:
  Cloudflare in front but no challenge seen, no cookies needed. Not blocked from the UK. LIVE
* Every title is **one gallery** (no chapters) → `hasChapters = false`, one Chapter "Gallery · N pages".
* List thumbs carry **numeric tag ids** in `data-tags`, so minors galleries can be dropped from any list without a
  details call. Search understands `-slug` exclusions. LIVE
* Page images are built by JS from hidden inputs + a `g_th` JSON (rule in section 4). LIVE
* Images: `i.` / `i2.hentaifox.com` answer **403 to a foreign Referer** (OK with none or `https://hentaifox.com/`);
  `i3.` accepted any. Register `Referer: https://hentaifox.com/` for `hentaifox.com`. LIVE

## 1. Lists

Item markup (home, search, tag / artist / parody pages, related block):
```html
<div class="thumb" data-artists="6475" data-categories="2" data-languages="1 2" data-tags="13 24 75 99 3247">
  <div class="g_type"><h3 class="g_cat"><a class="t_cat" href="/category/manga/">Manga</a></h3></div>
  <div class="inner_thumb">
    <div class="ribbon ribbon-left ribbon-orange">NEW</div>
    <a href="/gallery/176553/"><img class="lazy" data-src="https://i3.hentaifox.com/005/4298557/thumb.jpg" src="data:image/svg+xml,…" alt=""></a>
  </div>
  <div class="caption"><h2 class="g_title"><a href="/gallery/176553/">The Matter of My New School …</a></h2></div>
</div>
```
Selectors: `div.thumb` that has `h2.g_title a` (the home page also has parody / character cards with
`h2.g_title_popular`: skip them). id = digits of `/gallery/(\d+)/`; title = `h2.g_title a` text; cover =
`.inner_thumb img[data-src]` (a ~250 px `thumb.jpg`; the details page has the bigger `cover.jpg`); category =
`.g_cat` text; tag ids = `data-tags` (space-separated). No page count in lists. 20 a page. LIVE

**Next page**: the last `ul.pagination li` is "Next"; it has class `disabled` (and `href="#"`) on the last page. LIVE
```html
<li class="page-item"><a class="page-link" href="//hentaifox.com/search/?q=glasses&page=965">Next</a></li>
<li class="page-item disabled"><a class="page-link" href="#">Next</a></li>   <!-- last page -->
```
Past the end: 200 with 0 thumbs (search) — LIVE.

| feed | URL | notes |
|---|---|---|
| **Latest** | page 1 `/`, page 2 **`/page/2/`**, page N≥3 **`/pag/N/`** | quirk: `/pag/2/` redirects to `/`, `/page/3/` is 404. 8 119 pages. LIVE |
| **Popular** | `/search/?q=english <guard>&sort=popular&page=N` | there is no site-wide popular page (`/popular/` 404). A search needs at least one positive word (`q=-lolicon` alone → 0 hits), and `english` is on 162 k of the ~176 k galleries. 6 180 pages. LIVE |
| **Search** | `/search/?q=<words>&sort=popular&page=N` | sort: none (newest) or `popular` (the only sort link the site shows; `sort=latest` behaves like none). LIVE |
| tag / artist / parody / character / group / language / category pages | `/tag/<slug>/`, `/tag/<slug>/popular/`, `/tag/<slug>/pag/N/`, `/artist/<slug>/` … | cannot exclude tags; not used (search does the same with the guard) |

### Search syntax (`q`), LIVE

* Words are matched against titles **and** tag / artist / parody / character / group names.
* Quoted phrase = one tag: `"big breasts" glasses` → 20 / 20 with big breasts (vs `big-breasts glasses` 18 / 20).
* Exclusion = `-<slug>` with hyphens: `glasses -big-breasts` → 0 / 20 with big breasts (424 pages).
  `-big breasts` (space) excludes only "big" and makes "breasts" required — wrong. So: include tags as `"name"`,
  exclude as `-slug`.
* Artists / parodies the same way: `"okumoto yuuta"` → 3 pages, same as `/artist/okumoto-yuuta/`.

## 2. Tags

* Full list: `/tags/` (alphabetical, 50 a page, `/tags/pag/N/`, 65 pages, **3 245 tags** on 2026-11-02):
  `<a class="tag_btn btn btn-primary" href="/tag/big-breasts/"><h3 class="list_tag">big breasts</h3><span class="badge">62745</span></a>`.
  Saved in `out\tags.tsv`. Top: big breasts 62 745, sole female 57 244, sole male 53 708, nakadashi 43 823, anal 33 927.
  The Kotlin source embeds the 472 tags used ≥ 150 times (minors tags left out), from `make_tags.py`.
* No autocomplete / tag search endpoint exists (main.min.js only has `/includes/*.php` for likes, favs, comments). The
  app filters its embedded table and also offers the typed words as a free phrase.
* Tag id ↔ slug is not published; ids were found as the id common to every thumb on that tag's page (`probe_tags.py`).

### Minors tags (hide always)

| id | slug | count |
|---|---|---|
| 8 | lolicon | 22 351 |
| 11 | shotacon | 18 229 |
| 45 | oppai-loli | 1 339 |
| 111 | low-lolicon | 532 |
| 268 | low-shotacon | 262 |
| 715 | toddlercon | 13 |
| 3321 | kodomo-doushi | 325 |
| 3322 | kodomo-only | 212 |
| 1463 | shota | 10 |
| 1472 | loli | 7 |

How the app applies them (LIVE): every search carries
`-lolicon -shotacon -oppai-loli -low-lolicon -low-shotacon -toddlercon -kodomo-doushi -kodomo-only -loli -shota`
(`schoolgirl uniform`: 1 081 → 819 pages, 0 minors thumbs); every list drops thumbs whose `data-tags` hold one of the
ids (home page 1: 2 of 20 dropped, page 2: 4 of 20); `details()` / `pages()` refuse a gallery whose tag slugs hit the
list. Also seen: randoseru (298), kindergarten uniform (739), age regression (337), infantilism (470): Safety catches
the first three by name; they are left out of the suggestion table.

## 3. Details: `GET /gallery/<id>/`

```html
<div class="gallery_top">
 <div class="gallery_left"><div class="cover"><img src="https://i3.hentaifox.com/005/4298557/cover.jpg" alt="…"></div>
   <a class="g_button" href="/g/176553/1/">Read Online</a></div>
 <div class="gallery_right"><div class="info">
   <h1>Ane to no Nichijō Kaiwa | Everyday Conversations With My Big Sister</h1>
   <ul class="parodies"><span class="i_text">Parodies:</span><li><a class="tag_btn" href="/parody/hololive/">hololive <span class="t_badge">642</span></a></li></ul>
   <ul class="characters">…</ul>
   <ul class="tags"><span class="i_text">Tags:</span><li><a class="tag_btn" href="/tag/big-breasts/">big breasts <span class="t_badge">62749</span></a></li>…</ul>
   <ul class="artists">…/artist/waya/…</ul>
   <ul class="groups">…/group/wayawaya/…</ul>
   <ul class="languages">…/language/english/ … /language/translated/…</ul>
   <ul class="categories">…/category/doujinshi/…</ul>
   <span class="i_text pages">Pages: 137</span>
   <span class="i_text pages">Posted: 3 years ago</span>          <!-- or "just now", "6 years ago (Updated 6 years ago)" -->
   <ul class="g_buttons">… <input type="hidden" id="load_id" value="2799654"> <input id="load_dir" value="004"> …</ul>
 </div></div></div>
<input type="hidden" id="load_pages" value="137">
<script> var g_th = $.parseJSON('{"1":"j,1280,1728","2":"j,1000,782",…}'); </script>
<div class="related_galleries"><h2>Related Galleries</h2><div class="rel_div"><div class="thumb" data-tags="…">…</div>…</div></div>
```
* Title = `h1` (often "Japanese | English"; the app shows it whole and offers the halves as alt titles).
* Tag groups = `div.info ul.<parodies|characters|tags|artists|groups|languages|categories> a.tag_btn`; name = the
  link's own text (without the `.t_badge` count); slug = last part of the href.
* Pages = `span.i_text.pages` "Pages: N" (= `#load_pages` = `g_th` size). Date = "Posted: N <unit>s ago" (relative only).
* No description, no status (always a finished gallery).
* Related = `div.related_galleries div.thumb` (same item markup, 0-5 items).

## 4. Page images (LIVE, from `/js/reader.min.js`)

```js
u=["i.hentaifox.com","i2.hentaifox.com"],
v=(parseInt($("#unique_id").val(),10)||0)>140236?"i3.hentaifox.com":u[Math.floor(Math.random()*u.length)],
w={j:"jpg",p:"png",b:"bmp",g:"gif",w:"webp"}
x=(t,e)=>`//${v}/${d}/${h}/${t}.${e}`        // d = image_dir (#load_dir), h = gallery_id (#load_id)
```
So page n = `https://<host>/<load_dir>/<load_id>/<n>.<ext>` with `g_th[n] = "<e>,<width>,<height>"`;
host = `i3.hentaifox.com` when the gallery id > 140236, else `i.` or `i2.` (both serve old galleries; `i3.` gives 404
for them). Examples (fetched, magic bytes OK): `https://i3.hentaifox.com/005/4298557/1.webp`,
`https://i2.hentaifox.com/004/2799654/1.jpg`. Page thumbs: `<n>t.jpg`. The reader page `/g/<id>/<n>/` has the same
inputs (`#image_dir`, `#gallery_id` = load id, `#unique_id` = gallery id) and `<img id="gimg" data-src=…>`.

## 5. Images (all LIVE)

| what | URL | no Referer | hentaifox Referer | foreign Referer |
|---|---|---|---|---|
| list thumb | `https://i.hentaifox.com/004/2799654/thumb.jpg` | 200 jpeg | 200 | GUESS 403 (as pages) |
| cover | `https://i3.hentaifox.com/005/4298557/cover.jpg` | 200 jpeg | 200 | - |
| page (old) | `https://i2.hentaifox.com/004/2799654/1.jpg` | 200 jpeg | 200 | **403** |
| page (new) | `https://i3.hentaifox.com/005/4298557/1.webp` | 200 webp | 200 | 200 |

## 6. Behaviour

* No rate-limit headers; ~120 requests at ~1/s never hit a limit. App: 600 ms between page requests.
* Login / favourites / comments need an account (not used). A Cloudflare Turnstile script is loaded for forms only.
