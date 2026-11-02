"""Gentle probe of muchohentai.com (spec: _dev_tools/specs/muchohentai.md).

Usage: probe.py get URL NAME          -> GET, save body to out/NAME, print status + headers
       probe.py range URL NAME [REF]  -> GET with Range: bytes=0-65535 (never more), print headers + first bytes
Every call sleeps 1.2 s first. One request per call.
"""
import sys, time, os, json
import requests

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out')
os.makedirs(OUT, exist_ok=True)
UA = ('Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) '
      'Chrome/130.0.0.0 Mobile Safari/537.36')
LOG = os.path.join(OUT, 'requests.log')


def log(line):
    with open(LOG, 'a', encoding='utf-8') as f:
        f.write(time.strftime('%H:%M:%S ') + line + '\n')


def show_headers(r):
    for k in ('content-type', 'content-length', 'content-range', 'server', 'cf-ray', 'cf-cache-status',
              'location', 'set-cookie', 'cache-control', 'x-robots-tag', 'accept-ranges', 'last-modified'):
        if k in r.headers:
            print(f'  {k}: {r.headers[k][:300]}')


def main():
    mode, url, name = sys.argv[1], sys.argv[2], sys.argv[3]
    ref = sys.argv[4] if len(sys.argv) > 4 else None
    time.sleep(1.2)
    h = {'User-Agent': UA, 'Accept-Language': 'en-GB,en;q=0.9'}
    if ref:
        h['Referer'] = ref
    if mode == 'get':
        r = requests.get(url, headers=h, timeout=30, allow_redirects=True)
        body = r.content
        with open(os.path.join(OUT, name), 'wb') as f:
            f.write(body)
        print(r.status_code, r.url, len(body), 'bytes')
        for hr in r.history:
            print('  redirect', hr.status_code, hr.url, '->', hr.headers.get('location'))
        show_headers(r)
        log(f'GET {r.status_code} {url}')
    elif mode == 'range':
        h['Range'] = 'bytes=0-65535'
        r = requests.get(url, headers=h, timeout=30, stream=True, allow_redirects=True)
        data = b''
        for chunk in r.iter_content(8192):
            data += chunk
            if len(data) >= 65536:
                break
        r.close()
        data = data[:65536]
        with open(os.path.join(OUT, name), 'wb') as f:
            f.write(data)
        print(r.status_code, r.url, 'got', len(data), 'bytes')
        for hr in r.history:
            print('  redirect', hr.status_code, hr.url, '->', hr.headers.get('location'))
        show_headers(r)
        print('  first bytes:', data[:64])
        if data[:7] == b'#EXTM3U':
            print(data[:3000].decode('utf-8', 'replace'))
        log(f'RANGE {r.status_code} {url}')


if __name__ == '__main__':
    main()
