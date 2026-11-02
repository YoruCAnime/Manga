# hentai2read.com source spec (tested 2026-11-02)

Tested live on 2026-11-02 with Python `requests`, anonymous, about one request a second, the app's Android Chrome UA.
Probe scripts: `_dev_tools\probe_hentai2read\` (`fetch.py` helper, `inertia.py` page-JSON dumper, `probe_search.py`,
`probe_detail.py`, `probe_urls.py`, `probe_more.py`, `parse.py` = the Kotlin source's steps end to end); saved replies in
`out\` (`*.page.json`, `search_tags.json`, `parse_run.txt`). Kotlin: `sources\Hentai2ReadSource.kt`.

## 0. The short version

* The site was **rebuilt in Oct 2026** (announcement "Welcome to the new Hentai2Read.com!", 2026-10-10): Laravel +
  **Inertia.js** + React. Old scrapers (`/hentai-list/...`, `#js-mangaReader`, `gData`) no longer apply. LIVE
* Every page carries its full data as JSON: `<script data-page="app" type="application/json">{…}</script>`
  (`component`, `props`, `url`, `version`). No HTML scraping needed. LIVE
* **Inertia JSON mode** returns only that JSON (~13-36 KB instead of 65-265 KB of HTML): send
  `X-Inertia: true`, `X-Inertia-Version: <version>`, `X-Requested-With: XMLHttpRequest`. A stale or wrong version
  answers **409** (empty body, `X-Inertia-Location` header) → load the page as HTML once, read `version` from the
  embedded JSON, retry. Responses carry `Vary: X-Inertia`, so HTTP caches keep both kinds apart. LIVE
* Plain OkHttp works: Cloudflare in front (`server: cloudflare`) but no challenge seen. No cookies needed. LIVE
* Images on `hentaicdn.com`: OK with **no Referer** or `Referer: https://hentai2read.com/`; a foreign Referer
  (`https://example.com/`) gets **403**. Register the hentai2read Referer for `hentaicdn.com`. LIVE
* Not blocked from the UK (no 451 / Online Safety Act page). LIVE

## 1. Lists (all = `/search`, which is the only list that can exclude tags)

