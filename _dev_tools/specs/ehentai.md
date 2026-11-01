# E-Hentai / ExHentai source spec (MangaCracker)

Probed 2026-11-01 from the UK PC (no VPN, no login, GET only, ~1 req/s).
Probe scripts: `_dev_tools\probe_eh\probe_eh.py` (hosts / status), `probe_eh2.py` (list / gallery / page),
`rows.py` / `show.py` (markup dumps), `imghead.py` (image headers). Saved bodies: `_dev_tools\probe_eh\out\`.
Reference parser read: TachiyomiSY `EHentai.kt` (copy in `probe_eh\ref\`). keiyoushi/extensions-source has
**no** E-Hentai source any more (only `pt/exhentainetbr`, an unrelated mirror), so SY is the reference.

Live samples were limited to the **Non-H** category with minor tags excluded (see "Content filter").

Legend: **[LIVE]** = seen in a real reply this session. **[DOC]** = from the SY source / EH wiki docs, not
live-tested (ExHentai, logged-in behaviour, POST API calls).

---

## 0. The UK block: what is actually blocked [LIVE]

| URL | Status from the UK | Body |
|---|---|---|
| `https://e-hentai.org/` (latest), `/?f_search=...`, `/popular`, `/toplist.php?tl=..` | **451** (via Cloudflare) | Full normal page: a notice div `#agegate` at the top, **then the complete gallery list** (`table.itg`, thumbnails, tags, paging links) |
| `https://e-hentai.org/g/<gid>/<token>/` (gallery) | **200** | normal gallery page |
| `https://e-hentai.org/g/<gid>/<token>/?p=N` (thumb pages) | **200** | normal |
| `https://e-hentai.org/s/<pagetoken>/<gid>-<n>` (image page) | **200** | normal |
| `https://api.e-hentai.org/api.php` (GET) | **200** | `{"error":"Empty JSON Request"}` (server Apache, not Cloudflare). Not blocked; POST not tried (GET-only rule) |
| `https://ehgt.org/...` thumbnails | 200 | images |
| `https://<x>.hath.network/...` full images | 200 | images, no Referer needed |
| `https://exhentai.org/` (no cookies) | **302** -> `https://forums.e-hentai.org/remoteapi.php?ex=<base64>` | empty |
| `https://forums.e-hentai.org/` | **403** Cloudflare "Just a moment..." challenge | needs a WebView |
| `https://e-hentai.org/robots.txt` | 200 | disallows `/s/`, `/archiver.php`, `/favorites.php`, ... |

The 451 notice text (trimmed):
```html
<div id="agegate">
  <div><strong>This content is unavailable in the United Kingdom</strong></div>
  <div>due to mandatory privacy-invasive age verification legally required by the
       <a href="https://en.wikipedia.org/wiki/Online_Safety_Act_2023">Online Safety Act</a></div>
  <div><a href="https://www.parliament.uk/...">Click here to complain about this block</a> (seriously)</div>
</div><div id="nb" class="nosel">... normal nav ...
```
So: listing / search / popular / toplists answer 451 but the HTML still holds the listing. OkHttp's
`response.isSuccessful` is false for 451, but `response.body` can still be read. **Whether the app should
read a 451 body is the user's decision** (the site says the content is unavailable in the UK). Galleries
opened by URL (`/g/...`) and their pages work normally (200) from the UK.

Detect the block: `code == 451` or `doc.selectFirst("#agegate") != null`.

---

## 1. Hosts, headers, cookies

| | E-Hentai | ExHentai |
|---|---|---|
| Site | `https://e-hentai.org` | `https://exhentai.org` |
| Thumbnails | `https://ehgt.org/...` (`/w/..webp` list covers, `/g/...` UI icons) [LIVE] | `https://s.exhentai.org/...` [DOC] |
| Images | `https://<random>.hath.network/...` (H@H) [LIVE] | same H@H network [DOC] |
| API | `https://api.e-hentai.org/api.php` [LIVE GET] | `https://s.exhentai.org/api.php` [DOC] - read `var api_url` in the gallery page script instead of hard-coding |

