# comix.to source spec (tested 2026-11-01)

Everything below was tested live on 2026-11-01 (site build `main-tnswp0-DCtXfAe1.js`, Chromium 145 headless +
Python `requests`). Probe scripts and their raw output are in `_dev_tools\probe_comix\` (`out\` holds the JSON).
The keiyoushi Mihon extension (`src/en/comix`, versionCode 59, last commit 2026-10-31) was read for cross-checking;
copies are in `probe_comix\out\ext\` (`Comix.kt`, `Dto.kt`, `Filters.kt`, `Descrambler.kt`, the asset `.js` files).

## 0. The short version

comix fights scrapers on purpose (a keiyoushi asset file opens with "Every build you re-mangle, this file gets a
little shorter"). Three layers:

| layer | what it does | what we can do |
|---|---|---|
| `?_=s8a.<16 chars>` token | Every `/api/v1/*` call except `tags/search` needs it. Without it: `403 {"code":"missing_token"}`. The token signs the exact URL: same URL gives the same token (deterministic), any changed parameter gives `403 invalid_token`. A captured token works from a plain client **without cookies** | The token is made inside an obfuscated bytecode VM (`secure-*.js`). It cannot be made in Kotlin. Let the site's own JS make the call (section 2) |
| Encrypted replies | `manga/{hid}/chapters` and `chapters/{id}` answer `{"e":"<base64>"}`. The list endpoints (`/manga`, `/manga/top`) answer **plain** JSON | Decryption is also inside the VM (it does not use `JSON.parse` or `TextDecoder`). Let the site's own JS decrypt (section 2) |
| Name mangling | After decrypting, the client renames every field (`id`→`lo`, `hid`→`n9`, `title`→`_Q`...), and the names **change with every build** (checked: none of the 29 Oct names exist on 1 Nov) | Never read the client's cached objects by key. `cxlib.js` reads the plain arrays the site's mapper walks over (section 2) |

So the app needs:
* **Plain OkHttp** for: the home rows, title details + "recommended" + groups (all from the HTML's
  `script#initial-data`), tag / author / artist name → id lookup (`/api/v1/tags/search`), the filter option lists,
  and the page images.
* **One hidden WebView** with a comix page loaded, plus the injected `probe_comix\cxlib.js`, for: search / browse /
  popular / latest with any filters and paging, the chapter list (all pages), and the page-image list of a chapter.

The keiyoushi extension uses a WebView for everything too (it scrapes the DOM: navigates `/browse?...` and harvests the
cards, clicks through the chapter pages, scrolls the reader to collect `<img src>`). The `cxlib.js` approach below is
faster and gives full JSON with the site's normal field names, but it depends on four things that a site update can
break (listed in 2.4). Keep the keiyoushi DOM approach in mind as plan B.

---

## 1. Plain HTTP (OkHttp, no cookies, no WebView)

Base `https://comix.to` (keiyoushi also lists the mirror `https://comix.ws`). Any UA works (tested: Android Chrome UA,
`okhttp/4.12.0`, an empty UA, and an app `X-Requested-With`, all 200). No Cloudflare challenge was shown to the plain
client (the browser silently gets a `cf_clearance` cookie; the plain client was never asked for it).
Rate: keiyoushi limits its client to 2 req/s on comix.to; stay at or under 1 req/s.

### 1.1 `script#initial-data` (every HTML page)

```kotlin
val raw = Jsoup.parse(html).selectFirst("script#initial-data")!!.data()   // JSON
val queries = Json.parseToJsonElement(raw).jsonObject["queries"]!!.jsonObject
// keys are JSON-encoded TanStack query keys (strings), e.g. "[\"manga\",\"detail\",\"50xvk\"]"
```
Match keys by their decoded array, not by the exact string (the trailing params object can change).
Field names in `initial-data` are the normal (unmangled) ones.

### 1.2 Home rows: `GET https://comix.to/` (432 KB HTML)

| query key | what | use as |
|---|---|---|
| `["manga","top",{"type":"trending","days":1,"limit":50,"content_rating":["safe","suggestive"]}]` | array of 50 manga | **Popular** (trending today) |
| `["manga","top",{"type":"follows","days":1,"limit":50,...}]` | array of 50 manga | Most followed today |
| `["manga","list",{"scope":"hot","order":{"chapter_updated_at":"desc"},"page":1,"limit":31,...}]` | `{items:[31], meta}` | **Latest** (hot updates) |
| `["manga","list",{"order":{"created_at":"desc"},"page":1,"limit":10,...}]` | `{items:[10], meta}` | Recently added |

The home rows are always safe + suggestive only. For other ratings or for paging use the WebView list call (2.3).

### 1.3 Title page: `GET https://comix.to/title/<hid>-<slug>` (24 KB)

The slug can be anything after the hid; `<hid>` alone is the real id. Top-level `initial-data` keys:
`page:"manga"`, `manga:{hid,id}`, `queries`:

* `["manga","detail","<hid>"]` → the full manga object (sample in 1.6). **No chapters here** (they come from the
  encrypted API, section 2).
* `["manga","recommended","<hid>",1,{"content_rating":[...]}]` → `{items:[6 manga + "recPivot":{votes,byUserId}],
  meta:{total:54, perPage:6, page:1, lastPage:9, hasNext:true}}` → **Recommended / similar series**.
* `["manga","groups","<hid>"]` → `[{id:4725, name:"Asura Scans", slug:"asurascan"}, ...]` (scanlation groups).
* `["manga","collections","<hid>"]` → user collections containing it.

The chapter page HTML (`/title/<hid>-<slug>/<chapterId>-chapter-<n>`, 7.9 KB) has only the detail plus
`read:{mangaId, mangaHid, chapterId, chapterNumber}`, no image list.

### 1.4 Name → id lookup (no token needed): `GET /api/v1/tags/search?type=<type>&q=<text>&limit=20`

Answer: `{"status":"ok","result":[{"id":99588,"label":"Vampire/s","slug":"vampires"}, ...]}` (fuzzy; pick the exact
label match when there is one, as keiyoushi does). Working `type`s: `tag`, `genre`, `author`, `artist`, `format`,
`publisher` (`demographic` returns `[]`; `theme` is not a route). Use it for author / artist search (2.3 `authors[]` /
`artists[]` take ids) and for free-text tag filters.

### 1.5 Filter option lists: `GET https://comix.to/browse` → `initial-data.list.options`

`genres`(31) `demographics`(4) `formats`(9) `statuses`(5) `types`(4) `years`(40) `sorts`(13). Cache them; they rarely
change. Current values:

* **genres** (`genres_in[]` / `genres_ex[]` ids): Action 6, Adult 87264, Adventure 7, Boys Love 8, Comedy 9, Crime 10,
  Drama 11, Ecchi 87265, Fantasy 12, Girls Love 13, Harem 40, Hentai 87266, Historical 14, Horror 15, Isekai 16,
  Magical Girls 17, Mature 87267, Mecha 18, Medical 19, Mystery 20, Philosophical 21, Psychological 22, Romance 23,
  Sci-Fi 24, Slice of Life 25, Smut 87268, Sports 26, Superhero 27, Thriller 28, Tragedy 29, Wuxia 30.
* **formats** (also go in `genres_in[]`/`genres_ex[]`): 4-Koma 93164, Adaptation 93167, Anthology 93165,
  Award Winning 93166, Doujinshi 93168, Full Color 93172, Long Strip 93170, Oneshot 93169, Web Comic 93171.
* **demographics** (`demographics[]`): Shoujo 1, Shounen 2, Josei 3, Seinen 4.
* **types** (`types[]`): manga, manhwa, manhua, other. **statuses** (`statuses[]`): releasing, finished, on_hiatus,
  discontinued, not_yet_released.
* **sorts** (`order[<field>]=asc|desc`): relevance (only with a keyword), chapter_updated_at, created_at, title, year,
  score, views_7d, views_30d, views_90d, views_total, follows_total.
* Narrative **tags** (about 18 000, e.g. "Vampire/s" 99588) are looked up by name with 1.4 and also go in
  `genres_in[]` / `genres_ex[]` (tested: excluding 5 tag ids changed the total 110626 → 110222).

### 1.6 Manga object (plain names; trimmed real sample from the title page)

```json
{"id":119297,"hid":"50xvk","title":"I Married the Dragon I Killed",
 "altTitles":["Naega Jugin Deuraegon-gwa Gyeolhonhaetda","내가 죽인 드래곤과 결혼했다","My Slain Dragon Bride"],
 "type":"manhwa","status":"releasing","originalLanguage":"ko",
 "poster":{"medium":"https://static.comix.to/02b1/i/9/93/6a5513f4e4ddb@280.jpg",
           "large":"https://static.comix.to/02b1/i/9/93/6a5513f4e4ddb.jpg"},
 "latestChapter":21,"finalChapter":0,"finalVolume":0,"hasChapters":true,
 "chapterUpdatedAtFormatted":"4d ago","createdAtFormatted":"3mos ago","updatedAtFormatted":"3w ago",
 "startDate":"2026","endDate":"?","year":2026,"rank":360,
 "synopsis":"The 9th Circle, a realm spoken of as ... (markdown)","synopsisHtml":"... (html)",
 "followsTotal":35146,"ratedAvg":9.2,"ratedScore":9.04,"ratedCount":1514,"contentRating":"safe",
 "links":{"al":"https://anilist.co/manga/214050","mal":"https://myanimelist.net/manga/194872",
          "mu":"https://www.mangaupdates.com/series/roci33b","md":null,"mb":"https://mangabaka.org/596433"},
 "url":"/title/50xvk-i-married-the-dragon-i-killed",
 "genres":[{"id":6,"title":"Action","slug":"action"},{"id":9,"title":"Comedy","slug":"comedy"}],
 "demographics":[{"id":2,"title":"Shounen","slug":"shounen"}],"formats":[],"tags":[],
 "authors":[{"id":108813,"title":"만둣불 (Mandutbul)","slug":"mandutbul"}],
 "artists":[{"id":108814,"title":"젤링 (Jelling)","slug":"jelling"}],
 "publishers":[{"id":31156,"title":"Naver","slug":"naver"}],"sources":[],
 "firstChapterUrl":"/title/50xvk-i-married-the-dragon-i-killed/10839967-chapter-1",
 "latestChapterUrl":"/title/50xvk-i-married-the-dragon-i-killed/11590440-chapter-21"}
```
List items (home, list API, recommended) have the same fields **minus** genres / demographics / formats / tags /
authors / artists / publishers / sources / first- and latestChapterUrl.

* **Titles**: `title` + `altTitles[]`. **Description**: `synopsis` (markdown) or `synopsisHtml`.
* **Cover**: `poster.medium` = `...@280.jpg` (280 px wide JPEG; **use in the grid**); `poster.large` = the original
  (full size, for the details header). keiyoushi's DTO also accepts `poster.small`, not seen in current data.
* **Genres**: `genres[].title` + `demographics[].title` (+ `formats`, + `tags` if you want the long tag list).
* **Authors / artists**: `authors[].title`, `artists[].title` (ids for 2.3 `authors[]` / `artists[]`).
* **Status**: releasing → ongoing, finished → completed, on_hiatus, discontinued → cancelled, not_yet_released.
* **Content rating**: `contentRating` = safe | suggestive | erotica | pornographic.
* **Page count**: not on the manga; per chapter it is the length of the page list (2.2). `latestChapter` /
  `finalChapter` give chapter numbers.
* Dates are only relative strings ("4d ago", "3mos ago", "1yr ago"): parse with keiyoushi's `parseRelativeDate`
  (units s/m/h/d/w/mo/mos/y/yr/yrs/min/sec/hr/day/week/month/year + plural) and keep the first date you stored.

### 1.7 Images (page images + covers)

* Page images are on rotating throw-away hosts (`https://1xx.softdesignstudio.site/hi/<token>`, 73 different
  `*.site` domains in one 149-page chapter), webp, `Cache-Control: public, max-age=2592000`.
* **Send NO Referer and NO Origin.** Tested on one page image: no Referer → 200 webp 800x1510;
  `Referer: https://comix.to/` + `Origin` → **403** (Cloudflare HTML). keiyoushi says the same ("Comix image domains
  block any image requests with Referer/Origin"). Coil / OkHttp: strip both headers for comix images.
* Width / height come with the page list (2.2): width is 800 here, height varies (long-strip slices).
* The image I fetched was a normal webp. keiyoushi's `Descrambler.kt` also handles replies that carry
  `x-scramble-seed / x-scramble-grid: 5x5 / x-scramble-algo / x-scramble-hash` (5x5 tile shuffle) and
  `x-enc-seed / x-enc-len / x-enc-algo` (XOR of the first bytes). Port it as an OkHttp interceptor so such images
  still decode (copy in `probe_comix\out\ext\Descrambler.kt`; the hash table is `"03632"→58414, "02900"→117532`).
* When a page image fails, keiyoushi retries the same URL with the path prefix swapped:
  `/hi/ /fcf/ /i5/ /si/ /i/ /sii/ /ii/` (regex `/(?:i5|s?i+)/`), and retries 502/503/522/523/404 up to 10 times
  1.5 s apart with `&r=<n>` added.
* Covers (`static.comix.to/...@280.jpg`) were not fetched in this probe (the 2-image limit went to the page image);
  the user's comix userscript shows them on comix pages fine. Treat them like page images (try without Referer).

---

## 2. WebView part (token + decryption by the site's own JS)

### 2.1 Set-up (tested headless in Chromium; same JS for Android `WebView.evaluateJavascript`)

1. Hidden WebView (off-screen / not attached, JavaScript on, DOM storage on, images can be blocked, normal Chrome UA).
   Do **not** send an app `X-Requested-With` on the reader route (keiyoushi: "The reader route refuses an app
   package"; it loads the reader with `X-Requested-With: com.android.chrome`). Block pop-ups
   (`setSupportMultipleWindows(true)` and refuse `onCreateWindow`): comix lays an invisible ad link over pages.
   keiyoushi also sets `localStorage.__pu1 = "[<now>]"` at document start so the ad overlay counts as already shown.
2. Load a comix page and wait until it has run (title page: about 3-6 s). Load the page that holds the query you
   need the first time: home or `/browse` for lists, `/title/<hid>` for chapters, a reader URL for page images.
3. `evaluateJavascript(<contents of probe_comix\cxlib.js>)`, then call `window.__cxlib.*` (async; on Android use a
   `@JavascriptInterface` callback or `WebMessageListener`, as keiyoushi does, because `evaluateJavascript` does not
   await promises).
4. Do **not** patch `JSON.parse` or other natives at document start: when I did, the site's requests changed from
   `v=0&w=0` to `v=2&w=0000f` (it reports tampering). `cxlib.js` wraps `Array.prototype.map` and XHR `open/send`
   only for the duration of its own call; the requests it made all carried `v=0&w=0`.

How `cxlib.js` works (about 150 lines, commented):
* finds the page's TanStack `QueryClient` through the React fiber tree (`__reactContainer$…` → props `client`),
* reads the api method name from a cached query's `queryFn` source, e.g. key `["manga","chapters",…]` →
  `()=>nn.qL(a,A)`, key `["chapters","detail",…]` → `()=>Tn.mH(d)`, key `["manga","list",…]` → `()=>nn.Xi(m)`,
* resolves `nn` / `Tn` to the apicore module export through apicore's own `export{nn as f,Tn as n,…}` list and
  `import()`s the same module URL (same live instance, so the site's axios instance with its token + decrypt
  interceptors is used),
* calls the method with **our** parameters and returns plain data: the raw XHR body for plain endpoints, or for
  encrypted ones the plain arrays the site's mapper iterates.
* `lib.learn()` returns the method map (e.g. `{"manga,chapters":{"local":"nn","path":["qL"]}}`) and
  `(await lib.api()).build` the bundle URL. Store them per build and `lib.teach(map)` on the next page load, so a
  single comix page (any page) can serve every call until the build URL changes (tested: methods learnt on the reader
  page, taught to the title page, `chapter()` worked there).

### 2.2 Calls (all tested 2026-11-01, results in `probe_comix\out\cx_*.json`)

**Chapter list** `__cxlib.chapters(hid, {page, limit, order:{number:"desc"|"asc"}})`
→ wire: `GET /api/v1/manga/<hid>/chapters?page=1&limit=100&order[number]=asc&v=0&w=0&_=s8a.…` (encrypted)
→ returns `{items:[…], url, status}`; every chapter (plain names):
```json
{"id":11497135,"mangaId":119297,"number":19,"volume":0,"name":"","language":"en","isOfficial":false,
 "votes":112,"createdAtFormatted":"2w ago","groupId":4725,"group":{"id":4725,"name":"Asura Scans"},
 "creator":null,"url":"/title/50xvk-i-married-the-dragon-i-killed/11497135-chapter-19"}
```
* `limit` max is **100** (300 → 422). Paging: `page` 1, 2, … until fewer than `limit` items come back (the plain
  `meta` is not captured; the mangled one is). The list holds **one entry per chapter per group** (this series: 13
  groups, so 100 entries covered chapters 1-9). Show group = `group.name` (null group: "Official" if `isOfficial`,
  else "Unknown"); dedupe per `number` if wanted (keiyoushi prefers isOfficial, then group id 10702 = official, then
  most `votes`, then newest id).
* Title: `"Chapter " + number (drop ".0") + (": " + name if not empty)`; `volume` 0 = none.
* Chapter URL = `https://comix.to` + `url` (form `/title/<hid>-<slug>/<id>-chapter-<number>`).
* **Pace chapter-list pages 2.5-5 s apart with a random gap** (keiyoushi: "Comix blocks chapter-list paging that is
  too fast or evenly spaced" → WAF captcha, section 3).

**Page images** `__cxlib.chapter(chapterId)`
→ wire: `GET /api/v1/chapters/<id>?v=0&w=0&_=s8a.…` (encrypted)
→ `{pages:[{"width":800,"height":1510,"url":"https://1xx.softdesignstudio.site/hi/bEqPbYfo…"}, …]}`
(149 pages for ch. 20, 118 for ch. 21). Page count = `pages.length`. Fetch images per 1.7 (no Referer). The mangled
client object also carries 5 fallback host names per page and a key (`kkplayer.wtf`, `pq4`) that the site's own
reader uses to switch hosts; they are not in the plain array. Image URLs may expire: ask for the list when the user
opens the chapter, not days before.

**Browse / search / popular / latest** `__cxlib.list(params)` → wire `GET /api/v1/manga?<params>&_=s8a.…` (plain)
→ returns the server JSON as is: `{"status":"ok","result":{"items":[manga…],"meta":{"total":133,"perPage":5,
"page":1,"lastPage":27,"from":1,"to":5,"hasNext":true,"hasPrev":false}}, "_url": "<signed url>"}`.

| param (JS object → query) | meaning |
|---|---|
| `keyword: "dragon"` | text search (use with `order:{relevance:"desc"}`) |
| `genres_in: [6, 12]` / `genres_ex: [23]` | include / exclude genre, format **and tag** ids (several allowed) |
| `genres_mode: "and"\|"or"` | how several includes combine (default and) |
| `types: ["manhwa"]`, `statuses: ["finished"]`, `demographics: [2]` | arrays |
| `authors: [108813]`, `artists: [108814]` | ids from 1.4 (`by_author` test: 1 hit, the right one) |
| `year_from`, `year_to`, `min_chap` | numbers (from keiyoushi's filters; not separately tested) |
| `order: {views_30d: "desc"}` | one sort field (list in 1.5) |
| `content_rating: ["safe","suggestive","erotica","pornographic"]` | array (**a comma string gives 422**). Omitted = all four (110 626 titles); safe+suggestive = 80 119 |
| `page`, `limit` | `limit` 28 is the site's default; 5-50 tested |

Feeds: **Popular** `{order:{views_30d:"desc"}}` (or `views_7d`, `follows_total`, `score`); **Latest**
`{order:{chapter_updated_at:"desc"}}`; **Search** keyword + filters; **By author / artist** `{authors:[id]}`.

**Top lists** (plain JSON, through the learnt `manga,top` method; `cxlib.capture` + `fn('manga,top')`, see
`probe_comix\test_cx2.py`): `{type:"trending"|"follows", days:1|7|30, limit, content_rating:[…]}` →
`{"status":"ok","result":[manga…]}` (no paging).

**Recommended, more pages**: the title page gives page 1 (6 items, `lastPage` 9). The site method behind
`["manga","recommended",hid,page,{content_rating}]` is `nn.Iy(hid, page, params)` (seen in the queryFn
`()=>nn.Iy(a,b,m)`; not called with page 2 in this probe).

### 2.3 Errors seen

* `403 {"message":"Missing token.","code":"missing_token"}` (no `_`), `403 {"code":"invalid_token"}` (URL changed
  after signing), `422` (bad parameter: comma `content_rating`, `limit` > 100), `404 {"message":"The route … could
  not be found."}`.
* keiyoushi also handles `403 captcha_required`: the site then shows its own captcha page at `/@waf/challenge`
  (Cloudflare Turnstile, sitekey `0x4AAAAAAB_OAw9NJC7858li` in the client config). The app should then show the
  WebView to the user to solve it (never solve it automatically).

### 2.4 What can break `cxlib.js` (check these first after a site update)

1. The QueryClient is no longer reachable through the React fiber props (`client`).
2. queryFns stop being simple `()=>local.method(args)` arrow functions.
3. apicore stops ending with an `export{…}` list, or main no longer imports `./apicore-*.js`.
4. The mapper stops using `Array.prototype.map` on the plain arrays (then `chapters()` / `chapter()` return empty
   arrays; `list()` still works because it reads the plain XHR body).
Plan B = keiyoushi's DOM harvesters (`harvest_browse.js`, `harvest_chapters.js`, `chapter_pages.js` in
`probe_comix\out\ext\`).

---

## 3. Rate limits, Cloudflare, WebView needs

* No rate-limit headers were sent. keiyoushi: 2 req/s on comix.to, 2 req/s on page images, chapter-list pages 2.5-5 s
  apart with a random gap, at most 3 reader walks per chapter per 10 min. Recommended for MangaCracker: ≤ 1 API call
  per second, random 2.5-5 s between chapter-list pages, page images ≤ 2-3 at a time.
* Cloudflare: plain HTML and `tags/search` worked without cookies; no challenge page appeared. The WebView gets a
  `cf_clearance` cookie silently.
* Needs a WebView: token + decryption (lists with filters / paging, chapter list, page list), and the WAF captcha
  when it appears. Everything in section 1 does not.

## 4. Hide-by-default tags (minors)

Exclude these with `genres_ex[]` on every list call and drop any title whose `tags[]` contain them (ids from
`/api/v1/tags/search?type=tag`, 2026-11-01). Only names / ids were looked up; no such titles were opened.

| id | label |
|---|---|
| 101462 | Loli |
| 113446 | Lolicon |
| 102568 | Loli Female Lead |
| 102407 | Loli Protagonist |
| 104264 | Loli Mother |
| 105051 | Lolicon Protagonist |
| 107660 | Loli/grooming/child porn |
| 100753 | Shota |
| 113898 | Shotacon |
| 102228 | Shota Protagonist |
| 102229 | Shota Uke |
| 103278 | Shota Seme |
| 108794 | Shota Character/s |
| 101093 | Underage Uke |
| 101092 | Underage Seme |
| 99608 | Child Rape |

Hide when the title is also `erotica` / `pornographic` (these tags are fine in normal stories): 100122 Child
Protagonist, 99242 Child/ren, 101775 Elementary School Student/s, 99680 Elementary School, 101600 Lolita/s
(usually the fashion). Re-check with `tags/search?type=tag&q=loli|shota|child|underage` now and then: new tags appear.

## 5. Files

* `probe_comix\cxlib.js`: the injectable library (ship it as an app asset).
* `probe_comix\test_cxlib.py`, `test_cx2.py`, `test_limit.py`: end-to-end tests of it (headless).
* `probe_comix\plain.py`, `plain2.py`, `initdata.py`: plain-HTTP checks; `imgcheck.py`: Referer test (1 image, twice).
* `probe_comix\net.py`, `decrypted.py`, `rq.py`, `modules.py`, `apiobj.py`, `maphook.py`, `parsehook.py`: how the
  token / encryption / mangling were worked out. `getjs.py` downloads the current bundles to `out\js\`.
