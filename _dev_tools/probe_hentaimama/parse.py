"""hentaimama.io parser proof (spec: _dev_tools/specs/hentaimama.md). Mirrors HentaiMamaSource.kt.

Live, ~1 request per 1.1 s, about 12 requests. Proves: the latest-episodes list, popular (series cards), a search,
one episode -> mirror -> stream URL answering 206 video/mp4 to a 64 KB Range read, the series page's episodes,
and 3 posters that come back as images. Video reads are capped at 64 KB.
"""
import base64, html, json, re, sys, time
import requests
from bs4 import BeautifulSoup

BASE = 'https://hentaimama.io'
UA = ('Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) '
      'Chrome/130.0.0.0 Mobile Safari/537.36')
S = requests.Session()
S.headers.update({'User-Agent': UA, 'Accept-Language': 'en-GB,en;q=0.9'})
_last = [0.0]

# minors: the site's genre is spelled "shoutacon"; series seen there on 2026-11-02 (the app re-reads the genre)
HIDDEN_GENRES = {'shoutacon', 'shotacon', 'shota', 'loli', 'lolicon'}
WORDS = re.compile(r'\b(loli|lolicon|shota|shotacon|shoutacon|toddler|child|children|kids?|elementary)\b', re.I)
blocked_slugs, blocked_names = set(), set()


def norm(s):
    return re.sub(r'[^a-z0-9]+', '', s.lower())


def get(url, **kw):
    wait = _last[0] + 1.1 - time.time()
    if wait > 0:
        time.sleep(wait)
    _last[0] = time.time()
    r = S.get(url, timeout=30, **kw)
    print(f'  GET {r.status_code} {url[:110]}')
    return r


def post(url, data, ref):
    wait = _last[0] + 1.1 - time.time()
    if wait > 0:
        time.sleep(wait)
    _last[0] = time.time()
    r = S.post(url, data=data, headers={'Referer': ref, 'X-Requested-With': 'XMLHttpRequest'}, timeout=30)
    print(f'  POST {r.status_code} {url} {data}')
    return r


def soup(url, **kw):
    r = get(url, **kw)
    r.raise_for_status()
    return BeautifulSoup(r.text, 'lxml')


def has_next(doc):
    return doc.select_one('a.dt-pg-next[href]') is not None


def slug_of(href):
    return href.rstrip('/').split('/')[-1]


def safety_check():
    """All series of the minors genre (slugs + names), at most 6 pages."""
    for p in range(1, 7):
        doc = soup(f'{BASE}/genre/shoutacon/' + (f'page/{p}/' if p > 1 else ''))
        for c in doc.select('article.series-card'):
            a = c.select_one('.sc-title a')
            blocked_slugs.add(slug_of(a['href']))
            blocked_names.add(norm(a.get_text(strip=True)))
        if not has_next(doc):
            break
    print(f'  blocked series: {len(blocked_slugs)}')


def ep_blocked(slug, series):
    if norm(series) in blocked_names or WORDS.search(series):
        return True
    return any(slug == s or slug.startswith(s + '-episode') for s in blocked_slugs)


def episode_item(a):
    """article.item.se (latest / episodes archive)."""
    link = a.select_one('.season_m a[href]') or a.select_one('a[href*="/episodes/"]')
    url = link['href']
    slug = slug_of(url)
    series = a.select_one('.season_m .b').get_text(strip=True) if a.select_one('.season_m .b') else ''
    ep_text = a.select_one('.season_m .c').get_text(strip=True) if a.select_one('.season_m .c') else ''
    ep = re.search(r'(\d+(?:\.\d+)?)', ep_text)
    img = a.select_one('.poster img')
    cover = img.get('src', '') if img else ''
    alts = [u.split()[0] for u in (img.get('srcset', '') if img else '').split(',') if u.strip()]
    flags = [t.get_text(strip=True) for t in a.select('.card-tags .tag')]
    lang = 'RAW' if 'RAW' in flags else ('EN' if 'SUB' in flags else '')
    if ep_blocked(slug, series):
        return None
    return dict(id=slug, title=f'{series} Episode {ep.group(1)}' if ep else (img.get('alt', '') if img else series),
                url=url, series=series, episode=ep.group(1) if ep else '', cover=cover,
                alts=[u for u in alts if u != cover][::-1], lang=lang,
                tags=(['uncensored'] if 'UNC' in flags else []), post_id=a.get('id', '').removeprefix('post-'))