Headers: any normal UA works (tested Android Chrome UA). No Referer needed for thumbnails or images [LIVE].

Cookies (send as one `Cookie` header; SY uses `CookieJar.NO_COOKIES` + an interceptor that sets it):

| cookie | value | effect |
|---|---|---|
| `nw` | `1` | skip the "Content Warning - offensive content" interstitial on galleries [DOC; the Non-H test gallery showed no warning either way] |
| `sl` | `dm_2` | session-less **Extended** list mode (tags grouped by namespace, uploader, rating, pages per row) [LIVE: the mode `<select>` shows Extended selected]. Without it the default is **Compact** [LIVE]. (`sl=dm_t` did NOT switch to thumbnail mode [LIVE]) |
| `ipb_member_id`, `ipb_pass_hash` | from login | logged-in e-hentai; required for ExHentai [DOC] |
| `igneous` | from exhentai.org | required for ExHentai. Value `mystery` = account has no Ex access -> blank pages [DOC] |
| `sk` | settings key | keeps the user's EH settings profile [DOC] |
| `sp` | settings profile number | which profile to use [DOC] |
| `s`, `hath_perks` | optional session / perks [DOC] |

### Login by WebView [DOC]
1. Open `https://forums.e-hentai.org/index.php?act=Login&CODE=00` in a WebView (Cloudflare challenge is
   there [LIVE 403 to plain HTTP]). After login, read `ipb_member_id` and `ipb_pass_hash` from the WebView
   CookieManager for `.e-hentai.org`.
2. For ExHentai: load `https://exhentai.org/` in the WebView with those two cookies set for `.exhentai.org`;
   the site sets `igneous` (and `sk`). Read them back. If `igneous == "mystery"` or the page body is empty,
   the account has no Ex access.
3. Store all four; send them on every request to the matching host. Always add `nw=1` and `sl=dm_2`.

---

## 2. Gallery lists

### Requests
| purpose | URL |
|---|---|
| Latest (front page) | `GET {base}/` |
| Latest, next page | `GET {base}/?next=<gid>` |
| Search | `GET {base}/?f_search=<q>&f_cats=<mask>[&advanced...]` (+ `&next=<gid>` for later pages) |
| Popular | `GET {base}/popular` (one page, ~50 rows, no paging) [LIVE] |
| Toplists | `GET https://e-hentai.org/toplist.php?tl=<11 all-time / 12 past year / 13 past month / 15 yesterday>&p=<0..>` (page index 0-based, up to 200 pages, `table.ptt` paging) [LIVE structure] |
| Tag page | `GET {base}/tag/<ns>:<tag with + for spaces>` (same list markup) [DOC] |
| Uploader | `GET {base}/uploader/<name>` [DOC] |
| Watched (logged in) | `GET {base}/watched` [DOC] |

**Paging = gid cursor** [LIVE]. The nav block (`.searchnav`, twice: ids `u*` top, `d*` bottom):
```html
<a href="https://e-hentai.org/?f_search=...&f_cats=767&next=4266856" id="unext">Next &gt;</a>
<a href="https://e-hentai.org/?f_search=...&f_cats=767&prev=1" id="ulast">Last &gt;&gt;</a>
<a href="...&prev=4264924" id="uprev">&lt; Prev</a>   <!-- on page 2+; on page 1 it is <span id="uprev"> -->
```
- Next page: take `#unext` href as is (or append `next=<gid of the last row>`). No next page when `#unext`
  is a `<span>` (not `<a>`).
- `prev=<gid>` goes back; `prev=1` = last page; `jump=<1d|3d|1w|2w|1m|6m|1y|2y>` and `seek=<YYYY-MM-DD>` jump in time [DOC].
- Result count: `.searchtext p` -> `Found 1,692,557 results.` / `Found about 7,500 results.` / `Found 1,000+ results. Excluded 1 gallery from this page.` [LIVE]
- Empty search: no rows; `.searchwarn` may hold a message (SY throws its text) [DOC].

