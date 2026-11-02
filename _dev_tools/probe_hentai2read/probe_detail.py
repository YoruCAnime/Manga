"""hentai2read: popular feeds, a details page, its chapters and a reader page. Read-only GETs, ~1 req/s.
python probe_detail.py [book_url_path]"""
import json, sys
from fetch import get
from inertia import page_json, shape

B = 'https://hentai2read.com'
HIDE = {6, 23}


def pj(path, name):
    r = get(B + path)
    j = page_json(r.text)
    if j:
        open(f'out/{name}.page.json', 'w', encoding='utf-8').write(json.dumps(j, indent=1, ensure_ascii=False))
    return r, j


if __name__ == '__main__':
    # sorts the browse menu has, tried on search (which can carry without=)
    for s in ('last-week', 'yesterday', 'last-month', 'trending'):
        r, j = pj(f'/search?without=6,23&sort={s}', f'search_sort_{s}')
        b = j['props'].get('books') or {}
        print('   sort', s, '->', j['props'].get('sort'), b.get('total'), [d['title'][:30] for d in (b.get('data') or [])[:3]])
    r, j = pj('/browse/trending', 'browse_trending')
    b = j['props'].get('books') or {}
    print('   trending', b.get('total'), b.get('lastPage'), (b.get('links') or {}).get('next'), [d['title'][:30] for d in (b.get('data') or [])[:3]])
    r, j = pj('/ranking', 'ranking')
    shape(j['props'], maxd=2)
    path = sys.argv[1] if len(sys.argv) > 1 else '/original/33728-penetration-bare-fucking-family'
    r, j = pj(path, 'detail')
    print('component', j['component'])
    shape({k: v for k, v in j['props'].items() if k not in ('ads', 'promos', 'features', 'affiliate', 'announcements')}, maxd=3)
