"""hentaifox: does search understand exclusions / several tags? Prints the last page number and how many thumbs on
page 1 carry a minors tag id (data-tags). Read-only, ~1 req/s."""
import sys
from urllib.parse import quote_plus
from bs4 import BeautifulSoup
from fetch import get

B = 'https://hentaifox.com'
MINOR_IDS = {'8', '11', '45', '111', '268', '3321', '3322', '715', '1463', '1472'}


def probe(path):
    r = get(B + path)
    s = BeautifulSoup(r.text, 'lxml')
    th = [t for t in s.select('div.thumb') if t.select_one('h2.g_title')]
    pages = [a.get_text(strip=True) for a in s.select('ul.pagination a.page-link')]
    last = max([int(p) for p in pages if p.isdigit()] or [1])
    bad = sum(1 for t in th if set((t.get('data-tags') or '').split()) & MINOR_IDS)
    big = sum(1 for t in th if '24' in (t.get('data-tags') or '').split())
    print(f'    {path}: {r.status_code} n={len(th)} last={last} minors={bad} with-big-breasts={big}')


if __name__ == '__main__':
    qs = sys.argv[1:] or ['big breasts', 'big breasts -lolicon', 'big breasts -lolicon -shotacon', '-lolicon', 'lolicon -lolicon',
                          '"big breasts" glasses', 'big-breasts glasses', 'tag:big-breasts', 'big breasts glasses']
    for q in qs:
        probe('/search/?q=' + quote_plus(q))
