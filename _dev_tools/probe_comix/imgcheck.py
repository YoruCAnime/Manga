"""Fetch ONE comix page image twice (no Referer / with comix Referer): status, headers (x-scramble-*, x-enc-*),
magic bytes. Uses out/maphook_chapter.json (run maphook.py first; urls can expire)."""
import json, pathlib, time, requests

OUT = pathlib.Path(__file__).parent / 'out'
d = json.load(open(OUT / 'maphook_chapter.json', encoding='utf-8'))
pages = json.loads(d['plain'][0]['all'])
u = pages[0]['url']
UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36'
for label, h in (('no referer', {}), ('referer comix', {'Referer': 'https://comix.to/', 'Origin': 'https://comix.to'})):
    r = requests.get(u, headers={'User-Agent': UA, 'Accept': 'image/avif,image/webp,image/*,*/*', **h}, timeout=30)
    b = r.content
    print(f'== {label}: {r.status_code} {len(b)}B type={r.headers.get("content-type")} magic={b[:12]!r}')
    for k, v in r.headers.items():
        if k.lower().startswith(('x-', 'cache', 'access-control', 'server', 'cf-')):
            print(f'     {k}: {v}')
    if r.ok:
        (OUT / f'img_{label.replace(" ", "_")}.bin').write_bytes(b)
    time.sleep(1.5)
print('url was', u, pages[0])
