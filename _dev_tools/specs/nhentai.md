# nhentai.net source spec (tested 2026-11-01)

Tested live on 2026-11-01 with Python `requests`, anonymous, one request every 4.5 s. API version
`2.0.0+f4fdd1d`. Probe script: `_dev_tools\probe_nhentai\probe.py` (steps `base hide search lists detail images html
extras`); output in `probe_nhentai\out\` (`openapi.json.txt` = the full OpenAPI 3.1 document).
keiyoushi/extensions-source has **no** nhentai source any more (only an unrelated `src/all/nhentaicom`), so there was
nothing to cross-check there.

## 0. The short version

* Everything works with **plain OkHttp, no cookies, no WebView**: a public JSON REST API at
  `https://nhentai.net/api/v2` (FastAPI; docs `https://nhentai.net/api/v2/docs`, schema `/api/v2/openapi.json`).
  The old `/api/galleries/*` answers 403 "Use new API".
* The **website itself is blocked for this connection**: `GET https://nhentai.net/` → `451 "Regulatory Blocked -
  nhentai"` (UK; the user is in England). The API and the image hosts are **not** blocked. So: never fall back to HTML
  scraping or a WebView; use only the API.
* Rate limits are per IP and low (search 10/min anonymous). Build the app around them (section 6).
* Images need no Referer and no cookies.

Headers: the docs ask for a descriptive UA, `User-Agent: MangaCracker/0.1 (personal Android reader)`;
`Accept: application/json`. Tested: that UA and `okhttp/4.12.0` both work. Optional `Authorization: Key <API key>`
(made in nhentai account settings) doubles most limits; not used here (no login).

---

## 1. Lists

All list endpoints return `GalleryListItem`s:
```json
{"result":[{"id":691957,"media_id":"4279383",
  "english_title":"[Awan] Couldn't resist step-sister who doesn't understand personal space [ai translation]",
  "japanese_title":"沒能忍住不懂距離感的繼妹攻勢",
  "thumbnail":"galleries/4279383/thumb.webp","thumbnail_width":250,"thumbnail_height":378,
  "num_pages":46,"num_favorites":48264,"tag_ids":[1033,1067,2937,6817,8693,9083],
  "blacklisted":false,"suggestion":null}],
 "num_pages":20,"per_page":25,"total":500}
```
(`total` can be null; `japanese_title` can be null or ""). `tag_ids` lets the app hide items without a detail call.

| feed | request | notes (tested) |
|---|---|---|
| **Latest** | `GET /api/v2/galleries?page=1&per_page=25` | newest first; `per_page` 1-100; total 656 413, 26 257 pages. Limit 15/min |
| **Popular now** | `GET /api/v2/galleries/popular` | plain array of today's 5 popular (the site's top row). Limit 8/min |
| **Popular (paged)** | `GET /api/v2/search?query=<q>&sort=popular-week&page=N` | sorts: `date` (default), `popular`, `popular-today`, `popular-week`, `popular-month` (anything else → 400). Popular sorts stop at 20 pages / 500 results |
| Popular for one tag | `GET /api/v2/galleries/tagged?tag_id=12227&sort=popular-week&page=2&per_page=25` | any tag id, including language (12227 = english), artist, parody...; sorts as search plus `pending*`. `total` null, 20 pages for popular sorts. Limit 15/min |
| **Recommended / similar** | `GET /api/v2/galleries/<id>/related` → `{"result":[5 items]}` or `include=related` on the detail call | 5 similar galleries. Limit 12/min |

A "query" for search is required (min length 1). A query made only of exclusions works, e.g.
`-tag:"lolicon" -tag:"shotacon" …` with `sort=popular-week` = popular overall minus hidden tags.

## 2. Search: `GET /api/v2/search?query=<q>&sort=<sort>&page=<n>`

Always 25 per page (no `per_page`). Paging: `page` 1..`num_pages`. Limit **10/min anonymous** (20 with a key).
Query syntax (from the docs, each part tested unless marked):

| syntax | example | tested |
|---|---|---|
| words | `english` | yes |
| exact phrase | `"exact phrase"` | (docs) |
| tag include (by **name**, not id) | `tag:"big breasts" tag:glasses` (quotes needed for spaces; several = AND) | yes: 26 389 hits |
| tag exclude | `-tag:"netorare"` (several allowed) | yes |
| artist / group / parody / character / language / category | `artist:"shindol"`, `group:"crimson comics"`, `parody:"touhou project" character:"reimu hakurei"`, `language:english` | yes (25 / 565 / 1607 / 110 312 hits) |
| exclude any of those | `-artist:name` | (docs) |
| numbers | `pages:>100 favorites:>=1000` | yes |
| upload age | `uploaded:<30d`, `uploaded:>1m` | yes |

