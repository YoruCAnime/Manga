"""Save atsu.moe's index HTML + all JS bundles locally for grepping (GET only, 1 req/s)."""
import re, time, os, requests
from urllib.parse import urljoin
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36'
S = requests.Session(); S.headers['User-Agent'] = UA
OUT = os.path.join(os.path.dirname(__file__), 'bundle')
os.makedirs(OUT, exist_ok=True)
base = 'https://atsu.moe/'
r = S.get(base, timeout=20)
print(r.status_code, r.headers.get('server'), r.headers.get('cf-ray'))
open(os.path.join(OUT, 'index.html'), 'w', encoding='utf8').write(r.text)
todo = [urljoin(base, s) for s in re.findall(r'(?:src|href)="([^"]+\.js)"', r.text)]
seen = set()
while todo and len(seen) < 150:
    u = todo.pop()
    if u in seen: continue
    seen.add(u)
    time.sleep(1.0)
    try:
        t = S.get(u, timeout=30).text
    except Exception as e:
        print('ERR', u, e); continue
    open(os.path.join(OUT, u.rsplit('/', 1)[-1]), 'w', encoding='utf8').write(t)
    for s in re.findall(r'["\'`]((?:\.{0,2}/)?(?:assets/)?[\w.-]+\.js)["\'`]', t):
        nu = urljoin(u, s)
        if 'atsu.moe' in nu and nu not in seen: todo.append(nu)
print(len(seen), 'files')
