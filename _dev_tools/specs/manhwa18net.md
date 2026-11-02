# manhwa18.net source spec (tested 2026-11-02)

Tested live on 2026-11-02 from England with Python `requests`, anonymous, about 1 request per 1.1 s. Probe scripts in
`_dev_tools\probe_manhwa18\` (`probe.py` single GET / capped picture GET, `inertia.py` dumps the page JSON,
`net_lists*.py` list / search / filter checks, `imgtest.py` pictures with / without Referer, `parse.py net` = the
parser the app copies, `final_check.py`). Saved replies in `probe_manhwa18\out\` (`requests.log` lists every call).
Kotlin: `sources/Manhwa18NetSource.kt` (object `Manhwa18NetSource`).

Tags: **[LIVE nnn]** = seen in this probe with that HTTP status; **[GUESS]** = not tested.

## 0. The short version

* **Laravel + Inertia.js (React).** Every HTML page carries ALL its data as JSON in one attribute:
  `<div id="app" data-page="{&quot;component&quot;:&quot;MangaList&quot;,&quot;props&quot;:{...}}">` [LIVE 200].
  Jsoup's `attr("data-page")` un-escapes it; parse with `Http.json` and read `props`. No HTML selectors needed.
  (Inertia also answers JSON to `X-Inertia: true` + `X-Inertia-Version: <version>`, but the version changes on every
  deploy and a stale one answers 409, so the app reads the HTML.)
* Behind Cloudflare, no challenge, not blocked in the UK (no 451 / Online Safety Act page) [LIVE 200, `cf-ray …-LHR`].
* Pictures need **no Referer** [LIVE 206 with and without]. The app still registers `Referer: https://manhwa18.net/`.
* Some titles are **videos** (genres "Animation" id 165, "Hentai Anime" id 166): their chapter has one poster
  `…/video-posters/….jpg` + `linkedVideo`. The app drops them from lists and rejects them in search.
* No minors-related genre exists in the 73-genre list. Tags (a second taxonomy, e.g. "Older Woman") are only seen on
  details pages; `Safety.blocked()` checks genres + tags + characters there.

## 1. Lists (`props.paginate`, a Laravel paginator) [LIVE 200]

| feed | URL | notes |
|---|---|---|
| Popular | `/manga-list?sort=top&page=N` | all-time views |
| Latest | `/manga-list?sort=update&page=N` (= default) | latest chapter update |
| Genre | `/genre/<slug>?sort=…&page=N` | same props, items keep `genres` |
| Tag | `/tag/<slug>?sort=…&page=N` [LIVE 200, "older-woman": 66] | |
| Status | add `&Ongoing=1` / `&completed=1` / `&Onhold=1` (Dropped) to manga-list / genre [LIVE 200] | **ignored by `/tim-kiem`** [LIVE] |

