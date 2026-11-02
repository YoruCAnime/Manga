"""Stream flow probe: m3u8 with / without the keyserver authorize cookie.

Usage: stream.py M3U8_PATH VIDEO_PAGE_URL
Steps (1.2 s apart, each read capped at 64 KB):
 1. GET m3u8 on va01 with no cookie, no Referer
 2. GET m3u8 on va01 with Referer/Origin only
 3. POST https://keyserver.muchohentai.com/authorize (Origin/Referer = site) -> print status, headers, cookies
 4. GET m3u8 again with the session cookies
 5. GET the first variant / first segment (Range 0-65535) with cookies
"""
import sys, time, os
import requests
from urllib.parse import urljoin

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out')
os.makedirs(OUT, exist_ok=True)
UA = ('Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) '
      'Chrome/130.0.0.0 Mobile Safari/537.36')
SITE = 'https://muchohentai.com'


def capped(s, method, url, **kw):
    time.sleep(1.2)
    h = kw.pop('headers', {})
    h.setdefault('User-Agent', UA)
    r = s.request(method, url, headers=h, timeout=30, stream=True, allow_redirects=False, **kw)
    data = b''
    for chunk in r.iter_content(8192):
        data += chunk
        if len(data) >= 65536:
            break
    r.close()
    data = data[:65536]
    print(f'\n== {method} {url} -> {r.status_code} ({len(data)} B)')
    for k, v in r.headers.items():
        if k.lower() in ('content-type', 'content-length', 'content-range', 'server', 'set-cookie', 'location',
                         'access-control-allow-origin', 'access-control-allow-credentials', 'cache-control',
                         'cf-cache-status', 'expires', 'accept-ranges', 'x-cache'):
            print(f'  {k}: {v[:400]}')
    return r, data


def show(data):
    if data[:7] == b'#EXTM3U':
        print(data[:2500].decode('utf-8', 'replace'))
    else:
        print('  first bytes:', data[:48])


def main():
    path, page = sys.argv[1], sys.argv[2]
    m3u8 = 'https://va01.edge.tmncdn.io/' + path.lstrip('/')
    s = requests.Session()
    r, d = capped(s, 'GET', m3u8); show(d)
    r, d = capped(s, 'GET', m3u8, headers={'Referer': page, 'Origin': SITE}); show(d)
    r, d = capped(s, 'POST', 'https://keyserver.muchohentai.com/authorize',
                  headers={'Referer': page, 'Origin': SITE, 'Accept': 'application/json'})
    print('  body:', d[:300])
    print('  session cookies:', [(c.name, c.domain, c.path, c.expires, len(c.value or '')) for c in s.cookies])
    r, d = capped(s, 'GET', m3u8, headers={'Referer': page, 'Origin': SITE}); show(d)
    with open(os.path.join(OUT, 'master.m3u8'), 'wb') as f:
        f.write(d)
    if d[:7] == b'#EXTM3U':
        lines = [l for l in d.decode('utf-8', 'replace').splitlines() if l and not l.startswith('#')]
        if lines:
            sub = urljoin(m3u8, lines[0])
            r, d2 = capped(s, 'GET', sub, headers={'Referer': page, 'Origin': SITE}); show(d2)
            with open(os.path.join(OUT, 'variant.m3u8'), 'wb') as f:
                f.write(d2)
            if d2[:7] == b'#EXTM3U':
                segs = [l for l in d2.decode('utf-8', 'replace').splitlines() if l and not l.startswith('#')]
                keys = [l for l in d2.decode('utf-8', 'replace').splitlines() if l.startswith('#EXT-X-KEY')]
                print('  keys:', keys[:2])
                if segs:
                    seg = urljoin(sub, segs[0])
                    r, d3 = capped(s, 'GET', seg, headers={'Referer': page, 'Origin': SITE,
                                                           'Range': 'bytes=0-65535'})
                    show(d3)


if __name__ == '__main__':
    main()
