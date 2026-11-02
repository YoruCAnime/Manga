# sfmcompile.club video source spec (tested 2026-11-02)

Tested live on 2026-11-02 from England with Python `requests`, anonymous, no login, about 1 request per 1.1 s.
Probe scripts in `_dev_tools\probe_sfmcompile\`: `probe.py` (single GET / capped 64 KB Range GET), `api.py` (REST
calls with paging headers), `mp4head.py` (duration from a saved MP4 head), `parse.py` (latest via API, popular via
HTML, search, post -> MP4, thumbnails: the parser the app copies). Saved replies in `probe_sfmcompile\out\`
(`requests.log`). No video was downloaded: 64 KB heads only.

Tags: **[LIVE nnn]** = seen in this probe with that HTTP status; **[GUESS]** = not tested.

## 0. The short version

* **WordPress (theme Bimber + Snax) behind Sucuri CloudProxy, REST API open** (`/wp-json/wp/v2/`) [LIVE 200].
  No challenge, no 451, no age wall from a UK IP.
* **Stream = one direct MP4 per post** in `/wp-content/uploads/YYYY/MM/<Name>.mp4` on the site itself, Range works,
  **no Referer / cookie needed** [LIVE 206 video/mp4]. Clips are short (a few seconds to minutes; one tested: 4.2 s,
  720x1280, 433 KB). Some old MP4s have the moov box at the end (still plays, ExoPlayer seeks to it).
* 40 221 posts, 1 239 tags, 321 categories (2026-11-02).
* Categories = franchise (parent 0) and character (child), e.g. Resident Evil > Ada Wong: good `series`.
* Tags = acts, length marks (`20secs`, `30secs`, `1min`, `2mins` … names "30secs+"), `sound`, and **artist names**.

## 1. REST API (latest, search, tags, related)

`GET /wp-json/wp/v2/posts?per_page=24&page=<n>&_fields=id,date,link,title,content,categories,tags,yoast_head_json.og_image`
[LIVE 200, ~35 KB for 24]. Headers `X-WP-Total`, `X-WP-TotalPages` (1676 pages of 24). Past the end: **400**
`rest_post_invalid_page_number` [LIVE 400].

| field | where |
|---|---|
| id | `id` |
| title | `title.rendered` (HTML entities: `&#8217;`) |
| page | `link` (`https://sfmcompile.club/<slug>/`) |
| video | `content.rendered`: `<video controls src="…/wp-content/uploads/2026/11/<Name>.mp4">` (block editor) or `<source type="video/mp4" src="…">`. Regex `src="([^"]+\.(?:mp4|webm))"`. 72/72 sampled posts (2020-2026) had one |
| artist | `content.rendered`: `Artist: <a href="https://sfmcompile.club/tag/<Name>">Name</a>` |
| picture | `yoast_head_json.og_image[0].url` = the full featured image (1280x720 JPEG) |
| tags / categories | numeric ids; names via `GET /wp-json/wp/v2/tags?include=<ids>&per_page=100&_fields=id,slug,name` (one small call per list page, cache) [LIVE 200] |

Do not use `_embed=wp:term`: it works but 24 posts become 800 KB [LIVE 200].

**Pictures** [LIVE 206 image/jpeg]: the grid size is the featured image name + `-608x342` before the extension
(`…/Carla-and-Ada-rides-together-608x342.jpg`, 30-55 KB); full size as fallback. **Old posts (2020)** give
`https://cdn.sfmcompile.club/wp-content/uploads/2020/02/12163911/Name.jpg`: that host no longer resolves (DNS
failure). Rewrite to `https://sfmcompile.club/wp-content/uploads/2020/02/Name.jpg` (drop the host and the 8-digit
offload folder) [LIVE 206]; the `-608x342` size exists there too [LIVE 206].

Queries [LIVE 200]:
* text: `&search=ada%20wong` (568 hits)
* tags AND: `&tags%5Bterms%5D=36,39&tags%5Boperator%5D=AND` (176 hits); OR: `&tags=36,39`
* exclude: `&tags_exclude=52`; category: `&categories=1610` (child categories included [GUESS: WP default])
* related (the post page's "More From: <category>"): `&categories=<cat>&exclude=<id>`

Tag list: `GET /wp-json/wp/v2/tags?per_page=100&orderby=count&order=desc&_fields=id,name,slug,count` [LIVE 200]
(top: sound 28 440, POV 8 013, 30secs+ 7 284, blowjob 7 079 …). Categories the same at `/categories`
(+ `parent`): Overwatch 8 104, Final Fantasy 3 002, Resident Evil 2 305 …

## 2. HTML lists (popular)

The REST API cannot sort by views; the theme can:

| feed | URL | notes |
|---|---|---|
| **Popular (most views, all time)** | `GET /page/<n>/?filter-by=popular` (page 1 = `/?filter-by=popular`) [LIVE 200] | 6M, 4.4M … views; page 2 ~1M |
| Hot | `?filter-by=hot` [LIVE 200] | recent views [GUESS: Bimber's meaning] |
| Trending | `?filter-by=trending` [LIVE 200] | 33k-73k views, recent |
| Tag by views | `/tag/<slug>/?order=most_views` [LIVE link in the menu, not fetched] | |
| Search | `/?s=<q>`, `/page/<n>/?s=<q>` [LIVE 200] | the API search is better |

**8 posts per page** (~235 KB each). Posts = `article.entry-tpl-stream` (the 5 `entry-tpl-grid-fancy` are a side
box: skip). Next = `a.next[href]` (`…/page/3/?filter-by=popular`, keeps the filter) [LIVE].

```html
<article id="post-374076" class="entry-tpl-stream post-374076 post … category-marvel tag-20secs tag-doggy-style tag-sound tag-waos snax_format-video">
  <h2 class="… entry-title"><a href="https://sfmcompile.club/chaos-weaver-scarlet-witch-in-doggy/">Chaos Weaver Scarlet Witch in doggy</a></h2>
  <p class="entry-meta entry-stats"><span class="entry-shares"><strong>5</strong>…</span><span class="entry-views"><strong>47</strong><span> Views</span></span></p>
  <div class="entry-featured-media"><div class="wp-video"><video class="wp-video-shortcode" … data-poster-image="…-608x342.jpg">
     <source type="video/mp4" src="…/Chaos-Weaver-Scarlet-Witch-in-doggy.mp4" /></video></div></div>
```
Older posts show a lazy picture instead (`.entry-featured-media img[data-src]` = `…-608x342.jpg`) and **no video**:
open the post through the API for the MP4. Tags / categories = the `tag-*` / `category-*` classes (slugs).
Views: "47", "33.1k", "1.1M".

## 3. Post page

`GET /<slug>/` [LIVE 200, ~215 KB]: `<video autoplay loop controls src="….mp4">`, tag links `a.entry-tag`, "More
From: <category>" related grid. The API post (`/wp-json/wp/v2/posts/<id>?_fields=…` [LIVE 200]) has the same and is
small, so the app uses the API.

## 4. Minors

`tags?search=loli|shota|teen|child|kid` and `categories?search=shota|young|child|kid|teen` = nothing [LIVE 200].
`tags?search=young` = only the artist "Youngiesed"; `categories?search=loli` = only "Hololive". No minors tags on this
site; the app's VideoSafety word / tag filter still runs on every item.

## 5. App notes

* `VideoStream(mp4, "mp4")`, no headers: the player's default `Http.imageClient` works.
* `series` = the post's most specific category name (character, else franchise). `details().episodes` = this post +
  the latest 24 of the same category; `series()` = same.
* Search: API (text + tags AND + tags_exclude); a tag id prefixed `c` is a category.