### Search parameters
| param | meaning |
|---|---|
| `f_search` | query text, space-separated terms (see syntax) |
| `f_cats` | bitmask of categories to **exclude** [LIVE: 767 -> only Non-H rows]. All = 1023. 0 / absent = all shown |
| `f_sh=on` | include expunged [DOC] |
| `f_sto=on` | only with torrents [DOC] |
| `f_sr=on&f_srdd=<2..5>` | minimum rating (stars) [LIVE accepted] |
| `f_sp=on&f_spf=<min>&f_spt=<max>` | page-count range [LIVE accepted] |
| `f_sfl=on` / `f_sfu=on` / `f_sft=on` | disable the user's language / uploader / tag filters (logged-in) [DOC] |
| `f_shash=<sha1>` | file search (seen in image pages) [LIVE link] |

Category bits [LIVE from the search box ids `cat_<bit>`]:
`Misc 1, Doujinshi 2, Manga 4, Artist CG 8, Game CG 16, Image Set 32, Cosplay 64, Asian Porn 128, Non-H 256, Western 512`.
Category name in rows: `div.cn` (list) / `#gdc .cs` (gallery), class `ct1..cta`, text `Non-H` etc; its `onclick` holds `.../non-h`.

### f_search syntax [DOC + LIVE for `ns:tag$` and `-`]
- `ns:tag` with namespaces: `language parody character artist cosplayer group female male mixed other reclass temp`
  (short forms `l: p: c: a: cos: g: f: m: x: o: r:`). Rows show `f:` prefixes in Compact mode [LIVE].
