"""Which list URLs sort by views / take a date range / search (nsfwr34.com). Prints the first items' views + dates.

Usage: sorts.py URL [URL ...]   (1.1 s between requests)
"""
import sys, time, os, re
import requests
from bs4 import BeautifulSoup

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out')
UA = ('Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) '
      'Chrome/130.0.0.0 Mobile Safari/537.36')


def views(t):
    t = t.strip().lower().replace(',', '').replace('views', '').replace('view', '').strip()
    m = re.match(r'([\d.]+)\s*([km]?)', t)
    if not m:
        return -1
    n = float(m.group(1))
    return int(n * {'': 1, 'k': 1e3, 'm': 1e6}[m.group(2)])


for i, url in enumerate(sys.argv[1:]):
    time.sleep(1.1)
    r = requests.get(url, headers={'User-Agent': UA, 'Accept-Language': 'en-GB,en;q=0.9'}, timeout=30)
    with open(os.path.join(OUT, 'sort_%d.html' % i), 'wb') as f:
        f.write(r.content)
    s = BeautifulSoup(r.text, 'lxml')
    arts = s.select('article.type-video')
    nxt = s.select_one('link[rel=next]')
    print(r.status_code, url, len(arts), 'items, next =', nxt['href'] if nxt else None)
    for a in arts[:8]:
        v = a.select_one('.post-meta__views')
        d = a.select_one('time')
        t = a.select_one('.post-title a')
        print('   ', views(v.get_text()) if v else '-', d['datetime'] if d else '-', (t.get_text(strip=True) if t else '')[:60])