So multiple include + exclude filters = one query string: join the parts with spaces, URL-encode once
(`+` for spaces is fine). Tags are referenced by **name** in search; by **id** only in `/galleries/tagged` (one tag).

### Tag lists (names ↔ ids)

* `GET /api/v2/tags/<type>?sort=popular|name&page=N` → `{"result":[TagResponse…],"num_pages":40,"per_page":120,
  "total":4764,"alphabet":…}`. `per_page` is ignored (always 120). Types and totals (2026-11-01): `tag` 4 764,
  `artist` 33 333, `group` 25 378, `parody` 4 397, `character` 17 982, `language` 15, `category` 3.
* `GET /api/v2/tags/<type>/<slug>` (slug = name with spaces as `-`) → one TagResponse, 404 `{"error":"Tag not found"}`.
* `GET /api/v2/tags/ids?ids=19440,32341,2937` → array (max 100 ids). Limit 15/min.
* TagResponse: `{"id":2937,"type":"tag","name":"big breasts","slug":"big-breasts","url":"/tag/big-breasts/",
  "count":239803,"description":"…","is_community":null,"links":null}`.
* There is no name-search endpoint for tags. For a tag picker, download `tags/tag?sort=name` once (40 pages, about
  3 minutes at the limit) and keep it in the app; for artists / groups / parodies / characters let the user type the
  name and put it in the query (`artist:"name"`), or check one with `tags/<type>/<slug>`.
* Fixed ids worth keeping: languages english 12227, japanese 6346, chinese 29963, translated 17249;
  categories doujinshi 33172, manga 33173.

## 3. Search by artist / group / parody / character

`query=artist:"<name>"` (or `group:` / `parody:` / `character:`), any `sort`. Or by id with
`/galleries/tagged?tag_id=<id>&sort=date|popular…&per_page=25` (higher limit than search, 15 vs 10/min, and
`per_page` up to 100). The tag ids come from the gallery detail `tags[]` (type `artist`, `group`, `parody`,
`character`), so "more by this artist" from a details screen should use `tagged`.

## 4. Details: `GET /api/v2/galleries/<id>` (optional `?include=related`; also `comments,favorite,suggestions`)

Limit 20/min. 404 `{"error":"Gallery not found"}`. Trimmed real reply:
```json
{"id":691957,"media_id":"4279383",
 "title":{"english":"[Awan] Couldn't resist step-sister who doesn't understand personal space [ai translation]",
          "japanese":"沒能忍住不懂距離感的繼妹攻勢",
          "pretty":"Couldn't resist step-sister who doesn't understand personal space"},
 "cover":{"path":"galleries/4279383/cover.webp.webp","width":350,"height":529},
 "thumbnail":{"path":"galleries/4279383/thumb.webp","width":250,"height":378},
 "scanlator":"","upload_date":1793216709,
 "tags":[{"id":33172,"type":"category","name":"doujinshi","slug":"doujinshi","url":"/category/doujinshi/","count":517255},
         {"id":17249,"type":"language","name":"translated","slug":"translated","url":"/language/translated/","count":288692},
         {"id":2937,"type":"tag","name":"big breasts","slug":"big-breasts","url":"/tag/big-breasts/","count":239766}],
 "num_pages":46,"num_favorites":48262,
 "pages":[{"number":1,"path":"galleries/4279383/1.webp","width":1280,"height":1935,
           "thumbnail":"galleries/4279383/1t.webp","thumbnail_width":200,"thumbnail_height":302},
          {"number":2,"path":"galleries/4279383/2.webp","width":1280,"height":1866,
           "thumbnail":"galleries/4279383/2t.webp.webp","thumbnail_width":200,"thumbnail_height":292}],
 "related":[{"id":680033,"media_id":"4173451","english_title":"[Awan] 沒能忍住不懂距離感的繼妹攻勢", "...":"..."}]}
```
* **Titles**: `title.pretty` (short, for cards), `title.english` (full, with [circle (artist)] and [language]),
  `title.japanese` (nullable).
* **Description**: none (nhentai has no synopsis). Build one from tag groups if wanted.
* **Tags**: `tags[]` grouped by `type`: `tag` (genres/tags), `artist`, `group`, `parody`, `character`, `language`,
  `category`. **Authors/artists** = type `artist` (+ `group` as the circle).
* **Status**: always completed (a gallery is one finished upload). **Page count**: `num_pages` (= `pages.length`).
* `upload_date` = Unix seconds. `scanlator` usually "".
* **One gallery = one chapter**: chapter name e.g. "Gallery" or `title.pretty`, number 1, date = `upload_date`,
  scanlator = `scanlator` or the `group` tag.

## 5. Images

