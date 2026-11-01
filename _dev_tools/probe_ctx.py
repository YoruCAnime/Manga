"""Show text around a regex in a site's JS bundles: python probe_ctx.py https://atsu.moe/ "search/manga" [width]"""
import re, sys, requests
from urllib.parse import urljoin

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36'
S = requests.Session(); S.headers['User-Agent'] = UA
base, rx = sys.argv[1], re.compile(sys.argv[2])
w = int(sys.argv[3]) if len(sys.argv) > 3 else 250
html = S.get(base, timeout=20).text
seen, todo = set(), [urljoin(base, s) for s in re.findall(r'src="([^"]+\.js[^"]*)"', html)]
texts = [('PAGE', html)]
while todo and len(seen) < 120:
    u = todo.pop()
    if u in seen:
        continue
    seen.add(u)
    try:
        t = S.get(u, timeout=20).text
    except Exception:
        continue
    texts.append((u, t))
    for s in re.findall(r'["\']((?:\.{0,2}/)?[\w./-]*?(?:assets|_next|chunks|build)/[\w./-]+?\.js)["\']', t):
        todo.append(urljoin(u, s))
n = 0
for u, t in texts:
    for m in rx.finditer(t):
        n += 1
        if n > 30:
            break
        print('--', u.rsplit('/', 1)[-1][:60])
        print(t[max(0, m.start() - w):m.end() + w].replace('\n', ' '))
