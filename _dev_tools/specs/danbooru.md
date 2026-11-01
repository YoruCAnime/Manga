# Danbooru source spec (MangaCracker)

Probed 2026-11-01 from the UK PC (anonymous, GET only, ~1 req/s, no VPN).
Probe scripts: `_dev_tools\probe_boorus\probe_danbooru.py` (UA test + endpoints), `probe_danbooru2.py`
(tag limit, pools), `probe_danbooru3.py` (which metatags are free), `probe_danbooru4.py` (limits, pool orders).
Saved replies: `_dev_tools\probe_boorus\out\danbooru_*.json`. Reference: keiyoushi `src/all/danbooru`
(copy in `probe_boorus\ref\`). Live samples used `rating:g` with neutral tags (scenery, cloud) only.

**[LIVE]** = seen in a real reply. **[DOC]** = Danbooru docs / source / keiyoushi, not live-tested.

---

## 1. Getting past Cloudflare: it is the User-Agent [LIVE]

| User-Agent sent | `posts.json` | `cdn.donmai.us` image |
|---|---|---|
| none (header absent) | **200** JSON | **200** |
| `MangaCracker/1.0` | **200** | **200** |
| `MangaCracker/1.0 (Android; personal reader)` | **200** | - |
| `okhttp/4.12.0` (OkHttp default) | **200** | - |
| `python-requests/2.32` | 403 "Just a moment..." | - |
| Android Chrome (`Mozilla/5.0 ... Chrome/130 Mobile Safari/537.36`) | **403** challenge | **403** |

So: **no WebView / Cloudflare pass needed. Use a plain app UA (`MangaCracker/1.0`) on every request,
including image loads.** A browser-like UA is what triggers the challenge - so the image loader (Coil / Glide
/ a WebView) must NOT use the default WebView UA for `cdn.donmai.us`. keiyoushi also strips cookies and adds
`Accept: image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5`, `Sec-Fetch-Dest: image`,
`Sec-Fetch-Mode: no-cors`, `Sec-Fetch-Site: same-site` on CDN requests [DOC] - harmless to add.
If a 403 HTML "Just a moment" ever comes back: fall back to a WebView pass (cf_clearance cookie + the WebView's
own UA together) - not needed today.

Base: `https://danbooru.donmai.us`. All endpoints accept `.json` (and `.xml`). Errors are JSON:
```json
{"success":false,"error":"PostQuery::TagLimitError","message":"You cannot search for more than 2 tags at a time.","backtrace":[...]}
```
Show `message`. Seen: 422 TagLimitError, 410 `PaginationExtension::PaginationError` "You cannot go beyond page 1000.",
500 "The database timed out running your query." (on `pools.json?search[post_tags_match]=...`) [LIVE].

Add `only=` to any index call to trim fields (nested `media_asset[variants]` works) [LIVE].

---

## 2. Posts (single images)

### List / search `GET /posts.json` [LIVE]
| param | meaning |
|---|---|
| `tags` | space-separated query (below). Empty = newest posts |
| `limit` | 1..200 (201 silently returns 200) [LIVE]. Default 20 |
| `page` | `1..1000` numbered (1001 -> 410 error) [LIVE]; or cursor `b<id>` = posts with id below `<id>`, `a<id>` = above [LIVE `b9000000`]. Use `b<last id>` for endless scroll (no 1000-page cap, faster) |
| `only` | field list, e.g. `id,rating,score,tag_string,tag_string_artist,file_ext,image_width,image_height,file_url,large_file_url,preview_file_url,media_asset[variants]` |

Count: `GET /counts/posts.json?tags=rating:g scenery` -> `{"counts":{"posts":67412}}` [LIVE].
One post: `GET /posts/<id>.json` [LIVE].
Popular: `GET /explore/posts/popular.json?date=2026-10-31&scale=day|week|month&limit=..` [LIVE]; this one is rate limited
(header `x-rate-limit: {"action":"posts:popular","cost":1,"rate":0.0166,"burst":50,...}` = burst 50, then 1/min) [LIVE].

### Query syntax
- `tag1 tag2` = AND; `-tag` exclude; `~a ~b` OR; `tag*` wildcard [LIVE all accepted].
- Ratings: `rating:g` (general), `rating:s` (sensitive), `rating:q` (questionable), `rating:e` (explicit);
  lists `rating:g,s`; `-rating:e`; `is:sfw` (= g/s) [LIVE].
- Sort: `order:score`, `order:rank` (hot), `order:favcount`, `order:id` (oldest first), `order:random`, default newest [LIVE: score, rank, random].
- Other metatags: `score:>10`, `date:2026-10-30`, `age:<1w`, `width:>1000`, `filetype:png`, `parent:none`, `status:active`,
  `pool:<id|name>`, `ordpool:<id>`, `id:1,2,3`, `user:<name>`, `limit:N` [LIVE].

### Anonymous 2-tag limit [LIVE, measured with "scenery cloud <meta>"]
Max **2 counted terms**; more -> 422. Counted: plain tags, `-tag`, `~tag` (each), `tag*`, `order:*`, `user:`, `pool:`.
**Free (not counted):** `rating:`, `-rating:`, `status:`, `limit:`, `date:`, `is:sfw`, `age:`, `score:`, `parent:`,
`filetype:`, `width:`.
Consequences for the app:
- Two user tags + `order:score` is not possible anonymously; with sorting, allow 1 tag (warn in the UI).
- **Exclusions cost slots**, so the minor-tag block list cannot go in the query - filter client-side on `tag_string`
  (section 6). Use `rating:` freely.
- A logged-in Gold+ account raises the limit (user API key, not in scope).

### Post fields to read [LIVE sample, trimmed]
```json
{"id":12491877,"created_at":"2026-11-01T01:58:40.981-05:00","score":1,"up_score":1,"down_score":0,"fav_count":1,
 "source":"https://i.pximg.net/img-original/img/2026/09/13/18/01/37/149622661_p0.jpg","md5":"a455a4db49f38c271ada8ebf02e1af96",
 "rating":"g","image_width":5016,"image_height":3541,"file_ext":"jpg","file_size":8390851,
 "tag_string":"2girls absurdres artist_logo beach ... wide_shot","tag_string_general":"2girls beach ...",
 "tag_string_character":"","tag_string_copyright":"original","tag_string_artist":"princeeugen2",
 "tag_string_meta":"absurdres commentary_request highres","parent_id":null,"has_children":false,
 "is_deleted":false,"is_banned":false,"is_pending":false,"is_flagged":false,"pixiv_id":149622661,
 "media_asset":{"id":50719403,"md5":"a455...","file_ext":"jpg","image_width":5016,"image_height":3541,"duration":null,
   "variants":[
    {"type":"180x180","url":"https://cdn.donmai.us/180x180/a4/55/a455a4db49f38c271ada8ebf02e1af96.jpg","width":180,"height":127,"file_ext":"jpg"},
    {"type":"360x360","url":"https://cdn.donmai.us/360x360/a4/55/a455a4db49f38c271ada8ebf02e1af96.jpg","width":360,"height":254,"file_ext":"jpg"},
    {"type":"720x720","url":"https://cdn.donmai.us/720x720/a4/55/a455a4db49f38c271ada8ebf02e1af96.webp","width":720,"height":508,"file_ext":"webp"},
    {"type":"sample","url":"https://cdn.donmai.us/sample/a4/55/sample-a455a4db49f38c271ada8ebf02e1af96.jpg","width":850,"height":600,"file_ext":"jpg"},
    {"type":"original","url":"https://cdn.donmai.us/original/a4/55/a455a4db49f38c271ada8ebf02e1af96.jpg","width":5016,"height":3541,"file_ext":"jpg"}]},
 "file_url":"https://cdn.donmai.us/original/a4/55/a455a4db49f38c271ada8ebf02e1af96.jpg",
 "large_file_url":"https://cdn.donmai.us/sample/a4/55/sample-a455a4db49f38c271ada8ebf02e1af96.jpg",
 "preview_file_url":"https://cdn.donmai.us/180x180/a4/55/a455a4db49f38c271ada8ebf02e1af96.jpg"}
```
Image choice:
| use | field |
|---|---|
| grid thumbnail | variant `360x360` (or `720x720` webp on big screens); fallback `preview_file_url` (180x180) |
| reader page | `large_file_url` (= `sample`, 850 px wide jpg) - or `file_url` when the user picks "original". If there is no `sample` variant, `large_file_url` == original |
| original | `file_url` |
| animation / video | `file_ext` `gif`, `mp4`, `webm`, `zip` (ugoira): `file_url` is the media; `sample` of ugoira is a webm [DOC]; `media_asset.duration` set |

**Fields can be missing** [DOC]: banned posts (`is_banned`) and posts restricted to higher account levels come back
without `file_url` / `large_file_url` / `preview_file_url` / `md5` (and variants). Skip them. Deleted posts are hidden from
searches unless `status:deleted` / `status:any`.
Timestamps are ISO-8601 with offset (`-05:00`).
URL pattern (for when only md5 + ext are known): `https://cdn.donmai.us/{original|sample|180x180|360x360|720x720}/<md5[0:2]>/<md5[2:4]>/<md5>.<ext>`
(sample files are named `sample-<md5>.jpg`).

---

## 3. Pools = manga (series pool = ordered post ids = a chapter) [LIVE]

List / search `GET /pools.json`:
| param | meaning |
|---|---|
| `search[category]` | `series` (comics, ordered) or `collection` |
| `search[name_contains]` | text in the name (spaces = `_`) [LIVE]; `search[name_matches]` exact / `*` wildcard [LIVE] |
| `search[description_matches]` | text in the description [DOC] |
| `search[post_tags_match]` | pools whose posts match a tag query - **timed out (500) in the probe**; avoid or expect errors |
| `search[is_deleted]` | `false` default |
| `search[id]` | `4606,5261` [LIVE] |
| `search[order]` | `updated_at` (default), `name`, `created_at`, `post_count` [LIVE accepted] |
| `limit`, `page` | as posts (`page=b<id>` works on all index endpoints [DOC]) |

Sample [LIVE]:
```json
{"id":4606,"name":"To_Aru_Majutsu_no_Index_-_A_Certain_Ordinary_Scenery_of_Colorful_Summer",
 "created_at":"2011-11-24T03:32:49.640-05:00","updated_at":"2011-11-24T03:49:45.453-05:00","description":"",
 "is_active":false,"is_deleted":false,"post_ids":[1042604,1042607,1042609,1042610,1042614,1042615,1042616,1042617,1042618,1042620,1042621,1042622],
 "category":"series","post_count":12}
```
- Title: `name` with `_` -> space. Description is DText (`"Original Title":http://...`, `{{bba}}` = tag link,
  `[[wiki]]`); strip or render simply. Artist: often `tag_string_artist` of the first post.
- Cover: no cover field. Use the first post id: batch for a whole page of pools with one call
  `GET /posts.json?tags=id:<id1>,<id2>,...&limit=200&only=id,rating,tag_string,preview_file_url,media_asset[variants]`
  (`id:` list = one counted tag [LIVE]; results come sorted by id desc - map back by id).
- One pool: `GET /pools/<id>.json` [LIVE].
- Chapter pages in pool order: `GET /posts.json?tags=ordpool:<id>&limit=200&page=N` returns posts in `post_ids` order [LIVE].
  (`pool:<id>` returns them id-desc, not reading order [LIVE].)
- Chapter model: one pool = one chapter (keiyoushi default "Oneshot"), or one post = one chapter (its option).
- HTML gallery alternative (keiyoushi): `GET /pools/gallery?search[category]=series&page=N&search[name_contains]=q`,
  rows `article.post-preview`, link `.post-preview-link`, title `div.text-center`, cover `source[srcset]` last entry,
  next page `a.paginator-next` [DOC].
- Pool autocomplete: `GET /autocomplete.json?search[query]=touhou&search[type]=pool&limit=10` -> `[{"id":26125,"post_count":17203,...}]` [LIVE].

---

## 4. Tags, autocomplete, artists [LIVE]

Autocomplete (use this in the search box):
`GET /autocomplete.json?search[query]=scen&search[type]=tag_query&limit=10`
```json
[{"type":"tag-word","label":"scenery","value":"scenery","category":0,"post_count":76407,
  "tag":{"id":395223,"name":"scenery","post_count":76407,"category":0,"is_deprecated":false,"words":["scenery"]}}, ...]
```
Insert `value`. `type` can also be `tag-alias` / `tag-other-name` / `tag-abbreviation` / `metatag` (show `label`, insert `value`) [DOC].
Cached by the server (`cache-control: max-age=600, public`) [LIVE].
`search[type]=artist` -> `[{"type":"tag","label":"dairi","value":"dairi","category":1}, ...]` [LIVE].

Tag search: `GET /tags.json?search[name_matches]=scen*&search[order]=count&limit=20`
(`search[category]=1` = artists; `search[hide_empty]=yes` [DOC]) [LIVE].
Tag categories: `0 general, 1 artist, 3 copyright, 4 character, 5 meta`.

Artists: `GET /artists.json?search[name]=dairi` (exact) or `search[any_name_matches]=kaoru` (name / other names, wildcards) [LIVE]:
```json
[{"id":87540,"name":"dairi","other_names":["はるか","ダイリ","dairi155", ...],"group_name":"","is_banned":false,
  "urls":[{"url":"https://x.com/dairi0610","is_active":true}, ...]}]
```
(`urls` only when asked via `only=...,urls`.) An artist "manga" = `posts.json?tags=<artist tag>` (+ `order:` uses the 2nd slot).
Related tags: `GET /related_tag.json?query=scenery&limit=10` -> object with keys `query`, `post_count`, `tag`, `related_tags` (list of `{"tag":{...}, "cosine_similarity":..., ...}`),
`wiki_page_tags` [LIVE keys].

---

## 5. Rate limits
- Observed headers only on `explore/posts/popular` (burst 50, then 1/min, per IP /64 and per session) [LIVE].
- Danbooru documents read limits of about 10 requests/s per IP [DOC]; keiyoushi uses 2 req/s. Use **<= 2 req/s**
  and back off on 429.
- `x-runtime` is up to ~1.7 s for `order:score` queries [LIVE]; set read timeout >= 30 s.
- Anonymous: pages up to 1000 (`page=b<id>` avoids it).

---

## 6. Content filter (hide by default, client-side on `tag_string`)
Because exclusions use the 2-tag budget, filter every post in the app: hide when `tag_string` contains any of
`loli`, `shota`, `toddlercon`, `lolicon`, `shotacon`, `child`, `female_child`, `male_child`, `oppai_loli`,
`aged_down`, `kindergarten_uniform`, `randoseru`, `toddler`.
For pools: hide the pool when its name contains `loli` / `shota`, and hide individual pages whose post matches.
List assembled from Danbooru's known tag set, not looked up live (no minor-related searches were made). The live probe
only ever searched `rating:g` + neutral tags.

---

## 7. Kotlin outline
- OkHttp client with an interceptor: `User-Agent: MangaCracker/1.0` for `danbooru.donmai.us` and `cdn.donmai.us`,
  no cookies to the CDN; same UA in the image loader. 2 req/s limiter.
- DTOs (`@Serializable`, `ignoreUnknownKeys = true`, every field nullable): `Post`, `MediaAsset(variants: List<Variant>)`,
  `Pool(postIds: List<Long>)`, `AutocompleteItem`, `Tag`, `Artist`, `ApiError(success, error, message)`.
- `browsePosts(tags, cursor)` -> `posts.json?tags=..&limit=100&page=b<cursor>`; count counted terms before sending (max 2).
- `browsePools(q, order, page)`; cover batch via `id:` list; `poolPages(id)` via `ordpool:`.
- Skip posts with null `file_url`; apply the block list.
