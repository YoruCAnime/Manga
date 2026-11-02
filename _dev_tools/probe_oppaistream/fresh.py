"""Fresh client, no cookies, okhttp UA: does search.php and a watch page still work? Count/headers only."""
import re, time, requests, pathlib

OUT = pathlib.Path(__file__).parent / 'out'
H = {'User-Agent': 'okhttp/4.12.0'}
r = requests.post('https://oppai.stream/api/search.php', headers=H, timeout=30,
                  data={'text': '', 'tags': 'Vanilla', 'blacklist': 'Loli,Shota', 'studios': '', 'order': 'views',
                        'limit': '15', 'page': '3', 'character_filters': '{}'})
print('search', r.status_code, r.headers.get('content-type'), re.findall(r'results-num[^>]*>([^<]*)<', r.text),
      'rows', len(re.findall('epid=', r.text)), re.findall(r"<div style='display: none;'>([^<]*)</div>", r.text)[:1])
time.sleep(2)
r = requests.get('https://oppai.stream/watch/Natsu%20to%20Hako-1', headers=H, timeout=30)
print('watch', r.status_code, len(r.content), 'folder' if 'var folder' in r.text else 'NO FOLDER',
      re.findall(r'var res = "([^"]*)"', r.text))
time.sleep(2)
r = requests.get('https://oppai.stream/series', headers=H, timeout=30)
(OUT / 'series_index.html').write_bytes(r.content)
print('series', r.status_code, len(r.content), 'series links', len(set(re.findall(r'href="https://oppai.stream/series/[^"]*"', r.text))),
      'pages', sorted(set(re.findall(r'[?&]page=(\d+)', r.text)))[:10])
time.sleep(2)
r = requests.get('https://oppai.stream/watch/This%20Does%20Not%20Exist-1', headers=H, timeout=30, allow_redirects=False)
print('missing', r.status_code, r.headers.get('location'), len(r.content), 'var folder' in r.text)