def series_card(c):
    """article.series-card (popular, genre, search): one series."""
    a = c.select_one('.sc-title a')
    slug = slug_of(a['href'])
    genres = [slug_of(g['href']) for g in c.select('.sc-genres a[href*="/genre/"]')]
    name = a.get_text(strip=True)
    if any(g in HIDDEN_GENRES for g in genres):
        blocked_slugs.add(slug)
        blocked_names.add(norm(name))
        return None
    if slug in blocked_slugs or WORDS.search(name):
        return None
    img = c.select_one('img')
    views = next((t.get_text() for t in c.select('.sc-meta .sc-tag') if 'views' in t.get_text()), '')
    return dict(id='tv:' + slug, title=name, url=a['href'], series=name, cover=img.get('src', '') if img else '',
                views=views.replace(' views', '').upper(), tags=genres + ['series:' + slug])


def latest(page):
    doc = soup(f'{BASE}/episodes/' + (f'page/{page}/' if page > 1 else ''))
    items = [i for i in (episode_item(a) for a in doc.select('article.item.se')) if i]
    return items, has_next(doc)


def popular(page):
    doc = soup(f'{BASE}/hentai-series/' + (f'page/{page}/' if page > 1 else '') + '?filter=weekly')
    return [i for i in (series_card(c) for c in doc.select('article.series-card')) if i], has_next(doc)


def search(text, page):
    doc = soup(f'{BASE}/' + (f'page/{page}/' if page > 1 else '') + '?s=' + requests.utils.quote(text))
    return [i for i in (series_card(c) for c in doc.select('article.series-card')) if i], has_next(doc)


def series_page(url):
    doc = soup(url)
    genres = [slug_of(g['href']) for g in doc.select('.dsc-genres a[href*="/genre/"]')]
    name = doc.select_one('h1.dsc-title').get_text(strip=True)
    eps = []
    for a in doc.select('a.dt-se-item[href]'):
        n = re.search(r'(\d+(?:\.\d+)?)', a.select_one('.dt-se-title').get_text())
        img = a.select_one('img')
        eps.append(dict(id=slug_of(a['href']), url=a['href'], series=name, episode=n.group(1) if n else '',
                        title=f'{name} Episode {n.group(1)}' if n else name, cover=img.get('src', '') if img else ''))
    return name, genres, eps


def embed_sources(page_html):
    """jwplayer setup({sources: [...]}) or newjav's `file: "..."`."""
    m = re.search(r'sources:\s*\[(.*?)\]', page_html, re.S)
    out = []
    for o in re.findall(r'\{[^}]*\}', m.group(1) if m else ''):
        f = re.search(r'"?file"?\s*:\s*"([^"]+)"', o)  # JSON keys on the site's embeds, bare keys on newjav.php
        t = re.search(r'"?type"?\s*:\s*"(\w+)"', o)
        lab = re.search(r'"?label"?\s*:\s*"([^"]*)"', o)
        if f:
            u = f.group(1).replace('\\/', '/')
            out.append((u, 'hls' if (t and t.group(1) == 'hls') or '.m3u8' in u else 'mp4', lab.group(1) if lab else ''))
    return out


PREFER = ['r2-mp4', 'rtmp', 'hls-mp4', 'hls', 'newjav']


