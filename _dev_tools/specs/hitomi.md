# hitomi.la source spec (tested 2026-11-02)

Tested live on 2026-11-02 with Python `requests`, anonymous, about one request a second, Android Chrome UA.
Probe scripts: `_dev_tools\probe_hitomi\` (`fetch_js.py` saves the site's JS, `probe.py` .. `probe5.py` the
endpoints, `parse.py` the end-to-end proof); replies and run logs in `probe_hitomi\out\`.
Marks: **LIVE** = seen working from here on the date above; **GUESS** = read from the site's JS, not exercised.

## 0. The short version

* The **website** `https://hitomi.la/` answers a plain nginx **404** from this connection (UK), every path (LIVE).
  Never fall back to the website or a WebView; use only the data hosts, the same files the site's own pages load
  (as with nhentai, whose API we use while its website is blocked).
* **Data host** `https://ltn.gold-usergeneratedcontent.net` (LIVE): `gg.js`, `.nozomi` lists, `galleries/<id>.js`,
  `galleriesindex/`. **Suggestions** `https://tagindex.hitomi.la/<field>/<c>/<h>/<a>/<r>.json` (LIVE, answers 200
  although the website does not). **Images** `w1|w2` (webp), `a1|a2` (avif), `tn` / `atn` / `btn` (thumbnails), all
  on `gold-usergeneratedcontent.net` (LIVE).
* Every image host **needs `Referer: https://hitomi.la/`**: without it, 404 (LIVE). The data host works with it too.
* Nothing answered 451 or an age-check page. No cookies, no login, no Cloudflare.
* `gg.js` is regenerated **every hour** (`b` = the generation time in epoch seconds; `Last-Modified` 04:00:01 GMT,
  `max-age=3600`). Page URLs built with an old `b` must not be kept: fetch gg.js at run time, cache a few minutes.

---

## 1. Lists: `.nozomi` files (LIVE)

A `.nozomi` file = gallery ids as **big-endian int32**, newest (or most popular) first. 25 per page on the site:
page N = `Range: bytes=(N-1)*100 - N*100-1` → `206`, `Content-Range: bytes 0-99/<file size>`, total = size / 4.
Missing list → `404` (HTML body).

| list | path (under `https://ltn.gold-usergeneratedcontent.net/`) | tested |
|---|---|---|
| Latest (date added) | `index-all.nozomi` | LIVE: 1 232 191 ids; pages 1 and 2 = 25 ids each, no overlap |
| Latest, one language | `index-english.nozomi`, `index-japanese.nozomi` ... | LIVE |
| Newest published | `date/published-all.nozomi`, `date/published-english.nozomi` | LIVE |
| Popular | `popular/today-all`, `popular/week-all`, `popular/month-all`, `popular/year-all` (+ `-<language>`) | LIVE |
| Tag (female / male / plain) | `tag/female:big%20breasts-all`, `tag/male:yaoi-all`, `tag/full%20color-all` | LIVE |
| Tag in one language | `tag/female:big%20breasts-english` | LIVE (74 769 ids) |
| Tag, sorted | `tag/popular/week/female:big%20breasts-english`, `tag/date/published/female:big%20breasts-all` | LIVE |
| Artist / series / character / group | `artist/shindol-all`, `series/touhou%20project-all`, `character/<name>-all`, `group/<name>-all` | LIVE for artist (727) and series; character / group names must be exact (`reimu hakurei`, not `hakurei reimu`; take them from suggestions) |
| Artist / series sorted | `artist/popular/month/shindol-all`, `series/popular/year/touhou%20project-all` | LIVE |
| Type | `type/doujinshi-all`, `manga`, `artistcg`, `gamecg`, `imageset`, `anime` (video) | LIVE |

Names are lower case, spaces as `%20`, `:` left as is.

