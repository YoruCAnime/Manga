"""Download comix.to's current JS bundle + every chunk it imports into out/js/ (1 req/s).
Then grep them offline."""
import re, time, pathlib, requests
from urllib.parse import urljoin

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36'
OUT = pathlib.Path(__file__).parent / 'out' / 'js'
OUT.mkdir(parents=True, exist_ok=True)
S = requests.Session(); S.headers['User-Agent'] = UA
html = S.get('https://comix.to/', timeout=25).text
todo = [urljoin('https://comix.to/', s) for s in re.findall(r'src="([^"]+\.js)"', html)]
seen = set()
while todo:
    u = todo.pop()
    if u in seen:
        continue
    seen.add(u)
    time.sleep(1.0)
    r = S.get(u, timeout=25)
    name = u.rsplit('/', 1)[1]
    (OUT / name).write_text(r.text, encoding='utf-8')
    print(r.status_code, len(r.text), name)
    for s in re.findall(r'["\'`](\.{0,2}/?[\w./-]*?[\w-]+\.js)["\'`]', r.text):
        if s.startswith('http') or 'node_modules' in s:
            continue
        todo.append(urljoin(u, s))
print(len(seen), 'files')
