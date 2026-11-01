"""Show the structure of the gallery list markup inside a saved E-Hentai body (the 451 page).
python -X utf8 rows.py out/<file>.html [nrows]
Prints the table / row classes, one trimmed row, and counts - not the whole listing.
"""
import re, sys
from bs4 import BeautifulSoup

path = sys.argv[1]
n = int(sys.argv[2]) if len(sys.argv) > 2 else 1
soup = BeautifulSoup(open(path, encoding='utf-8', errors='replace').read(), 'html.parser')
t = soup.select_one('table.itg') or soup.select_one('div.itg')
print('list container:', t.name if t else None, t.get('class') if t else None)
for s in soup.select('.searchnav')[:1]:
    print('searchnav:', str(s)[:1500])
print('searchtext:', [str(x)[:300] for x in soup.select('.searchtext')])
rows = [r for r in (t.select('tr') if t else []) if r.select_one('.glname')]
print('rows with .glname:', len(rows))
for r in rows[:n]:
    s = str(r)
    s = re.sub(r'\s+', ' ', s)
    print(s[:4000])
# thumbnails present?
imgs = [i.get('data-src') or i.get('src') for i in (t.select('img') if t else [])]
print('img count', len(imgs), 'sample', imgs[:3])
