"""Remaining checks (1.2 s apart, reads capped at 64 KB unless noted):
 1. POST /authorize with a bare OkHttp-like request (no Origin, no Referer, okhttp UA)
 2. key.bin with that cookie and okhttp UA (is the cookie bound to Origin/UA?)
 3. variant playlist again: is the ?key= token the same as 25 min earlier?
 4. original thumbnail (size suffix stripped): Range 0-65535 -> content-range gives size
 5. /genre/vanilla/page/2?orderby=views (sort on archives) -> saved
 6. /page/2/?s=episode (search paging) -> saved
 7. /series/amakano?orderby=title (sort param on series) -> saved
"""
import os, time, hashlib
import requests

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out')
UA = ('Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) '
      'Chrome/130.0.0.0 Mobile Safari/537.36')
EP = 'https://va01.edge.tmncdn.io/wp-content/uploads/Hitozukiai_ga_Nigate/episode_4/media-1/stream.m3u8'


def req(s, method, url, headers, save=None, cap=65536, full=False):
    time.sleep(1.2)
    r = s.request(method, url, headers=headers, timeout=30, stream=True, allow_redirects=True)
    data = b''
    for chunk in r.iter_content(8192):
        data += chunk
        if not full and len(data) >= cap:
            break
    r.close()
    if not full:
        data = data[:cap]
    print(f'\n== {method} {url[:150]} -> {r.status_code} ({len(data)} B)')
    for k in ('content-type', 'content-length', 'content-range', 'server'):
        if k in r.headers:
            print(f'  {k}: {r.headers[k][:200]}')
    if save:
        with open(os.path.join(OUT, save), 'wb') as f:
            f.write(data)
    return r, data


def main():
    s = requests.Session()
    ok = {'User-Agent': 'okhttp/4.12.0'}
    r, d = req(s, 'POST', 'https://keyserver.muchohentai.com/authorize', ok)
    print('  body:', d[:80], 'cookies:', [c.name[:20] for c in s.cookies])
    r, d = req(s, 'GET', EP, ok)
    key = [l for l in d.decode().splitlines() if l.startswith('#EXT-X-KEY')][0]
    print('  ', key)
    kurl = key.split('URI="')[1].split('"')[0]
    r, d = req(s, 'GET', kurl, ok)
    print('  key len', len(d), 'sha1', hashlib.sha1(d).hexdigest()[:10] if len(d) == 16 else d[:80])
    b = {'User-Agent': UA}
    r, d = req(requests.Session(), 'GET', 'https://muchohentai.com/wp-content/uploads/es-20.jpg',
               dict(b, Range='bytes=0-65535'))
    print('  first bytes', d[:12])
    req(requests.Session(), 'GET', 'https://muchohentai.com/genre/vanilla/page/2?orderby=views', b,
        save='genre_vanilla_views_p2.html', full=True)
    req(requests.Session(), 'GET', 'https://muchohentai.com/page/2/?s=episode', b,
        save='search_episode_p2.html', full=True)
    req(requests.Session(), 'GET', 'https://muchohentai.com/series/amakano?orderby=title&order=asc', b,
        save='series_amakano_title.html', full=True)


if __name__ == '__main__':
    main()
