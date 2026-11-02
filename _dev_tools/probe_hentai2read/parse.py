"""hentai2read: the same steps the Kotlin source takes, checked live. Read-only, ~1 req/s.
list (latest + popular + a tag search) -> details -> chapters -> page images, then 3 covers + 3 pages fetched (64 KB max)
and their magic bytes checked. Minors guard: without=6,23 on every list; details refused when a tag id is 6 / 23."""
import json, re
from datetime import datetime
from fetch import get

B = 'https://hentai2read.com'
GUARD = '6,23'          # Lolicon (category 6), Shota (category 23)
HIDE = {6, 23}
MAGIC = {b'\xff\xd8\xff': 'jpeg', b'\x89PNG': 'png', b'RIFF': 'webp', b'GIF8': 'gif'}
_ver = [None]


def page(path):
    """Inertia JSON with the cached version; on 409 (new deploy) or no version: the HTML page's embedded JSON."""
    if _ver[0]:
        r = get(B + path, headers={'X-Inertia': 'true', 'X-Inertia-Version': _ver[0], 'X-Requested-With': 'XMLHttpRequest'})
        if r.status_code == 200 and r.headers.get('content-type', '').startswith('application/json'):
            return r.json()
        print('    (inertia', r.status_code, '-> html)')
    r = get(B + path)
    m = re.search(r'<script data-page="app" type="application/json">(.*?)</script>', r.text, re.S)
    j = json.loads(m.group(1))
    _ver[0] = j['version']
    return j


def items(j):
    b = j['props']['books']
    out = [{'id': str(d['id']), 'title': d['title'], 'cover': d['cover'], 'sub': f"{d['typeLabel']} · {d['chapters']} ch · {d['pageCount']} pages"}
           for d in b['data'] if d.get('type') != 'video']
    nxt = bool((b.get('links') or {}).get('next'))
    return out, nxt, b.get('page'), b.get('lastPage')


def magic(data):
    return next((v for k, v in MAGIC.items() if data.startswith(k)), None)


if __name__ == '__main__':
    lat, nxt, p, last = items(page(f'/search?without={GUARD}&sort=latest'))
    print('LATEST', len(lat), 'next', nxt, p, last, lat[:2])
    pop, nxt, p, last = items(page(f'/search?without={GUARD}&sort=most-viewed&page=2'))
    print('POPULAR p2', len(pop), 'next', nxt, p, last, pop[:2])
    tg, nxt, p, last = items(page(f'/search?with=8,113&without={GUARD},99&type=doujinshi&status=completed&sort=top-rated'))
    print('TAGS', len(tg), 'next', nxt, p, last, tg[:2])
    tq, nxt, p, last = items(page(f'/search?q=teacher&without={GUARD}'))
    print('TEXT', len(tq), 'next', nxt, p, last, tq[:1])
    sug = get(B + '/tags?q=glass', headers={'X-Inertia': 'true', 'X-Inertia-Version': _ver[0], 'X-Requested-With': 'XMLHttpRequest'}).json()
    print('SUGGEST', [(t['id'], t['name'], t['type'], t['count']) for t in sug['props']['tags']['data']])
    all_tags = get(B + '/search/tags', headers={'Accept': 'application/json'}).json()
    print('ALL TAGS', len(all_tags), sorted(all_tags, key=lambda t: -t['count'])[:5])

    pages_checked, covers = [], [x['cover'] for x in pop[:3]]
    for bid in [pop[0]['id'], pop[1]['id']]:
        d = page(f'/original/{bid}')          # any type word works: the site 301s to the right slug
        bk, pr = d['props']['book'], d['props']
        ids = {t['id'] for t in bk['tags']}
        if ids & HIDE:
            print('DETAILS', bid, 'hidden')
            continue
        groups = {g['type']: [t['name'] for t in g['tags']] for g in bk['groups']}
        print('DETAILS', bid, '|', bk['title'], '| alt', bk.get('alt'), '| type', bk['type']['label'], '| status', bk['status']['label'],
              '| genres', [t['name'] for t in bk['tags']][:8], '| groups', groups, '| desc', (bk.get('description') or '')[:80],
              '| stats', pr['stats'])
        ch = [(c['id'], c['number'], c['title'][:40], int(datetime.strptime(c['date'], '%b %d, %Y').timestamp()) if c['date'][0].isalpha() else c['date'])
              for c in pr['chapters']]
        print('CHAPTERS (newest first)', len(ch), ch[:3])
        rd = page(f"/reader/{bid}/{pr['chapters'][0]['id']}/page-1")
        imgs = rd['props']['images']
        print('PAGES', len(imgs), imgs[:2], rd['props']['sizes'][:2])
        pages_checked += imgs[:2]
    for u in covers + pages_checked[:3]:
        r, data = get(u, image=True)
        print('   ', magic(data), u)
