"""E-Hentai probe (read-only GETs, ~1 req/s, no cookies, no login).

Checks what the UK 451 block covers: e-hentai.org pages, api.e-hentai.org, exhentai.org (no cookies),
the thumbnail host ehgt.org. Saves every body to out/ so the spec can quote it.
python -X utf8 probe_eh.py [extra urls...]
"""
import os, sys, time, re, requests

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out')
os.makedirs(OUT, exist_ok=True)
UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36'
S = requests.Session()
S.headers['User-Agent'] = UA

URLS = sys.argv[1:] or [
    'https://e-hentai.org/',
    'https://e-hentai.org/popular',
    'https://e-hentai.org/?f_search=english',
    'https://e-hentai.org/robots.txt',
    'https://api.e-hentai.org/api.php',
    'https://exhentai.org/',
    'https://ehgt.org/g/blank.gif',
    'https://forums.e-hentai.org/',
    'https://e-hentai.org/favicon.ico',
]


def slug(u):
    return re.sub(r'[^\w.-]+', '_', u.split('://', 1)[-1])[:90]


for u in URLS:
    try:
        r = S.get(u, timeout=25, allow_redirects=False)
        ct = r.headers.get('content-type')
        print('==', r.status_code, u, ct, len(r.content))
        for h in ('server', 'location', 'set-cookie', 'cf-ray', 'x-frame-options', 'cache-control'):
            if h in r.headers:
                print('   ', h + ':', r.headers[h][:200])
        ext = '.html' if 'html' in (ct or '') else ('.json' if 'json' in (ct or '') else '.bin')
        with open(os.path.join(OUT, slug(u) + ext), 'wb') as f:
            f.write(r.content)
        if 'text' in (ct or '') or 'json' in (ct or ''):
            print(r.text[:1500])
    except Exception as e:
        print('==', 'ERR', u, e)
    time.sleep(1.2)