**Whole files for set maths**: the site's search (`search.js` `nozomi_address_from_state`) reads whole files from the
gzip copy under **`n/`**: `n/index-english.nozomi`, `n/popular/week-all.nozomi`, `n/tag/female:big%20breasts-all.nozomi`,
`n/tag/popular/week/female:x-english.nozomi`, `n/tag/date/published/female:x-all.nozomi`, `n/artist/shindol-all.nozomi`
(LIVE: `Content-Encoding: gzip`, ~35 % smaller, decodes to the same ids). Use plain paths for Range paging (a Range on
the gzip copy gives gzip bytes) and `n/` for whole files. Sizes: index-all 4.9 MB (3.2 MB on the wire),
female:big breasts-all 1.9 MB, index-english 0.68 MB.

**Include / exclude** (`results.js` `do_search`): the first positive term with a `:` (or, without one, the index in the
chosen sort) gives the order; every other positive term intersects; every negative term subtracts; `language:x` only
switches every file to its `-x` variant; `orderby:popular orderbykey:week` / `orderby:datepublished` pick the sorted
files. LIVE: `female:big breasts` (english) minus `full color` = 74 769 → 54 030.

## 2. Text search: galleries B-tree (LIVE)

1. `GET galleriesindex/version?_=<ms>` → e.g. `1793591606`.
2. key = first 4 bytes of SHA-256(word, UTF-8), word lower case.
3. Node at address A (root 0) = `Range: bytes=A-(A+463)` of `galleriesindex/galleries.<v>.index`:
   int32 nKeys, then per key int32 size (1..32) + bytes; int32 nDatas, then per data uint64 offset + int32 length;
   then 17 uint64 sub-node addresses. All big-endian.
4. Walk: first key with key ≤ node key (unsigned byte compare); equal → data, else if all subs are 0 → not found,
   else go to `subs[i]`.
5. Data = `Range: offset..offset+length-1` of `galleriesindex/galleries.<v>.data`: int32 n + n int32 ids (newest first).

LIVE: `maid` → 49 180 ids, `tsunade` → 1 711 ids (first = the newest gallery "Tsunade game French").
Several words = one lookup each, intersected (the site splits on spaces).

## 3. Gallery: `galleries/<id>.js` (LIVE)

`var galleryinfo = {...}` (JSON after the first `{`), gzip, ~1-40 KB.
```json
{"id":"4298581","title":"Motokano-tachi to ...","japanese_title":null,"type":"manga","language":"english",
 "language_localname":"English","date":"2026-11-01 21:21:00-06","datepublished":null,"blocked":0,
 "tags":[{"tag":"big breasts","female":"1","male":"","url":"/tag/female%3Abig%20breasts-all.html"},
         {"tag":"rough translation","url":"/tag/rough%20translation-all.html"}],
 "artists":[{"artist":"shinonome ryu","url":"..."}],"groups":null,"parodys":null,"characters":null,
 "files":[{"name":"1.png","hash":"1f88...3031","width":1280,"height":1391,"hasavif":1}],
 "related":[4289515,4287073,4287032,4287027,4276585],"galleryurl":"/doujinshi/...-4298583.html","videofilename":null}
```
* `female` / `male` = `"1"` or `""`; no flag = plain tag. Lists are `null` when empty.
* `blocked` 1 = taken down (the site shows 404). `type` `anime` = a video, not pages (skip).
* No `haswebp` any more: every file has a webp (the reader always asks for webp; `hasavif` for the avif copy).
* `galleryblock/<id>.html` = the small HTML card the list pages use (LIVE); not needed.
* Web page: `https://hitomi.la/galleries/<id>.html` (redirects to `galleryurl` on the site; GUESS from gallery.js, the site
  is 404 here).

## 4. Pictures (LIVE)

