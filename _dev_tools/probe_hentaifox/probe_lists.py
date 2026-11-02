"""hentaifox: which list / search URLs exist and how they page. Read-only GETs, ~1 req/s.
Only counts, ids and pagination are printed (no gallery content is used for anything else)."""
import re, sys
from bs4 import BeautifulSoup
from fetch import get

B = 'https://hentaifox.com'


def show(path, save=None):
    r = get(B + path, out=f'out/{save}.html' if save else None)
    s = BeautifulSoup(r.text, 'lxml')
    th = [t for t in s.select('div.thumb') if t.select_one('h2.g_title')]
    pag = [a.get('href') for a in s.select('ul.pagination a.page-link')]
    act = s.select_one('ul.pagination li.active')
    nxt = [a.get('href') for a in s.select('ul.pagination a') if 'next' in a.get_text(strip=True).lower()]
    print(f'    {path}: {r.status_code} galleries={len(th)} active={act and act.get_text(strip=True)} next={nxt} pages={pag[:3]}..{pag[-2:]}')
    if th:
        t = th[0]
        print('      first:', t.select_one('h2.g_title a')['href'], t.get('data-tags'), t.select_one('img').get('data-src'))
    return r, s


if __name__ == '__main__':
    for p in sys.argv[1:] or ['/pag/2/', '/page/2/', '/popular/', '/popular/pag/2/', '/search/?q=big+breasts', '/search/?q=big+breasts&sort=popular',
                              '/search/?q=big+breasts&sort=popular&page=2', '/tag/big-breasts/', '/tag/big-breasts/popular/', '/tag/big-breasts/pag/2/']:
        show(p, re.sub(r'\W+', '_', p).strip('_') or 'root')
