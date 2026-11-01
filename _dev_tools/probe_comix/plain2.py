"""Plain HTTP checks: title HTML with an okhttp UA / app X-Requested-With, tag-search types, and the tag ids of
minors-related tags (names/ids only, so the app can exclude them).  ~1 req / 1.3 s"""
import time, json, requests

B = 'https://comix.to'
AND = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36'


def get(u, **h):
    time.sleep(1.3)
    return requests.get(u, headers=h, timeout=25)


for label, h in (('okhttp UA', {'User-Agent': 'okhttp/4.12.0'}),
                 ('android UA + app X-Requested-With', {'User-Agent': AND, 'X-Requested-With': 'com.exos.mangacracker'}),
                 ('no UA at all', {'User-Agent': ''})):
    r = get(B + '/title/50xvk-i-married-the-dragon-i-killed', **h)
    print(f'title html [{label}]: {r.status_code} {len(r.content)}B initial-data={"initial-data" in r.text}')

for typ in ('tag', 'genre', 'author', 'artist', 'format', 'demographic', 'publisher', 'theme'):
    r = get(f'{B}/api/v1/tags/search?type={typ}&q=a&limit=3', **{'User-Agent': AND})
    print(f'tags type={typ}: {r.status_code} {r.text[:160]}')

hide = {}
for q in ('loli', 'lolicon', 'shota', 'shotacon', 'toddler', 'child', 'minor', 'kid', 'young', 'underage', 'elementary', 'little girl', 'little boy'):
    r = get(f'{B}/api/v1/tags/search?type=tag&q={q}&limit=20', **{'User-Agent': AND})
    try:
        res = r.json().get('result', [])
    except Exception:
        res = r.text[:100]
    print(f'tag q={q}:', json.dumps(res, ensure_ascii=False)[:600])
    if isinstance(res, list):
        for t in res:
            hide[t['id']] = t['label']
print('ALL', json.dumps(hide, ensure_ascii=False))
