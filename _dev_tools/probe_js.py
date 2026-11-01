"""List the API routes a site's own JS bundles call: python probe_js.py https://atsu.moe/"""
import re, sys, requests
from urllib.parse import urljoin

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36'
S = requests.Session(); S.headers['User-Agent'] = UA
base = sys.argv[1]
pat = re.compile(sys.argv[2] if len(sys.argv) > 2 else r'["`\'](/api/[^"`\'\s]{2,80})')
html = S.get(base, timeout=20).text
seen, todo, found = set(), [urljoin(base, s) for s in re.findall(r'src="([^"]+\.js[^"]*)"', html)], set()
found.update(pat.findall(html))
while todo and len(seen) < 80:
    u = todo.pop()
    if u in seen:
        continue
    seen.add(u)
    try:
        t = S.get(u, timeout=20).text
    except Exception:
        continue
    found.update(pat.findall(t))
    for s in re.findall(r'["\']((?:\.{0,2}/)?[\w./-]*?(?:assets|_next|chunks|build)/[\w./-]+?\.js)["\']', t):
        todo.append(urljoin(u, s))
print(len(seen), 'scripts')
for f in sorted(found):
    print(f)