`gg.js` (21 KB):
```js
gg = { m: function(g) { var o = 0; switch (g) { case 602: case 1976: ... o = 1; break; } return o; },
       s: function(h) { var m = /(..)(.)$/.exec(h); return parseInt(m[2]+m[1], 16).toString(10); },
       b: '1793592001/' };
```
Parse at run time: default `var o = (\d+)`, then in order every `case (\d+):` until `o = (\d+); break;` gives that
value (1 984 cases, all → 1 today), `b: '(\d+)/'`.

* `s(hash)` = int(last hex digit + the two before it, 16) — e.g. hash `...3031` → `"1"+"03"` = 0x103 = 259.
* **Page (webp)**: `https://w{1 + m(s)}.gold-usergeneratedcontent.net/<b><s>/<hash>.webp`
  e.g. `https://w2.gold-usergeneratedcontent.net/1793592001/259/1f88...3031.webp` → 200 `image/webp`, `RIFF....WEBP`.
  The other host (`w1`) → 404, so `m()` matters.
* **Page (avif)**: same with `a{1 + m(s)}` and `.avif` → 200 `ftypavif`. Android decodes avif only from 12; use webp.
* **Thumbnail / cover**: `https://tn.gold-usergeneratedcontent.net/webpbigtn/<last>/<two before>/<hash>.webp`
  (from the gallery's first file). `tn` serves them all (as written in galleryblock HTML), as do the gg-chosen
  `atn` / `btn` (`chr(97 + m(s)) + "tn"`). Using `tn` keeps saved covers valid when gg changes. Smaller:
  `webpsmalltn`, `webpsmallsmalltn`; avif: `avifbigtn`.
* All need `Referer: https://hitomi.la/` (LIVE: without it 404).

parse.py fetched 3 covers + 3 pages + 1 avif page (first 64 KB each): all real webp / avif by magic bytes.

## 5. Tag suggestions (LIVE)

`GET https://tagindex.hitomi.la/<field>/<c1>/<c2>/....json` — one path segment per character of the typed term,
space → `_`, `/` → `slash`, `.` → `dot`; field = `global` or a namespace (`female`, `male`, `tag`, `artist`, `group`,
`series`, `character`, `language`, `type`). `global.json` = the top 10. Reply = up to 10 `[name, count, namespace]`:
```json
[["big breasts",470375,"female"],["big penis",64622,"male"],["big ass",63561,"female"]]
```
No match → 404 (HTML). Namespace `series` = parody.

## 6. Limits

No limit met at ~1 request/s. The app spaces the data host 100 ms (gallery infos 6 at a time) and suggestions 250 ms.
Images are not spaced (Coil).

## 7. Minors

Tag names (with counts today): `female:loli` 198 827, `male:shota` 119 373, `female:oppai loli` 7 843,
`female:toddlercon` 1 951, `male:toddlercon` 296, `tag:kodomo doushi` 4 871 / `male:` 2 921 / `female:` 575,
`tag:kodomo only` 5 614 (a children-only doujin event). Event tags like `tag:shota scratch NN` come with `male:shota`.
The app downloads these lists once every 6 hours (~0.9 MB gzip), subtracts their ids from every list, and also drops
any gallery whose own tags hold one of these names, and `details()` / `pages()` refuse them ("This gallery is hidden").
Suggestions never offer them. In the first latest page, 2 of 25 were dropped this way.

## 8. What the Kotlin source does (`sources/HitomiSource.kt`)

* Latest = `index-all` Range pages; Popular = `popular/week-all` Range pages. Titles + covers from
  `galleries/<id>.js` for the page's ids (6 in parallel, cached).
* Search: sort (added / published / popular today-week-month-year), language, type selects; include / exclude tags;
  typed text = words (B-tree) plus `ns:name` / `-ns:name` terms. One list and nothing excluded → Range paging; anything
  else → whole `n/` files, set maths, result cached 10 minutes and paged 25 at a time.
* Pages = webp URLs from the current gg.js. Related = the gallery's `related` ids.
* GUESS / untested in the app: the Kotlin itself was not compiled or run here (the Python proof was).
