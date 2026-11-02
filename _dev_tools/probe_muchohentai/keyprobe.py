"""AES key / subtitles / download-link probe (each read capped at 64 KB, 1.2 s apart).

Usage: keyprobe.py KEY_URL PAGE_URL SUBS_URL DOWNLOAD_URL
 1. key, no cookie, no Referer
 2. key, no cookie, Referer = page
 3. POST keyserver /authorize (Referer/Origin = page)
 4. key with the cookies + Referer
 5. key with the cookies, no Referer
 6. subtitles (no cookie)
 7. download link Range 0-65535 (no cookie, Referer = page)
Only the key's length and a hash prefix are printed (the key itself is not stored).
"""
import sys, time, os, hashlib
import requests

UA = ('Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) '
      'Chrome/130.0.0.0 Mobile Safari/537.36')
SITE = 'https://muchohentai.com'


def get(s, method, url, headers=None, cap=65536):
    time.sleep(1.2)
    h = {'User-Agent': UA}
    h.update(headers or {})
    r = s.request(method, url, headers=h, timeout=30, stream=True, allow_redirects=False)
    data = b''
    for chunk in r.iter_content(8192):
        data += chunk
        if len(data) >= cap:
            break
    r.close()
    data = data[:cap]
    print(f'\n== {method} {url[:140]} -> {r.status_code} ({len(data)} B)')
    for k, v in r.headers.items():
        if k.lower() in ('content-type', 'content-length', 'content-range', 'server', 'location', 'set-cookie',
                         'access-control-allow-origin', 'cache-control', 'cf-cache-status', 'accept-ranges',
                         'content-disposition'):
            print(f'  {k}: {v[:200]}')
    return r, data


def keyinfo(d):
    if len(d) == 16:
        print('  16-byte key, sha1 prefix', hashlib.sha1(d).hexdigest()[:10])
    else:
        print('  body:', d[:200])


def main():
    key, page, subs, dl = sys.argv[1:5]
    s = requests.Session()
    r, d = get(s, 'GET', key); keyinfo(d)
    r, d = get(s, 'GET', key, {'Referer': page}); keyinfo(d)
    r, d = get(s, 'POST', 'https://keyserver.muchohentai.com/authorize',
               {'Referer': page, 'Origin': SITE, 'Accept': 'application/json'})
    print('  body:', d[:100])
    r, d = get(s, 'GET', key, {'Referer': page, 'Origin': SITE}); keyinfo(d)
    r, d = get(s, 'GET', key); keyinfo(d)
    r, d = get(requests.Session(), 'GET', subs, {'Referer': page})
    print(d[:300].decode('utf-8', 'replace'))
    r, d = get(requests.Session(), 'GET', dl, {'Referer': page, 'Range': 'bytes=0-65535'})
    print('  first bytes:', d[:32])


if __name__ == '__main__':
    main()
