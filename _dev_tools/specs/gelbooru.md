# Gelbooru source spec (MangaCracker)

Probed 2026-11-01 from the UK PC (anonymous, GET only, ~1 req/s, no VPN).
Probe script: `_dev_tools\probe_boorus\probe_gelbooru.py` (`ua`, `html`, `api`, `view ID`, `pool [ID]` steps);
markup dumps with `_dev_tools\probe_eh\show.py`. Saved bodies: `_dev_tools\probe_boorus\out\gelbooru_*`
(API docs page: `gelbooru_api_docs.html`). Live searches used `scenery rating:general -loli -shota -lolicon
-shotacon -toddlercon`.

**[LIVE]** = seen in a real reply. **[DOC]** = Gelbooru's own API wiki page (`index.php?page=wiki&s=view&id=18780`,
read live) or general knowledge, not live-tested (everything behind the API key).

---

## 1. Summary
- **dapi (JSON/XML API) needs `api_key` + `user_id`**: every `page=dapi` call (post, tag, comment, user) answered
  **401 with an empty body** without them [LIVE].
- **HTML pages work without a key or login, no Cloudflare**: list, view, pools, tag list, autocomplete [LIVE].
  Any UA works (none, `MangaCracker/1.0`, Chrome, python-requests all 200). Server nginx; sets a `PHPSESSID` cookie (ignore).
