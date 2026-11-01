"""Fetch 2 chapter page images + 2 cover variants from cdn.atsu.moe with minimal headers (GET only).
Prints status, content-type, size, cache headers, and pixel size when Pillow can read it."""
import io, time, requests

TESTS = [
    # (label, url, headers)
    ('page0 bare (python UA, no Referer)', 'https://cdn.atsu.moe/static/pages/XYDXv2/0.webp', {}),
    ('page1 foreign Referer', 'https://cdn.atsu.moe/static/pages/XYDXv2/1.webp',
     {'User-Agent': 'Mozilla/5.0 (Linux; Android 14) MangaCracker', 'Referer': 'https://example.com/'}),
    ('cover large avif', 'https://cdn.atsu.moe/static/posters/3i6P1lYIogbwab92-large.avif', {}),
    ('cover original jpg', 'https://cdn.atsu.moe/static/posters/3i6P1lYIogbwab92.jpg', {}),
]
for i, (label, url, h) in enumerate(TESTS):
    if i:
        time.sleep(1.2)
    r = requests.get(url, headers=h, timeout=30)
    dims = '?'
    try:
        from PIL import Image
        dims = Image.open(io.BytesIO(r.content)).size
    except Exception as e:
        dims = f'(PIL: {type(e).__name__})'
    print(f'## {label}: {r.status_code} {r.headers.get("content-type")} {len(r.content)} B dims={dims}')
    for k in ('cache-control', 'cf-cache-status', 'access-control-allow-origin', 'server', 'set-cookie', 'age', 'vary'):
        if k in r.headers:
            print(f'   {k}: {r.headers[k][:120]}')
