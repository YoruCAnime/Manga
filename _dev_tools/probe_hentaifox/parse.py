"""hentaifox: the same steps the Kotlin source takes, checked live. Read-only, ~1 req/s.
lists (latest p1/p2/p3, popular, tag search, artist) -> details -> one "Gallery" chapter -> page image URLs from g_th,
then 3 covers + 3 pages fetched (64 KB max) and their magic bytes checked.
Minors guard: the search carries -slug exclusions, and every list drops thumbs whose data-tags hold a minors id."""
import json, re
from urllib.parse import quote_plus
from bs4 import BeautifulSoup
from fetch import get

B = 'https://hentaifox.com'
MINOR_IDS = {'8', '11', '45', '111', '268', '3321', '3322', '715', '1463', '1472'}
GUARD = '-lolicon -shotacon -oppai-loli -low-lolicon -low-shotacon -toddlercon -kodomo-doushi -kodomo-only -loli -shota'
MINOR_SLUGS = {'lolicon', 'shotacon', 'oppai-loli', 'low-lolicon', 'low-shotacon', 'toddlercon', 'kodomo-doushi', 'kodomo-only', 'loli', 'shota'}
EXT = {'j': 'jpg', 'p': 'png', 'b': 'bmp', 'g': 'gif', 'w': 'webp'}
MAGIC = {b'\xff\xd8\xff': 'jpeg', b'\x89PNG': 'png', b'RIFF': 'webp', b'GIF8': 'gif'}


def listing(path):
    r = get(B + path)
    s = BeautifulSoup(r.text, 'lxml')
    out, dropped = [], 0
    for t in s.select('div.thumb'):
        a = t.select_one('h2.g_title a')
        if not a:
            continue          # parody / character cards on the home page
        tags = (t.get('data-tags') or '').split()
        if set(tags) & MINOR_IDS:
            dropped += 1
            continue
        gid = re.search(r'/gallery/(\d+)/', a['href']).group(1)
        img = t.select_one('.inner_thumb img')
        out.append({'id': gid, 'title': a.get_text(strip=True), 'cover': img.get('data-src') or img.get('src'),
                    'cat': (t.select_one('.g_cat') or t).get_text(strip=True), 'tags': ['hf:' + x for x in tags]})
    nxt = any(li.get_text(strip=True) == 'Next' and 'disabled' not in (li.get('class') or []) for li in s.select('ul.pagination li'))
    return out, nxt, dropped, r.status_code


def home_path(page):
    return '/' if page == 1 else '/page/2/' if page == 2 else f'/pag/{page}/'


def search_path(words, page, sort=''):
    q = quote_plus((words + ' ' + GUARD).strip())
    return f'/search/?q={q}' + (f'&sort={sort}' if sort else '') + (f'&page={page}' if page > 1 else '')


def magic(data):
    return next((v for k, v in MAGIC.items() if data.startswith(k)), None)


def details(gid):
    r = get(f'{B}/gallery/{gid}/')
    s = BeautifulSoup(r.text, 'lxml')
    info = s.select_one('div.gallery_right div.info')
    groups = {}
    for ul in info.select('ul'):
        cls = ' '.join(ul.get('class') or [])
        if cls == 'g_buttons':
            continue
        groups[cls] = [(a['href'].strip('/').split('/')[-1], a.find(string=True, recursive=False).strip(),
                        int(a.select_one('.t_badge').get_text(strip=True) or 0)) for a in ul.select('a.tag_btn')]
    if {slug for slug, _, _ in groups.get('tags', [])} & MINOR_SLUGS:
        return None
    spans = {x.get_text(strip=True).split(':', 1)[0]: x.get_text(strip=True).split(':', 1)[1].strip() for x in info.select('span.i_text.pages')}
    m = re.search(r"g_th = \$\.parseJSON\('(.*?)'\)", r.text)
    th = json.loads(m.group(1))
    load_id = s.select_one('#load_id')['value']
    load_dir = s.select_one('#load_dir')['value']
    host = 'i3.hentaifox.com' if int(gid) > 140236 else 'i2.hentaifox.com'
    pages = []
    for n in sorted(th, key=int):
        e, w, h = th[n].split(',')
        pages.append((f'https://{host}/{load_dir}/{load_id}/{n}.{EXT.get(e, "jpg")}', int(w), int(h)))
    cover = s.select_one('div.gallery_left div.cover img')
    rel = [re.search(r'/gallery/(\d+)/', t.select_one('h2.g_title a')['href']).group(1) for t in s.select('div.related_galleries div.thumb')
           if not set((t.get('data-tags') or '').split()) & MINOR_IDS]
    return {'title': info.select_one('h1').get_text(strip=True), 'cover': cover and cover.get('src'), 'groups': groups,
            'spans': spans, 'pages': pages, 'related': rel}


if __name__ == '__main__':
    for p in (1, 2, 3):
        it, nxt, drop, code = listing(home_path(p))
        print(f'LATEST p{p} {code} n={len(it)} next={nxt} dropped={drop}', it[:1])
    it, nxt, drop, code = listing(search_path('english', 1, 'popular'))
    print(f'POPULAR {code} n={len(it)} next={nxt} dropped={drop}', it[:2])
    popular = it
    it, nxt, drop, code = listing(search_path('"big breasts" "sole female" -netorare', 2, 'popular'))
    print(f'TAG SEARCH p2 {code} n={len(it)} next={nxt} dropped={drop}', it[:1])
    it, nxt, drop, code = listing(search_path('"okumoto yuuta"', 1))
    print(f'ARTIST {code} n={len(it)} next={nxt} dropped={drop}', it[:1])
    it, nxt, drop, code = listing(search_path('glasses', 99999))
    print(f'PAST END {code} n={len(it)} next={nxt}')
    covers, pages = [x['cover'] for x in popular[:3]], []
    for g in popular[:2]:
        d = details(g['id'])
        if not d:
            print('DETAILS', g['id'], 'hidden')
            continue
        print('DETAILS', g['id'], '|', d['title'], '| cover', d['cover'], '| spans', d['spans'])
        for k, v in d['groups'].items():
            print('     ', k, v[:6])
        print('   PAGES', len(d['pages']), d['pages'][:2], '| related', d['related'])
        pages += [p[0] for p in d['pages'][:2]]
    for u in covers + pages[:3]:
        r, data = get(u, image=True)
        print('   ', magic(data), u)
