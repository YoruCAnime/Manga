"""Cloudflare check: bare clients (python-requests UA, okhttp UA, no Accept) on API + Typesense + the .webp twin of a small cover."""
import time, requests
CASES = [
    ('api python-requests UA', 'https://atsu.moe/api/manga/page?id=ZrRJ', {}),
    ('api okhttp UA', 'https://atsu.moe/api/home2/popular?offset=0&limit=2', {'User-Agent': 'okhttp/4.12.0'}),
    ('typesense okhttp UA', 'https://atsu.moe/collections/manga/documents/search?q=*&query_by=title&per_page=1&include_fields=id', {'User-Agent': 'okhttp/4.12.0'}),
    ('cover small .webp twin', 'https://cdn.atsu.moe/static/posters/3i6P1lYIogbwab92-small.webp', {'User-Agent': 'okhttp/4.12.0'}),
    ('html page okhttp UA', 'https://atsu.moe/manga/ZrRJ', {'User-Agent': 'okhttp/4.12.0'}),
]
for i, (label, url, h) in enumerate(CASES):
    if i: time.sleep(1.2)
    r = requests.get(url, headers=h, timeout=30)
    print(f'## {label}: {r.status_code} {r.headers.get("content-type")} {len(r.content)} B cf-mitigated={r.headers.get("cf-mitigated")} set-cookie={bool(r.headers.get("set-cookie"))}')
    if 'html' in (r.headers.get('content-type') or ''):
        import re
        print('   title:', re.findall(r'<title>([^<]*)', r.text)[:1], 'challenge' in r.text.lower(), 'window.home2' in r.text, 'window.mangaPage' in r.text or 'mangaPage' in r.text)