- **Images need `Referer: https://gelbooru.com/`** - without it `img4.gelbooru.com` answers 200 **text/html**
  (the site's front page) instead of the image [LIVE]. With it: `image/jpeg` [LIVE].
- Anonymous HTML search accepted 7 terms (no 2-tag limit like Danbooru) [LIVE].

Base: `https://gelbooru.com/index.php`. Send `User-Agent: MangaCracker/1.0` everywhere and
`Referer: https://gelbooru.com/` for every `img*.gelbooru.com` request (image loader too).

---

## 2. HTML (no key)

### Post list `GET index.php?page=post&s=list&tags=<query>&pid=<offset>` [LIVE]
- 42 posts per page. **`pid` is an offset in posts**, not a page number: page 2 = `pid=42`, page 3 = `pid=84`.
- **Max offset 20000** [LIVE]: `pid=19992` ok, `pid=20034` returns 0 posts and the text
  "Unable to go this deep in pagination. Sorry! ..." For deeper scrolling use an id cursor: add `id:<<last id>`
  to the tags and restart at `pid=0` [DOC, the metatag is in Gelbooru's cheatsheet].
- `tags=all` or empty = everything newest first.

Thumbnail markup:
```html
<div class="thumbnail-container">
<article class="thumbnail-preview">
  <a href="https://gelbooru.com/index.php?page=post&amp;s=view&amp;id=15199505&amp;tags=scenery+rating%3Ageneral+..." id="p15199505">
    <img alt="Rule 34 | 2girls, absurdres, artist logo, beach, ..., wide shot" class=""
         src="https://img4.gelbooru.com/thumbnails/a4/55/thumbnail_a455a4db49f38c271ada8ebf02e1af96.jpg"
         title=" 2girls absurdres artist_logo beach ... wide_shot score:0 rating:general">
  </a>
</article>
```
| field | how |
|---|---|
| id | `a[id^=p]` id minus `p` (or `id=` in href) |
| thumbnail | `img` `src` (250 px jpg). md5 = file name after `thumbnail_` (also gives the dir `a4/55`) |
| tags | `img[title]` split on spaces, drop `score:N` and `rating:X` tokens |
| score | `score:N` token in `title` |
| rating | `rating:general|sensitive|questionable|explicit` token in `title` (lower case here; `rating:Sensitive` capitalised on pool pages - compare case-insensitively) [LIVE] |

Paging block [LIVE]:
```html
<div id="paginator"> <b>1</b> <a href="?page=post&amp;s=list&amp;tags=...&amp;pid=42">2</a> ...
  <a alt="next" href="?page=post&amp;s=list&amp;tags=...&amp;pid=42">›</a>
  <a alt="last page" href="?page=post&amp;s=list&amp;tags=...&amp;pid=53550">»</a></div>
```
Has next page = `#paginator a[alt=next]` exists. Total estimate = `last page` pid + 42.
Sidebar tags of the page: `ul#tag-list li.tag-type-{artist|character|copyright|general|metadata}` with
`a[href*="s=list&tags="]` (name) and a grey `span` (count) [LIVE].
There are hidden `div.alert` blocks ("You have mail", "Safe images only mode.") with `display:none` - ignore [LIVE].

### Query syntax [LIVE where marked]
- AND by spaces, `-tag` exclude [LIVE], wildcard `tag*`, OR `{a ~ b}` [DOC].
- Ratings: `rating:general`, `rating:sensitive`, `rating:questionable`, `rating:explicit`; `-rating:explicit` [LIVE general].
- Sort: `sort:score` [LIVE accepted, different order], `sort:score:desc|asc`, `sort:id:asc`, `sort:updated`, `sort:random` [DOC].
- Other: `score:>=10`, `width:>=1000`, `id:<15000000`, `user:<name>`, `md5:<hash>` [DOC].

### Post page `GET index.php?page=post&s=view&id=<id>` [LIVE]
Best source = the data attributes on the image section:
```html
<section class="image-container note-container" data-id="15199505" data-has-sound="false"
  data-tags=" 2girls absurdres artist_logo beach ... wide_shot " data-pools="" data-rating="general"
  data-large-width="850" data-large-height="850" data-width="5016" data-height="3541" data-flags="" data-has-children="false"
  data-score="0" data-file-ext=".jpg" data-source="https://i.pximg.net/img-original/img/2026/09/13/18/01/37/149622661_p0.jpg"
  data-uploader-id="6498" data-md5="a455a4db49f38c271ada8ebf02e1af96" data-file-url="" data-large-file-url="" data-preview-file-url="">
...
<img width="849" height="600" id="image" class="fit-width" src="https://img4.gelbooru.com//samples/a4/55/sample_a455a4db49f38c271ada8ebf02e1af96.jpg">
...
<li><a href="https://img4.gelbooru.com/images/a4/55/a455a4db49f38c271ada8ebf02e1af96.jpg" target="_blank" rel="noopener" style="font-weight: bold;">Original image</a><br /></li>
<meta content="https://img4.gelbooru.com//images/a4/55/a455a4db49f38c271ada8ebf02e1af96.jpg" property="og:image"/>
```
| field | how |
|---|---|
| tags / rating / score / size / md5 / ext / source / pools | `section.image-container` `data-*` (the `data-*-file-url` ones are empty - build URLs instead) |
| sample (reader image) | `#image` `src` (850 px). Posts too small for a sample show the original here |
| original | `a` with text `Original image` (or `meta[property=og:image]`) |
| video | `video#gelcomVideoPlayer source[src]` for webm/mp4 posts [DOC], `data-has-sound` |
| tags by type | `#tag-list li.tag-type-{artist,character,copyright,general,metadata}` -> 2nd `a` text, `span` = count [LIVE] |
| stats | `li` texts `Id: 15199505`, `Posted: 2026-11-01 01:31:03`, `Uploader: <a>danbooru</a>`, `Size: 5016x3541`, `Source: <a>`, `Rating: General`, `Score: <span id="psc15199505">0</span>` [LIVE] |

URL patterns [LIVE] (note the double slash Gelbooru itself prints, `//samples/`; both forms load):
- original `https://img4.gelbooru.com/images/<md5[0:2]>/<md5[2:4]>/<md5>.<ext>`
- sample `https://img4.gelbooru.com/samples/<aa>/<bb>/sample_<md5>.jpg` (exists only if the image is large; check `data-large-width`)
- thumbnail `https://img4.gelbooru.com/thumbnails/<aa>/<bb>/thumbnail_<md5>.jpg`
Image host number (`img4`) can differ - take it from the page, do not hard-code.

### Pools (no key) [LIVE]
List: `GET index.php?page=pool&s=list&pid=<offset>` (25 per page, offset paging, `#paginator` like posts).
```html
<tr> <td ...><a href="index.php?page=pool&amp;s=show&amp;id=74243"><img alt="pool" src="layout/icons/universal.png"/></a></td>
 <td> <div><a href="index.php?page=pool&amp;s=show&amp;id=74243">COOL CHARACTER DESIGN</a></div><div>description...</div>
  <span ...>Created by <a href="...uname=...">user</a> and last updated about 10/28/26 10:24 PM </span></td>
 <td class="sm-hidden" ...><b>4 Images</b></td> <td class="sm-hidden" ...><b>Public</b></td> </tr>
```
Row = `table tr` with >= 4 `td`: id + name = `td[1] div a`, description = `td[1] div` 2nd, count = `td[2]` (`(\d+) Images`),
`Public`/`Private` = `td[3]`. Pools are user-made lists (not comic series like Danbooru's): many are personal
"favourites" with mixed content. No pool search box was found on the list page; there is no pool API.

Show: `GET index.php?page=pool&s=show&id=<id>`:
```html
<h3>Now Viewing: COOL CHARACTER DESIGN</h3> <div ...>description</div>
<div class="thumbnail-container">
 <a href="index.php?page=post&amp;s=view&amp;id=14516332&amp;pool_id=74243" onclick="return remove_post_confirm(14516332, 74243);">
   <img alt="1girl bare_shoulders ..." class="thumbnail-preview" src="https://img4.gelbooru.com/thumbnails//ef/44/thumbnail_ef44....jpg"
        title="1girl ... score:8 rating:Sensitive user:danbooru"/></a> ...
```
Items in pool order: `.thumbnail-container a[href*="s=view"]` -> id from href, md5 from the thumbnail name, tags + rating
from `img[title]`. Then each page image = original / sample URL from md5 (need the ext: fetch the post page, or try
the sample `.jpg` first). The 4-item test pool had one page (`#paginator` showed only `1`); paging of big pools
is probably `pid` like the rest [DOC].
For a "manga" feel on Gelbooru, tag searches like `comic`, `4koma`, `english_text`, `translated`, `<artist>` sorted
by `sort:id:asc` work better than pools.

### Tag autocomplete (no key) [LIVE]
`GET index.php?page=autocomplete2&term=scen&type=tag_query&limit=10` -> `application/json`:
```json
[{"type":"tag","label":"scenery","value":"scenery","post_count":"73286","category":"tag"},
 {"type":"tag","label":"scene (arknights)","value":"scene_(arknights)","post_count":"328","category":"character"}, ...]
```
`category`: `tag` (general), `artist`, `character`, `copyright`, `metadata`. `post_count` is a string.
The old `page=autocomplete&term=` answers the word `Deprecated` [LIVE].
Tag list HTML: `index.php?page=tags&s=list&tags=scen*&sort=desc&order_by=index_count` [LIVE 200; markup not parsed].

---

## 3. dapi (when the user enters `api_key` + `user_id`) [DOC - 401 without a key]
Keys come from the account Options page (user pastes them into the app). Append to every call:
`&api_key=<key>&user_id=<id>`. The docs say keyed requests are throttled less ("will not be limited if you have
contributed to the Patreon").

**Posts** `index.php?page=dapi&s=post&q=index&json=1&limit=<<=100>&pid=<page number>&tags=<query>`
(`pid` here is a **page number**, not an offset; `id=<post id>` for one post; `cid=` change time; `deleted=show&last_id=`).
Reply shape (field list documented; values illustrative):
```json
{"@attributes":{"limit":100,"offset":0,"count":53592},
 "post":[{"id":15199505,"created_at":"Sat Nov 01 01:31:03 -0500 2026","score":0,"width":5016,"height":3541,
   "md5":"a455a4db49f38c271ada8ebf02e1af96","directory":"a4/55","image":"a455a4db49f38c271ada8ebf02e1af96.jpg",
   "rating":"general","source":"https://...","change":1761978663,"owner":"danbooru","creator_id":6498,"parent_id":0,
   "sample":1,"preview_height":177,"preview_width":250,"tags":"2girls absurdres ...","title":"",
   "has_notes":"false","has_comments":"false","file_url":"https://img4.gelbooru.com/images/a4/55/a455....jpg",
   "preview_url":"https://img4.gelbooru.com/thumbnails/a4/55/thumbnail_a455....jpg",
   "sample_url":"https://img4.gelbooru.com/samples/a4/55/sample_a455....jpg","sample_width":850,"sample_height":600,
   "status":"active","post_locked":0,"has_children":"false"}]}
```
- No results: the `post` key is missing (not an empty list) - make it nullable.
- `sample_url` is empty when `sample == 0` -> use `file_url`.
- `created_at` is `EEE MMM dd HH:mm:ss Z yyyy`. Several booleans are strings (`"false"`).
- Without `json=1` the reply is XML (`<posts count= offset=><post .../></posts>`).
- The same 20000-offset limit applies (`pid * limit <= 20000`); use `id:<N` cursors.

**Tags** `index.php?page=dapi&s=tag&q=index&json=1` + one of `name=`, `names=a b c`, `name_pattern=scen%` (SQL LIKE,
`%` / `_`), `id=`, `after_id=`; `orderby=date|count|name`, `order=ASC|DESC`, `limit` (default 100) ->
`{"@attributes":{...},"tag":[{"id":..,"name":"scenery","count":73286,"type":0,"ambiguous":0}]}`;
`type` 0 general, 1 artist, 3 copyright, 4 character, 5 metadata, 6 deprecated.

**Comments** `index.php?page=dapi&s=comment&q=index&post_id=<id>` (XML only):
`<comments type="array"><comment created_at="..." post_id="..." body="..." creator="..." id="..." creator_id="..."/></comments>`.

**Users** `index.php?page=dapi&s=user&q=index&json=1&name=` / `name_pattern=` (not needed by a reader).
No pool endpoint exists in dapi - pools are HTML only.

---

## 4. Rate limits
- No rate-limit headers seen on HTML or autocomplete [LIVE]. Gelbooru documents that it may throttle the API and
  requires keys to "mitigate abusive behavior" [DOC]. Keep **<= 1-2 requests/s**, cache list pages, and back off on
  429 / 503 or an HTML error page.
- Images: browser-like parallel loading is fine, but always with the Referer.

---

## 5. Content filter (hide by default)
Add exclusions to every query (Gelbooru has no tag-count limit in HTML search) AND filter client-side on the
`title` / `data-tags` / `tags` string:
`loli`, `shota`, `lolicon`, `shotacon`, `toddlercon`, `toddler`, `child`, `female_child`, `male_child`, `young`,
`oppai_loli`, `aged_down`, `kindergarten_uniform`, `randoseru`.
Pool names / descriptions: hide pools whose name or description mentions `loli` / `shota`.
List assembled from Gelbooru's (Danbooru-mirrored) tag set as known; not looked up live. The live probe always sent
`-loli -shota -lolicon -shotacon -toddlercon` and `rating:general`.

---

## 6. Kotlin outline
- OkHttp interceptor: `User-Agent: MangaCracker/1.0`; for hosts `img*.gelbooru.com` add `Referer: https://gelbooru.com/`
  (the image loader must use the same client). Ignore `PHPSESSID`.
- `search(tags, page)` -> HTML `pid = (page-1)*42`; stop at 20000, then switch to `id:<lastId` cursor.
- `parseList(doc)`: `article.thumbnail-preview`, `#paginator a[alt=next]`.
- `post(id)`: `section.image-container` data attributes + `#image src` + "Original image" link.
- `autocomplete(term)`: `page=autocomplete2`.
- `pools(pid)` / `pool(id)`: HTML as above.
- If the user saved `api_key` + `user_id`: switch `search` / `post` to dapi JSON (100 per page, page-number `pid`).
