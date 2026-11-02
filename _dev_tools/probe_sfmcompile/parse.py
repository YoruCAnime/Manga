"""sfmcompile.club proof for the app (spec: _dev_tools/specs/sfmcompile.md).

Latest through the WordPress REST API (+ one tag-name lookup), Popular from the HTML list (?filter-by=popular),
a search, one post -> MP4 -> a 64 KB Range read, 3 thumbnails. About 1 request per 1.1 s, ~10 requests in all.
"""
import os, re, time, html as htmllib
import requests
from bs4 import BeautifulSoup

BASE = 'https://sfmcompile.club'
API = BASE + '/wp-json/wp/v2/'
FIELDS = 'id,date,link,title,content,categories,tags,yoast_head_json.og_image'
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out')
os.makedirs(OUT, exist_ok=True)
UA = ('Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) '
      'Chrome/130.0.0.0 Mobile Safari/537.36')
S = requests.Session()
S.headers.update({'User-Agent': UA, 'Accept-Language': 'en-GB,en;q=0.9'})
TAGS = {}  # id -> slug, filled by lookups


def get(url, **kw):
    time.sleep(1.1)
    r = S.get(url, timeout=30, **kw)
    with open(os.path.join(OUT, 'requests.log'), 'a', encoding='utf-8') as f:
        f.write(time.strftime('%H:%M:%S ') + f'PARSE {r.status_code} {url}\n')
    return r


def fix_img(u):
    """Old posts point at a dead offload host: cdn.sfmcompile.club/.../YYYY/MM/<8 digits>/name -> the site itself."""
    m = re.match(r'https?://cdn\.sfmcompile\.club(/wp-content/uploads/\d{4}/\d{2}/)\d+/(.+)$', u)
    return BASE + m.group(1) + m.group(2) if m else u


def sized(u, size='608x342'):
    return re.sub(r'(\.\w+)$', '-' + size + r'\1', u)


def tag_names(ids):
    need = [i for i in ids if i not in TAGS]
    if need:
        r = get(API + 'tags?per_page=100&_fields=id,slug&include=' + ','.join(map(str, need)))
        for t in r.json():
            TAGS[t['id']] = t['slug']
    return [TAGS.get(i, str(i)) for i in ids]


def api_item(p):
    c = p['content']['rendered']
    mp4 = re.search(r'src="([^"]+\.(?:mp4|webm))"', c)
    og = ((p.get('yoast_head_json') or {}).get('og_image') or [{}])[0].get('url', '')
    full = fix_img(og)
    artist = re.search(r'Artist:\s*<a[^>]*>([^<]+)</a>', c)
    return {'id': p['id'], 'title': htmllib.unescape(p['title']['rendered']), 'url': p['link'],
            'video': mp4.group(1) if mp4 else None, 'cover': sized(full) if full else '', 'alt': full,
            'cats': p['categories'], 'tags': p['tags'], 'artist': artist.group(1) if artist else ''}


def api_list(query):
    r = get(API + 'posts?per_page=24&_fields=' + FIELDS + query)
    if r.status_code == 400:
        return [], False
    pages = int(r.headers.get('x-wp-totalpages', '1'))
    page = int(re.search(r'[?&]page=(\d+)', query).group(1)) if 'page=' in query else 1
    return [api_item(p) for p in r.json()], page < pages


def html_list(url):
    soup = BeautifulSoup(get(url).text, 'lxml')
    out = []
    for a in soup.select('article.entry-tpl-stream'):
        cls = a.get('class', [])
        t = a.select_one('h2.entry-title a')
        v = a.select_one('video source[src]')
        vid = a.select_one('video')
        views = a.select_one('.entry-views strong')
        poster = vid.get('data-poster-image', '') if vid else ''
        if not poster:  # older posts: a lazy picture instead of the inline video (the MP4 comes from the API then)
            img = a.select_one('.entry-featured-media img[data-src]')
            poster = img['data-src'] if img else ''
        out.append({'id': a['id'].removeprefix('post-'), 'title': t.get_text(strip=True), 'url': t['href'],
                    'video': v['src'] if v else None, 'cover': fix_img(poster),
                    'views': views.get_text(strip=True) if views else '',
                    'cats': [c.removeprefix('category-') for c in cls if c.startswith('category-')],
                    'tags': [c.removeprefix('tag-') for c in cls if c.startswith('tag-')]})
    nxt = soup.select_one('a.next[href]') or soup.select_one('link[rel=next]')
    return out, nxt['href'] if nxt else None


def range_read(url):
    time.sleep(1.1)
    r = S.get(url, headers={'Range': 'bytes=0-65535'}, timeout=30, stream=True)
    data = b''
    for c in r.iter_content(8192):
        data += c
        if len(data) >= 65536:
            break
    r.close()
    return r.status_code, r.headers.get('content-type'), r.headers.get('content-range'), data[:65536]


def main():
    lst, more = api_list('&page=1')
    print('LATEST (API):', len(lst), 'items, more =', more)
    for it in lst[:3]:
        print('  ', it['id'], it['title'], it['video'], it['cover'], 'tags', tag_names(it['tags']))
    pop, nxt = html_list(BASE + '/page/2/?filter-by=popular')
    print('POPULAR p2 (HTML):', len(pop), 'items, next =', nxt)
    for it in pop[:3]:
        print('  ', it)
    found, more = api_list('&search=' + requests.utils.quote('tifa') + '&tags%5Bterms%5D=36')
    print('SEARCH "tifa" + tag cowgirl (API):', len(found), 'more =', more, [x['title'] for x in found[:3]])
    # one post: its own API record, then the MP4
    one = lst[0]
    r = get(API + f'posts/{one["id"]}?_fields=' + FIELDS)
    d = api_item(r.json())
    print('POST', d['title'], d['video'], 'artist', d['artist'], 'cats', d['cats'])
    st, ct, cr, data = range_read(d['video'])
    print('  mp4', st, ct, cr, 'ftyp ok' if data[4:8] == b'ftyp' else data[:12])
    rel, more = api_list(f'&categories={d["cats"][0]}&exclude={d["id"]}')
    print('  related (same category):', len(rel), [x['title'] for x in rel[:3]])
    old, _ = api_list('&page=1600')
    for it in [lst[1], pop[0], old[0]]:
        for u in [it['cover'], it.get('alt')]:
            if not u:
                continue
            st, ct, cr, data = range_read(u)
            print('  thumb', st, ct, len(data), u, 'JPEG' if data[:2] == b'\xff\xd8' else data[:8])
            if st in (200, 206):
                break


if __name__ == '__main__':
    main()