`GET /search?<params>` → component `Search/Index`, `props.books`:
```json
{"data":[{"id":73690,"title":"The Love Hotel with Cats","url":"/original/73690-the-love-hotel-with-cats",
  "cover":"https://hentaicdn.com/hentai/cover/73690-nhr2onw9.jpg","type":"original","typeLabel":"Original",
  "chapters":1,"pageCount":28,"read":"/reader/73690-the-love-hotel-with-cats/117136",
  "artist":{"id":3185,"name":"ENDOU Okito","type":"artist","url":"/artist/3185-endou-okito"},
  "rating":5,"views":411,"recs":3,"isNew":false,"isHot":false,"isPick":false,"isUpdated":true,"isPromoted":false}],
 "page":1,"lastPage":1300,"total":51995,
 "links":{"prev":null,"next":"https://hentai2read.com/search?without=6%2C23&page=2"},
 "url":"https://hentai2read.com/search?page=1","more":9}
```
40 items a page. **List items carry no tags** (only the home page's "pick" has `tags`), so the minors guard must be in
the query (`without=6,23`) and in `details()`.

| param | values | tested |
|---|---|---|
| `q` | words, matched against **titles** only (`q=teacher` 7 pages; `q=big breasts glasses` 0). `props.matchingTags` lists tags whose name matches (e.g. `q=gotoh juan` → artist 2194) | LIVE |
| `with` | tag ids, comma-separated (any tag type: category, content, artist, parody, character, language …) | LIVE (`with=8` 36 783, `with=2194` artist, `with=1126` parody) |
| `without` | tag ids, comma-separated | LIVE (`without=6,23` 68 860 → 51 995) |
| `match=any` | include tags OR instead of AND (only sent when `with` is set) | LIVE (38 378 vs 36 783) |
| `type` | `original`, `doujinshi`, `imageset`, `video` (video: 0 results anonymously) | LIVE |
| `status` | `ongoing`, `completed` | LIVE |
| `sort` | `` (Newest, default), `oldest`, `latest` (Recently updated), `most-viewed` (Most read), `top-rated`, `alphabetical`, `most-chapters`, `most-pages`, `fewest-pages`. Browse-only sorts (`trending`, `yesterday`, `last-week`, `last-month`) are **ignored** by search | LIVE |
| `page` | 1..`lastPage`; past the end → **404** (Inertia component `Error`) | LIVE |

**Next page**: `books.links.next` non-null (or `page < lastPage`). LIVE

App feeds (all LIVE):
* Latest = `/search?without=6,23&sort=latest` (recently updated; new chapters bump a title).
* Popular = `/search?without=6,23&sort=most-viewed` (all-time most read). The real "trending" lists
  (`/browse/trending`, 500 titles; `/ranking` = yesterday, 17 pages) cannot exclude tags and carry no tags, so they are
  not used.
* Search = `/search?q=…&with=…&without=6,23,…&match=any&type=…&status=…&sort=…&page=N`.

Other list pages seen (not used): `/browse[/original|doujinshi|imageset|videos|trending|staff-picks|ongoing|completed|<sort>|<letter>]`
(`props.books`, same item shape, `links.next` is a bare `/?page=2`), `/artist/<id>-<slug>`, `/parody/…`,
`/category/<id>-<slug>`, `/content/<id>-<slug>` (component `Tag/Show`, `props.books`, `props.tag`).

## 2. Tags

* **Full genre list with counts**: `GET /search/tags` (`Accept: application/json`) → array of 428
  `{"id":8,"name":"Big Breasts","count":43192}` (types category + content only). LIVE. Top: Oneshot 56 310,
  Creampie 46 018, Big Breasts 43 192, Partial Censorship 42 955, Blow job 33 700 …
* **Search across all tag types with counts**: `GET /tags?q=<text>` (Inertia) → `props.tags.data[]`
  `{"id":8,"name":"Big Breasts","type":"category","url":"/category/8-big-breasts","typeLabel":"Category","count":43192,…}`.
  Short list (q=big → 6 hits: category, content, character, artist, parody). LIVE
* Suggest box: `GET /search/suggest?q=<3+ chars>` (JSON) → `{"tags":[{"name","type":"Category|Content|Artist|Parody|…",
  "url":".../parody/1126-big-hero-6"}],"books":[{"title","url"}],"complete":[]}`. No counts; id is in the url. LIVE
* Per-type lists: `/tags/<category|content|artist|parody|character|circle|language|publisher>` (`props.tags`, 48 a
  page, sorted by count, `?q=` filters). Totals 2026-11-02: category 25, content 404, artist 7 616, parody 1 330,
  character 2 658, publisher 54. LIVE
* Tag ids are global across types (`with=` takes any of them).

### Minors tags (hide always)

| id | name | type | count |
|---|---|---|---|
| 6 | Lolicon | category | 9 916 |
| 23 | Shota | category | 7 931 |

`without=6,23` on every list (LIVE: 28 of artist 2194's 34 titles dropped). Others in the list that Safety also
catches by name: `Child Abuse` (734, 3), `Older Female Young Boy` (584, 22). No toddlercon / loli / kodomo tags exist.

## 3. Details: `GET /<type>/<id>[-slug]` → component `Book/Show`

Any type word and any slug work: `/original/33728` → **301** to `/original/33728-penetration-bare-fucking-family`
(also from `/doujinshi/33728-…`). So the app keeps the numeric id only. OkHttp follows the 301 and keeps the Inertia
headers (Python `requests` did too: LIVE). `/books/<id>` = 404.

Trimmed `props`:
```json
{"book":{"id":33728,"title":"Penetration! Bare Fucking Family!","url":"/original/33728-penetration-bare-fucking-family",
  "cover":"https://hentaicdn.com/hentai/cover/_S33728.jpg","alt":"Gattai!Namahame Kazoku",
  "type":{"label":"Original","url":"/browse/original"},"status":{"label":"Completed","url":"/browse/completed"},
  "year":null,"description":null,
  "tags":[{"id":18,"name":"Adult","type":"category","url":"/category/18-adult"},
          {"id":238,"name":"Blow job","type":"content","url":"/content/238-blow-job"}],
  "groups":[{"label":"Artist","type":"artist","tags":[{"id":2194,"name":"GOTOH Juan","type":"artist","url":"/artist/2194-gotoh-juan"}]},
            {"label":"Parody","type":"parody","tags":[…]},{"label":"Character","type":"character","tags":[…]},
            {"label":"Language","type":"language","tags":[{"id":1,"name":"English",…}]}]},
 "stats":{"views":336735,"rating":5,"ratings":427,"rank":1,"updated":"5 days ago"},
 "chapters":[{"id":55650,"number":"11","title":"Penetration! Bare Fucking Family! 11 [END]",
              "url":"/reader/33728-penetration-bare-fucking-family/55650/page-1","date":"Jul 4, 2020",
              "isNew":false,"isMembers":false,"status":null,"length":null}],
 "related":[ …list items, no tags… ]}
```
* Title `book.title`; alt `book.alt` (may hold "A / B"); type `book.type.label`; status `book.status.label`
  (Ongoing / Completed); description usually null.
* Genres = `book.tags` (category + content). Groups seen: artist, parody, character, language (circle / publisher
  exist as tag types; GUESS they show up as groups too).
* **Has chapters**: `props.chapters` is **newest first** (LIVE: 57-chapter title, `[0].number` = "57"). `date` =
  `"MMM d, yyyy"` (US English). `length` was always null.
* `related` = similar titles **without tags** (unguarded) → the app uses `/search?with=<artist id>&without=6,23` instead.

## 4. Pages: `GET /reader/<bookId>/<chapterId>/page-1` → component `Reader/Show`

`/reader/<bookId>/<chapterId>` and `/reader/<bookId>/<chapterId>/page-1` 301 to the slugged URL. LIVE. Props:
```json
{"page":1,
 "images":["https://hentaicdn.com/hentai/33728/11/ccdn0193.jpg","https://hentaicdn.com/hentai/33728/11/ccdn0194.jpg"],
 "avif":[null,null],"sizes":[[996,1400],[996,1400]],
 "chapter":{"id":55650,"label":"Chapter 11: …","number":"11","base":"/reader/33728-…/55650"},
 "prevChapter":"/reader/33728-…/55609/page-1","nextChapter":null}
```
`images` = every page of the chapter, full size, ready to use (older uploads `.jpg` under `/hentai/<id>/<n>/`, new ones
`.webp` under `/hentai/up/<id>/c<chapter>-<key>/0001.webp` with an `.avif` twin in `avif`). `sizes` = [w, h]. LIVE

## 5. Images (all LIVE)

| what | URL | headers |
|---|---|---|
| cover | `https://hentaicdn.com/hentai/cover/_S33728.jpg` or `…/cover/73690-nhr2onw9.jpg` (always the `cover` field) | none or hentai2read Referer → 200 `image/jpeg`; foreign Referer → 403 |
| page | `https://hentaicdn.com/hentai/25889/57/ccdn1846.jpg`, `…/up/57127/c117189-grolyu/0001.webp` | same |

`Cache-Control: public, max-age=2678400`, `Access-Control-Allow-Origin: *`. Magic bytes checked in `parse.py`
(3 covers + 3 pages: JPEG).

## 6. Behaviour

* No rate-limit headers; ~1 req/s for this whole probe (~60 requests) never hit a limit. App: 700 ms between calls.
* robots.txt disallows crawlers from `/search`, sort paths and `/reader/*/page-N`; we are a reader for one person, not a
  crawler, and keep the pace low.
* Errors: unknown page / id → 404 HTML (component `Error`); stale Inertia version → 409.