CDN hosts from `GET /api/v2/cdn` (or `/api/v2/config`, which adds `announcement`):
```json
{"image_servers":["https://i1.nhentai.net","https://i2.nhentai.net","https://i3.nhentai.net","https://i4.nhentai.net"],
 "thumb_servers":["https://t1.nhentai.net","https://t2.nhentai.net","https://t3.nhentai.net","https://t4.nhentai.net"]}
```
Cache it for a day; pick a server at random per gallery and try the next one on failure.

| image | URL | notes |
|---|---|---|
| grid cover (light) | `<thumb_server>/<item.thumbnail>` | e.g. `https://t1.nhentai.net/galleries/4279383/thumb.webp`: **26 KB, really 500x756** (the API says 250x378 = the CSS size, the file is 2x). Best grid choice |
| big cover | `<thumb_server>/<detail.cover.path>` | `cover.webp.webp`, declared 350x529 (probably 2x again; not fetched) |
| full-size cover | `<image_server>/<pages[0].path>` | page 1, 1280 px wide |
| page | `<image_server>/<pages[i].path>` | `https://i1.nhentai.net/galleries/4279383/1.webp`: 225 KB, 1280x1935, matches `width`/`height` |
| page thumbnail | `<thumb_server>/<pages[i].thumbnail>` | for a page strip / grid in the reader |

* **Always use the `path` exactly as given.** Extensions vary per file and per gallery: `thumb.webp`, `thumb.jpg`,
  `thumb.jpg.webp`, `cover.webp.webp`, `2t.webp.webp` were all seen. Do not rebuild paths from `media_id` + a fixed
  extension (old clients did `galleries/<media_id>/<n>.jpg` with t:"j"/"p"/"w"; that is gone).
* **No Referer needed** (tested: thumb and page fetched with no Referer, no cookies → 200 `image/webp`, nginx,
  `Cache-Control: max-age=315360000`). Images have no listed rate limit.

## 6. Rate limits and behaviour

From the OpenAPI document (per IP, anonymous / with API key). No rate-limit headers are sent; expect 429 when over.

| endpoint | anon | key |
|---|---|---|
| `/search` | 10/min | 20/min |
| `/galleries` (latest), `/galleries/tagged` | 15/min | 30/min |
| `/galleries/popular` | 8/min | 8/min |
| `/galleries/<id>` | 20/min | 45/min |
| `/galleries/<id>/related` | 12/min | 30/min |
| `/tags/<type>`, `/tags/<type>/<slug>`, `/tags/ids` | 15/min | 30/min (ids 15) |
| `/cdn`, `/config` | none listed | |

Practical: one global API queue in the app, at least 6 s between search calls and 4 s between others, cache list pages
for a few minutes, cache gallery details for a long time (they don't change), and prefer `tag_ids` from lists over
detail calls. In this probe 45 API calls at 4.5 s spacing never hit a limit. Cloudflare sits in front
(`server: cloudflare`, `cf-cache-status: DYNAMIC`) but showed no challenge to the API. No WebView is needed for
anything; the website (HTML) is 451-blocked from the UK, so a WebView would not help anyway.

Errors: 400 `{"error":"Validation error","details":["query -> sort: Input should be …"]}`, 404
`{"error":"Gallery not found"}` / `{"error":"Tag not found"}`, 403 on the old v1 routes.

## 7. Hide-by-default tags (minors)

Ids from `GET /api/v2/tags/tag/<slug>` (2026-11-01). Every list in this probe was filtered against them before
anything was printed or saved (latest page 1: 3 of 25 dropped; popular: 1 of 5; related: 1 of 5).

| id | name | count |
|---|---|---|
| 19440 | lolicon | 114 777 |
| 32341 | shotacon | 68 622 |
| 159421 | toddlercon | 45 |
| 25663 | oppai loli | 4 567 |
| 14362 | low lolicon | 1 819 |
| 27598 | low shotacon | 965 |
| 152252 | kodomo doushi | 2 058 |

Not tags on nhentai (404): `loli`, `shota`, `low-toddlercon`, `school-age`, `child`.

How to apply them in the app:
1. Lists: drop items whose `tag_ids` contain any hidden id (works for every list endpoint, no extra calls).
2. Search: also add `-tag:"lolicon" -tag:"shotacon" -tag:"toddlercon" -tag:"oppai loli" -tag:"low lolicon"
   -tag:"low shotacon" -tag:"kodomo doushi"` to every query, so pages stay full (tested: these negations work and
   the results had 0 hidden items).
3. Details: refuse to open a gallery whose `tags[].id` hits the list (a deep link could point at one).
Re-check the list now and then with `GET /api/v2/tags/tag?sort=name` (look for new "…con" / loli / shota tags).