def details(ep_url):
    doc = soup(ep_url)
    raw = str(doc)
    post_id = (re.search(r"get_player_contents',\s*a:\s*'(\d+)'", raw) or re.search(r'\?p=(\d+)', doc.select_one('link[rel=shortlink]')['href'])).group(1)
    opts = [int(a['href'].removeprefix('#option-')) for a in doc.select('.dt-mi-tabs a[href^="#option-"]')]
    genres = [slug_of(g['href']) for g in doc.select('.sgeneros a[href*="/genre/"]')]
    series_a = doc.select_one('.episode-series-img a[href*="/tvshows/"]') or doc.select_one('#pag_episodes a[href*="/tvshows/"]')
    title = doc.select_one('h1.epih1').get_text(strip=True)
    desc = doc.select_one('#info .episode-desc')
    print(f'  post {post_id}, mirrors (tab order) {opts}, title {title!r}, genres {genres}')
    if any(g in HIDDEN_GENRES for g in genres):
        raise SystemExit('hidden')
    mirrors = []
    for i in opts:
        r = post(f'{BASE}/wp-admin/admin-ajax.php', {'action': 'get_player_contents', 'a': post_id, 'i': i}, ep_url)
        arr = json.loads(r.text)
        frame = arr[i - 1] if i - 1 < len(arr) else ''
        src = re.search(r'src="([^"]+)"', frame)
        if not src:
            continue
        u = html.unescape(src.group(1).replace('\\/', '/'))
        kind = (re.search(r'dt_embed=([\w-]+)', u) or re.search(r'(newjav)', u)).group(1)
        mirrors.append((PREFER.index(kind) if kind in PREFER else 9, kind, u))
        if kind == 'r2-mp4':
            break  # the best one: stop asking
    mirrors.sort()
    for _, kind, u in mirrors:
        p = re.search(r'[?&]p=([^&]+)', u).group(1)
        print(f'  mirror {kind}: p = {base64.b64decode(p + "=" * (-len(p) % 4)).decode(errors="replace")}')
        page = get(u, headers={'Referer': ep_url}).text
        srcs = embed_sources(page)
        if srcs:
            return dict(post_id=post_id, title=title, genres=genres, series_url=series_a['href'] if series_a else '',
                        series=series_a.get('title', '') if series_a else '', mirror=kind, sources=srcs,
                        desc=desc.get_text(' ', strip=True)[:120] if desc else '')
    raise SystemExit('no stream')


def range_check(url, ref=None):
    h = {'Range': 'bytes=0-65535'}
    if ref:
        h['Referer'] = ref
    r = get(url, headers=h, stream=True)
    data = b''
    for chunk in r.iter_content(8192):
        data += chunk
        if len(data) >= 65536:
            break
    r.close()
    return r.status_code, r.headers.get('content-type'), r.headers.get('content-range'), data[:12]


def main():
    print('== safety: minors genre')
    safety_check()
    print('== latest page 1')
    items, nxt = latest(1)
    print(f'  {len(items)} items, next={nxt}')
    for i in items[:4]:
        print('  ', {k: i[k] for k in ('id', 'title', 'series', 'episode', 'lang', 'cover')})
    print('== popular (series, this week) page 1')
    pop, nxt = popular(1)
    print(f'  {len(pop)} series, next={nxt}: ', [(p['title'][:30], p['views']) for p in pop[:5]])
    print('== search "sister" page 1')
    res, nxt = search('sister', 1)
    print(f'  {len(res)} series, next={nxt}: ', [r['title'][:30] for r in res[:6]])
    print('== details of', items[0]['url'])
    d = details(items[0]['url'])
    print('  ', {k: d[k] for k in ('post_id', 'title', 'series', 'series_url', 'mirror', 'desc')})
    for s in d['sources']:
        print('   source', s)
    status, ctype, crange, head = range_check(d['sources'][0][0])
    print(f'  RANGE -> {status} {ctype} {crange} {head}')
    assert status in (200, 206) and ('video' in (ctype or '') or 'mpegurl' in (ctype or '') or head.startswith(b'#EXTM3U'))
    print('== series page', d['series_url'])
    name, genres, eps = series_page(d['series_url'])
    print(f'  {name}: {len(eps)} episodes, genres {genres[:8]}...')
    for e in eps:
        print('   ', e['episode'], e['id'], e['cover'][-50:])
    print('== 3 posters')
    for u in [items[1]['cover'], pop[0]['cover'], eps[0]['cover']]:
        status, ctype, crange, head = range_check(u)
        ok = status in (200, 206) and (ctype or '').startswith('image/')
        print(f'   {status} {ctype} {head[:4]}{head[8:12]} {"OK" if ok else "BAD"} {u[-60:]}')
    print('done')


if __name__ == '__main__':
    sys.exit(main())