`sort` values (from the site's JS): `update` (Latest Update), `new` (Newest), `top` (Most Views), `like` (Most Liked),
`az`, `za`. 24 per page. Paging: `current_page < last_page` (also `next_page_url` non-null).

Paginator item (trimmed, real):
```json
{"id":2487,"name":"Taming My Stepsister","other_name":"(새누나 길들이기)","slug":"taming-my-stepsister",
 "cover_url":"https://min.manhwa18.net/manhwa18/vnwa/images/20260328143942_829804_94SNyUgT.jpg",
 "cover_image":{"src":"…","source_width":800,"source_height":1120,"variants":[
    {"width":160,"url":"https://min.manhwa18.net/chapters/cover-variants/6f/6fe6…/w160-q75.webp"},
    {"width":320,"url":"…/w320-q75.webp"},{"width":640,"url":"…/w640-q75.webp"}]},
 "thumb_url":"…","status_id":0,"view":1470154,"rating_average":4.73,"updated_at":"2026-11-02T04:12:58.000000Z",
 "latest_chapter":{"id":98941,"name":"Chapter 35","slug":"chapter-35","manga_id":2487},
 "genres":[{"id":4,"name":"Adult","slug":"adult"}, …]}
```
* id = `slug`; title = `name`; cover = first variant with width ≥ 300 (the w320 webp), else `cover_url`
  (sometimes relative-free full URLs on `manhwa18.net/storage/images/raw/…`); subtitle = `latest_chapter.name`.
* `genres` is present on manga-list / genre / tag items, **absent on `/tim-kiem` items**.
* `status_id`: 0 Ongoing, 1 Completed, 2 Dropped (the site's own map `Ime` in its JS).

## 2. Search: `/tim-kiem` (`props.mangas`, 18 per page) [LIVE 200]

`/tim-kiem?q=<text>&accept_genres=4,33&reject_genres=165,166&sort=top&page=N`

* `accept_genres` = genre **ids**, comma list, **OR** (33 → 47, 129 → 82, 33,129 → 129) [LIVE].
* `reject_genres` works (Adult 1616 → Adult minus Manhwa 179) [LIVE].
* `props.genres` = the full genre list `[{id,name,slug}]` (73 on 2026-11-02) — the app's `tags()`.
* `artist=` param exists in the JS but matched nothing [LIVE 0]. Artist pages are `/tac-gia/<artist slug>?page=N`
  (component Search, `props.mangas`) [LIVE 200, "bangkki": 1 title].
* App logic: text → `/tim-kiem` (includes OR'd); no text + genres → `/genre/<first>` filtered in the app for the
  other includes / excludes (AND); no text + only excludes → `/tim-kiem?reject_genres=`; a `tag:` include → `/tag/<slug>`.

Genres (id name): 59 Action, 4 Adult, 152 Adventure, 122 AI Art, 154 Animal Characteristics, 165 Animation, 65 Art,
153 Based on Another Work, 66 Borderline H, 82 Cohabitation, 141 Collection of Stories, 129 Comedy, 67 Coworkers,
142 Crime, 161 Delinquents, 146 Demons, 9 Doujinshi, 68 Drama, 164 Ecchi, 123 Explicit Sex, 125 Fantasy, 130 Fetish,
71 Full Color, 148 Ghosts, 143 GL, 156 Gyaru, 17 Harem, 166 Hentai Anime, 134 Historical, 162 Horror, 133 Incest,
90 Isekai, 120 Japanese Webtoons, 117 M18Scan, 151 Magic, 102 Magical Girl, 24 Manga, 26 Manhwa, 28 Mature,
138 Medical, 155 Monster Girls, 157 Monsters, 147 Mystery, 33 NTR, 92 Nudity, 131 Psychological, 63 Raw,
126 Reincarnation, 163 Revenge, 135 Reverse Harem, 36 Romance, 159 Salaryman, 127 School Life, 136 Sci Fi, 160 Seinen,
124 Sexual Abuse, 132 Sexual Content, 139 Siblings, 81 Slice of Life, 84 Smut, 140 Sports,
145 Summoned Into Another World, 128 Supernatural, 158 Survival, 150 Thriller, 149 Time Travel, 64 Uncensore,
77 Uncensored, 78 Violence, 57 Webtoon, 83 Webtoons, 137 Work Life, 144 Yuri.

## 3. Details + chapters: `/manga/<slug>` [LIVE 200]

`props.manga` = the item fields plus `pilot` (HTML description), `note`, `genres[]`, `tags[] {name,slug}`,
`artists[] {name,slug}`, `characters[]`, `translators[]`, `owner` (uploader, not an author). No author field.
`other_name` = alt titles joined by " / " or ", " (sometimes in parentheses).

`props.chapters` = **every chapter, newest first, inline** (no AJAX; 107 on a 106-chapter title) [LIVE]:
```json
{"id":98967,"name":"Chapter 106","slug":"chapter-106","order":107,"views":533,"created_at":"2026-11-02T09:34:07.000000Z"}
```
Old uploads use names like `"chap 02"` and slugs like `chap-02-834`; videos `"EP6 - EPISODE 6"`. Number = regex on
the name (`chapter|chap|ch|ep|episode` + number), fallback `order`. Date = ISO `created_at`.

## 4. Chapter pages: `/manga/<slug>/<chapter slug>` [LIVE 200]

`props.chapterImages = [{"src":"https://min.manhwa18.net/manhwa18/vnwa/images/20261026133257_776481_qN6WjEaU.jpg",
"variants":[]}, …]` (full size, in order). Same URLs again in `props.chapterContent` as `<img src=…>` (fallback).
Also: `listChapters`, `prevChapterUrl`, `nextChapterUrl`, `related`, `linkedVideo`.

## 5. Pictures [LIVE 206, Range 0-65535]

| what | host | Referer needed |
|---|---|---|
| cover variants (webp) | `min.manhwa18.net/chapters/cover-variants/…` | no |
| covers (jpg) | `min.manhwa18.net/manhwa18/vnwa/images/…`, `manhwa18.net/storage/images/raw/…` | no |
| pages (jpg) | `min.manhwa18.net/manhwa18/vnwa/(images|chapters/<id>)/…` | no |

## 6. App Http rules

`Http.referers["manhwa18.net"]`, rate limits 400 ms on `manhwa18.net/manga` (also covers `/manga-list`),
`manhwa18.net/tim-kiem`, `manhwa18.net/genre` (path rules, so `min.manhwa18.net` pictures are not slowed).