- `$` suffix = exact tag (`female:kemonomimi$`), spaces need quotes: `parody:"uma musume pretty derby$"`.
- `-term` excludes, `~term` = OR group. Plain words match titles and tags.
- Keep to ~8 tag terms (SY's limit hint). The probe used 8 terms fine [LIVE].
- Language filter: `language:english$` (also `translated`, `rewrite`, `speechless`).

### Row parsing, Extended mode (cookie `sl=dm_2`) [LIVE]
Rows: `table.itg > tbody > tr` (Jsoup adds tbody; BeautifulSoup saw `table.itg > tr`). Skip rows with `th` or `.itd` (ads).
```html
<tr><td class="gl1e" style="width:250px"><div style="height:340px;width:250px">
  <a href="https://e-hentai.org/g/4291190/4391f86797/"><img alt="[Circle (Artist)] Title [English]"
     src="https://ehgt.org/w/01/312/00758-inka0joa.webp" title="[Circle (Artist)] Title [English]"/></a></div></td>
<td class="gl2e"><div><div class="gl3e">
  <div class="cn ct9" onclick="document.location='https://e-hentai.org/non-h'">Non-H</div>
  <div id="posted_4291190" onclick="popUp(...)">2026-10-30 03:17</div>
  <div class="ir" style="background-position:0px -1px;opacity:1"></div>
  <div><a href="https://e-hentai.org/uploader/FakeSlip">FakeSlip</a></div>
  <div>41 pages</div>
  <div class="gldown"><img alt="T" src="https://ehgt.org/g/td.png" title="No torrents available"/></div></div>
  <a href="https://e-hentai.org/g/4291190/4391f86797/"><div class="gl4e glname" style="min-height:348px">
    <div class="glink">[Circle (Artist)] Title [English]</div>
    <div><table>
      <tr><td class="tc">language:</td><td><div class="gt" title="language:english">english</div>
                                           <div class="gt" title="language:translated">translated</div></td></tr>
      <tr><td class="tc">female:</td><td><div class="gtl" title="female:kemonomimi">kemonomimi</div></td></tr>
    </table></div></div></a></div></td></tr>
```
| field | selector |
|---|---|
| url / gid / token | `.gl1e a` or `.glname` parent `a` href `/g/<gid>/<token>/` |
| title | `.glink` text (or `.gl1e img[title]`) |
| cover | `.gl1e img` `src` (Extended never lazy-loads [LIVE]) |
| category | `.gl3e .cn` text |
| posted (UTC) | `.gl3e div[id^=posted_]` text, format `yyyy-MM-dd HH:mm` |
| rating | `.gl3e .ir` style (formula below) |
| uploader | `.gl3e a[href*=/uploader/]` text |
| pages | `.gl3e div` whose text matches `(\d+) pages` |
| tags | `.gl4e table tr`: namespace = `td.tc` text minus `:`, tags = each `div` in the 2nd td (`title` = `ns:tag`). Class `gt` = normal, `gtl` = light (weaker vote), `gtw` = weak |
| favourited (logged in) | a child of `.gl3e` with `style` starting `border-color:#xxx` (fav slot colour) [DOC] |

### Row parsing, Compact mode (default, no cookie) [LIVE]
```html
<tr><td class="gl1c glcat"><div class="cn ct9" ...>Non-H</div></td>
<td class="gl2c"><div class="glcut" id="ic4290691"></div>
  <div class="glthumb" id="it4290691" ...><div><img alt="Title" data-src="https://ehgt.org/w/02/751/34868-tgx95oge.webp"
       src="data:image/gif;base64,R0lGOD..." title="Title"/></div>...</div>
  <div><div id="posted_4290691" ...>2026-10-29 21:00</div><div class="ir" style="background-position:-16px -21px;opacity:0.73"></div>...</div></td>
<td class="gl3c glname" ...><a href="https://e-hentai.org/g/4290691/de2313f37b/"><div class="glink">Title</div>
  <div><div class="gt" title="language:english">english</div><div class="gt" title="other:western cg">western cg</div></div></a></td>
<td class="gl4c glhide"><div><a href="https://e-hentai.org/uploader/mechapacha">mechapacha</a></div><div>1582 pages</div></td></tr>
```
- Cover: `.glthumb img` -> `data-src` if present, else `src` (the first rows have a real `src`, later ones a 1x1 gif + `data-src`) [LIVE].
- Tags: flat `.gl3c a div div.gt` (`title` = `ns:tag`); Compact shows only some tags.
- Uploader + pages: `.gl4c div`.
- Header row `<tr><th></th><th>Published</th>...` must be skipped.

Recommendation: always send `sl=dm_2` and parse Extended; keep a Compact fallback.

### Rating from the star sprite [LIVE values + SY formula]
`style="background-position:<X>px <Y>px"` -> `stars = 5 - (-X)/16`; if `Y == -21` subtract 0.5.
Seen: `0px -1px` = 5.0, `0px -21px` = 4.5, `-16px -1px` = 4.0, `-32px -21px` = 2.5, `-64px -21px` = 0.5.
(`opacity` reflects how many votes, ignore.) The gallery page has the exact average (below).

---

## 3. Gallery page `GET {base}/g/<gid>/<token>/` [LIVE, 200 from UK]

```html
<div id="gd1"><div style="width:250px; height:359px; background:transparent url(https://ehgt.org/w/02/745/51119-093b7utl.webp) 0 0 no-repeat"></div></div>
<div id="gd2"><h1 id="gn">Scribbles, Vol. 3</h1><h1 id="gj"></h1></div>
<div id="gdc"><div class="cs ct9" onclick="document.location='https://e-hentai.org/non-h'">Non-H</div></div>
<div id="gdn"><a href="https://e-hentai.org/uploader/lookingL00k">lookingL00k</a> ...</div>
<div id="gdd"><table>
  <tr><td class="gdt1">Posted:</td><td class="gdt2">2026-10-25 15:29</td></tr>
  <tr><td class="gdt1">Parent:</td><td class="gdt2">None</td></tr>          <!-- or <a href="/g/gid/token/">gid</a> -->
  <tr><td class="gdt1">Visible:</td><td class="gdt2">Yes</td></tr>          <!-- "No (Replaced)" / "No (Expunged)" -->
  <tr><td class="gdt1">Language:</td><td class="gdt2">Japanese </td></tr>   <!-- may end in " TR" = translated -->
  <tr><td class="gdt1">File Size:</td><td class="gdt2">54.43 MiB</td></tr>
  <tr><td class="gdt1">Length:</td><td class="gdt2">259 pages</td></tr>
  <tr><td class="gdt1">Favorited:</td><td class="gdt2" id="favcount">70 times</td></tr>
</table></div>
<div id="gdr">... <span id="rating_count">22</span> ... <td colspan="3" id="rating_label">Average: 4.45</td> ...</div>
<div id="taglist"><table>
  <tr><td class="tc">language:</td><td><div class="gtl" id="td_language:english"><a href="https://e-hentai.org/tag/language:english" ...>english</a></div></td></tr>
  <tr><td class="tc">artist:</td><td><div class="gtl" id="td_artist:kaoru_mori"><a href=".../tag/artist:kaoru+mori">kaoru mori</a></div>...</td></tr>
  <tr><td class="tc">other:</td><td><div class="gtl" ...>artbook</div><div class="gtw" ...>sketch lines</div></td></tr>
</table></div>
<script>... var api_url = "https://api.e-hentai.org/api.php"; var gid = 4281254; var token = "30a6774456";
         var apiuid = -1; var apikey = "341ba9d027ae772a1d4c"; var average_rating = 4.45; ...</script>
```
| field | selector |
|---|---|
| title (English/romaji) | `#gn` |
| title (Japanese) | `#gj` (may be empty) |
| cover | `#gd1 div` style -> text between `url(` and `)` |
| category | `#gdc .cs` text |
| uploader | `#gdn a` first (text) - may be plain text for disowned galleries |
| posted / parent / visible / language / size / length / favourited | `#gdd tr`: key `.gdt1` (minus `:`, lower-case), value `.gdt2` |
| rating | `#rating_label` -> `Average: 4.45`; votes `#rating_count`; also JS `average_rating` |
| tags | `#taglist tr`: ns = `td.tc` text minus `:`; tags = `div` texts (or `div[id^=td_]` id after `td_`, `_` = space). Classes as in lists |
| newer versions | `#gnd a` (each followed by text `, added yyyy-MM-dd HH:mm`) - present only when newer versions exist [DOC] |
| api key bits | `<script>` vars `api_url`, `gid`, `token`, `apiuid`, `apikey` |

Dates: `yyyy-MM-dd HH:mm`, UTC.
Chapters: one gallery = one chapter. Optional: the "Parent" chain / `#gnd` newer versions as extra chapters (SY does this).

### Thumbnail pages (the page list) [LIVE]
- `?p=N` (0-based). Default "Normal" thumbs: **20 per page**; 259 pages -> `?p=0..12`.
- `.gtb p.gpc` -> `Showing 1 - 20 of 259 images`.
- Paging `table.ptt td a` (last cell `>`; when disabled it is `td.ptdd`). Number of thumb pages = largest number in `table.ptt a`.
- Page links: `#gdt > a`:
```html
<div class="gt200" id="gdt">
 <a href="https://e-hentai.org/s/d3884e86c3/4281254-1"><div style="width:200px;height:287px;background:transparent
   url(https://zurswtyclg.hath.network/c2/o5t3vfpb9zf9q719rl/4281254-0.webp) -0px 0 no-repeat" title="Page 1: 0.1.jpg"></div></a>
 <a href="https://e-hentai.org/s/1b39b46f44/4281254-2"><div style="... url(...4281254-0.webp) -200px 0 no-repeat" title="Page 2: 1.jpg"></div></a>
```
  - page number: `title` `Page N: filename` (or the `-N` at the end of the href).
  - page token: path segment 2 of `/s/<pagetoken>/<gid>-<n>`.
  - thumbnail: one **sprite** per 20 pages; crop `width x height` at x-offset `-Npx` from the `url(...)` image.
    (Logged-in "Large" thumbs use `<img>` tags inside `#gdt a` instead - SY handles both [DOC].)
- Build the full page list by fetching `?p=0..last` (each 1 request; respect rate limits) - or fetch thumb
  pages lazily as the reader advances.

---

## 4. Image page `GET {base}/s/<pagetoken>/<gid>-<n>` [LIVE]

```html
<script>var gid=4281254;var startpage=1;var startkey="d3884e86c3";var showkey="v50xqjiaoey";var base_url="https://e-hentai.org/";
        var api_url="https://api.e-hentai.org/api.php";var prl=0;var si="L9B";var xres=800;var yres=1148;</script>
<div class="sn">... <div><span>1</span> / <span>259</span></div>
  <a id="next" onclick="return load_image(2, '1b39b46f44')" href="https://e-hentai.org/s/1b39b46f44/4281254-2">...</div>
<div>0.1.jpg :: 800 x 1148 :: 111.5 KiB</div>
<div id="i3"><a ...><img id="img" src="https://zurswtyclg.hath.network/om/274551119/d3884e...-407611-900-1291-jpg/5eb938...-114146-800-1148-wbp/800/9rijw2cr4pidqo249zd/0.1.webp"
      style="width:800px;height:1148px" onerror="this.onerror=null; nl('L9B-498202')" .../></a></div>
<a href="#" id="loadfail" onclick="return nl('L9B-498202')">Reload broken image</a>
<a href="https://e-hentai.org/fullimg/4281254/1/5f49kyqaoey/0.1.jpg">Download original 900 x 1291 398.1 KiB</a>
```
| field | how |
|---|---|
| image | `#img` `src` (here a resampled 800 px webp; H@H host, 200, `image/webp`, no Referer needed [LIVE]) |
| reload key | `#loadfail` onclick text between the quotes -> `L9B-498202`. Retry with `{same /s/ url}?nl=L9B-498202` -> a page with an image on a **different** H@H server [LIVE: `zurswtyclg` -> `aufoxhomaqtndzjncfxt`]. Use on image load failure / timeout |
| next page token | `#next` href / `load_image(n, 'token')` - lets you walk pages without thumb pages |
| page n / total | `.sn div span` 1st / 2nd |
| showkey | JS `showkey` (for the showpage API) |
| original | `a[href*="/fullimg/"]` - **needs login and costs image-limit points; anonymous gets a login redirect** [DOC; not fetched]. Only offer as an explicit "download original" |
| quota exceeded | `#img` src `https://ehgt.org/g/509.gif` -> "image limit reached" error [DOC] |

`/s/` is disallowed in robots.txt (crawlers); a reader opening pages the user views is the normal use.

---

## 5. API `https://api.e-hentai.org/api.php` (POST JSON) [DOC - only a GET was made: 200 `{"error":"Empty JSON Request"}`]

All calls: `POST`, body `application/json`. Space calls out (EH wiki: about 4-5 calls in a row, then ~5 s pause).

**gdata** (metadata for up to 25 galleries per call; good for history / library refresh, and works for galleries known by gid+token):
```json
{"method":"gdata","gidlist":[[4281254,"30a6774456"]],"namespace":1}
```
Reply shape (documented; values here are illustrative, not a live reply) `{"gmetadata":[{"gid":4281254,"token":"30a6774456","archiver_key":"...","title":"...","title_jpn":"...",
"category":"Non-H","thumb":"https://ehgt.org/...","uploader":"...","posted":"1761406140","filecount":"259",
"filesize":57070000,"expunged":false,"rating":"4.45","torrentcount":"1","torrents":[...],
"tags":["language:english","artist:kaoru mori","other:artbook"],"parent_gid":"...","parent_key":"...",
"first_gid":"...","first_key":"...","current_gid":"...","current_key":"..."}]}`
(`posted` = unix seconds string; `filecount`/`rating` are strings; failed entries have `"error":"..."`.)

**gtoken** (page link -> gallery token): `{"method":"gtoken","pagelist":[[4281254,"d3884e86c3",1]]}` ->
`{"tokenlist":[{"gid":4281254,"token":"30a6774456"}]}`.

**showpage** (image for page n without loading the whole /s/ page):
`{"method":"showpage","gid":4281254,"page":2,"imgkey":"1b39b46f44","showkey":"v50xqjiaoey"}` ->
`{"p":2,"s":"/s/1b39b46f44/4281254-2","n":"<nav html>","i":"<div>1.jpg :: 800 x .. :: .. KiB</div>",
"k":"1b39b46f44","i3":"<a ...><img id=\"img\" src=\"https://...hath.network/...\" ...></a>","i5":"...","i6":"<... nl('...') ...>",
"si":..., "x":"800","y":"1148"}` -> parse `i3` with Jsoup for `#img src`, `i6` for the `nl` key.
`showkey` comes from the first `/s/` page's script. imgkey = the page token from the thumb list.

**tagsuggest** (tag autocomplete): `{"method":"tagsuggest","text":"kemono"}` ->
`{"tags":{"<id>":{"id":..,"ns":"female","tn":"kemonomimi"}, ...}}` (min 2-3 chars). Show `ns:tn`, insert as `ns:"tn$"`.

---

## 6. ExHentai differences [DOC]
- Base `https://exhentai.org`; same list / gallery / `/s/` markup and parameters.
- Requires cookies `ipb_member_id`, `ipb_pass_hash`, `igneous` (+ `sk`, optional `sp`, `s`, `hath_perks`).
  Without them: `302` to `forums.e-hentai.org/remoteapi.php?ex=...` [LIVE]. With a bad igneous: empty page / no rows
  (SY: if Ex and the list is empty and `igneous == "mystery"`, show "invalid igneous cookie").
- Thumbnails on `s.exhentai.org` need the same cookies. Image H@H URLs do not.
- API: read `api_url` from the page (`s.exhentai.org/api.php`); gdata with the user's cookies.
- Includes galleries hidden from E-Hentai. The UK 451 behaviour on Ex is unknown (could not be tested without cookies).

---

## 7. Limits / errors [DOC unless noted]
- Too many page loads -> temporary IP ban page: "Your IP address has been temporarily banned for excessive pageloads ... expires in N minutes". Detect by text, stop requests. Keep <= 1 page / s, burst <= 5.
- Image limits (anonymous / per-IP quota): `509.gif` image instead of the page.
- Gallery not found -> 404 or a "Key missing, or incorrect key provided." / "Gallery not available" text page.
- Expunged / replaced galleries: `Visible: No (...)`, newer version link in `#gnd`.

---

## 8. Content filter (hide by default)
Hide galleries carrying any of these tags (check every tag of the row / gallery, any namespace; also add them as
`-ns:tag$` exclusions to `f_search` - EH allows it):
`female:lolicon`, `male:shotacon`, `mixed:lolicon`, `mixed:shotacon`, `female:toddlercon`, `male:toddlercon`,
`mixed:toddlercon`, `female:low lolicon`, `male:low shotacon`, `female:oppai loli`, plus bare `lolicon`, `shotacon`,
`toddlercon`, `loli`, `shota` (old / unnamespaced forms) and, stricter, `female:age regression`, `female:kindergarten uniform`,
`female:randoseru`, `male:randoseru`.
The probe excluded `-female:lolicon$ -male:shotacon$ -female:toddlercon$ -male:toddlercon$ -lolicon -shotacon -toddlercon`
in every live search. Names are from the EH tag set as known, not looked up live (no minor-related lookups were made) -
match on the tag text after the namespace so renamed namespaces still match.

---

## 9. Kotlin outline
- `EHentaiSource(exh: Boolean)`: `baseUrl`, `cookieHeader()` (nw=1, sl=dm_2 + login cookies), interceptor adds it.
- `latest(cursor: Long?)` -> `GET base/?next=cursor`; `search(q, catsMask, minRating, pages, cursor)`; `popular()`.
- `parseList(doc)`: Extended rows (fallback Compact); `nextCursor` = `#unext` href `next` param, null if span.
- Treat `code == 451 || #agegate` as "UK-blocked list" (show a notice; reading the body is the user's choice).
- `gallery(gid, token)`: parse section 3; `pageTokens(p)` from `#gdt a`.
- `imageUrl(pageUrl)`: GET `/s/...`, `#img src`; keep `nl` for one retry; detect `509.gif`.
- Optional API client (`gdata`, `showpage`, `tagsuggest`) with a 1-call-per-second queue.
