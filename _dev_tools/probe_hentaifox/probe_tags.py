"""hentaifox: the full tag list (/tags/ pages, name + slug + count) -> out/tags.tsv, then the numeric id of the minors
tags (the id common to every thumb's data-tags on that tag's own list page; only data-tags are read there).
Read-only, ~1 req/s. python probe_tags.py [list|ids|both]"""
import re, sys, pathlib
from bs4 import BeautifulSoup
from fetch import get

B = 'https://hentaifox.com'
OUT = pathlib.Path(__file__).parent / 'out'
MINOR_WORDS = ('loli', 'shota', 'toddler', 'child', 'kid', 'young', 'minor', 'underage', 'kodomo', 'baby', 'infant',
               'school-age', 'elementary', 'kindergarten', 'randoseru', 'age-regression', 'aged-down', 'cub')


def tag_list():
    rows, page = [], 1
    while True:
        r = get(B + ('/tags/' if page == 1 else f'/tags/pag/{page}/'))
        s = BeautifulSoup(r.text, 'lxml')
        got = 0
        for a in s.select('div.list_tags a.tag_btn'):
            slug = a['href'].strip('/').split('/')[-1]
            name = a.select_one('.list_tag').get_text(strip=True)
            cnt = int(a.select_one('.badge').get_text(strip=True).replace(',', '') or 0)
            rows.append((slug, name, cnt))
            got += 1
        nxt = [x['href'] for x in s.select('ul.pagination a') if x.get('href', '').endswith(f'/pag/{page + 1}/')]
        print(f'   page {page}: {got} tags, next={bool(nxt)}')
        if not got or not nxt:
            break
        page += 1
    rows.sort(key=lambda t: -t[2])
    (OUT / 'tags.tsv').write_text('slug\tname\tcount\n' + '\n'.join(f'{a}\t{b}\t{c}' for a, b, c in rows), encoding='utf-8')
    print(len(rows), 'tags; top:', rows[:25])
    print('minor-ish:', [r for r in rows if any(w in r[0] for w in MINOR_WORDS)])


def tag_id(slug):
    r = get(f'{B}/tag/{slug}/')
    if r.status_code != 200:
        return None
    s = BeautifulSoup(r.text, 'lxml')
    sets = [set((t.get('data-tags') or '').split()) for t in s.select('div.thumb[data-tags]')]
    common = set.intersection(*sets) if sets else set()
    return sorted(common, key=int), len(sets)


if __name__ == '__main__':
    what = sys.argv[1] if len(sys.argv) > 1 else 'both'
    if what in ('list', 'both'):
        tag_list()
    if what in ('ids', 'both'):
        for slug in ('lolicon', 'shotacon', 'big-breasts', 'english', 'sole-female'):
            print('   id of', slug, tag_id(slug))
