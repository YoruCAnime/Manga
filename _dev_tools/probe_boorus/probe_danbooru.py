"""Danbooru probe (read-only GETs, 1.2 s apart, anonymous, no key).

Step 'ua': which User-Agent gets past Cloudflare on posts.json.
Step 'api': the endpoints the spec needs, with an agreed UA (argv[2]); bodies saved to out/danbooru_*.json.
Samples stay on rating:g with neutral tags (scenery, landscape), minor tags excluded where tags are free.
python -X utf8 probe_danbooru.py ua
python -X utf8 probe_danbooru.py api "MangaCracker/1.0"
"""
import os, sys, time, json, requests

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'out')
os.makedirs(OUT, exist_ok=True)
BASE = 'https://danbooru.donmai.us'


def get(url, ua, name=None, params=None, extra=None):
    time.sleep(1.2)
    h = {} if ua is None else {'User-Agent': ua}
    h.update(extra or {})
    s = requests.Session()
    if ua is None:
        s.headers.pop('User-Agent', None)
    r = s.get(url, params=params, headers=h, timeout=25, allow_redirects=False)
    print('==', r.status_code, r.url, '| UA', repr(ua), '|', r.headers.get('content-type'), len(r.content))
    for k in ('server', 'cf-ray', 'x-rate-limit', 'x-ratelimit-limit', 'x-ratelimit-remaining', 'retry-after',
              'cache-control', 'location', 'x-runtime', 'x-request-id'):
        if k in {x.lower() for x in r.headers}:
            print('   ', k + ':', r.headers.get(k))
    if name:
        with open(os.path.join(OUT, name), 'wb') as f:
            f.write(r.content)
    return r


step = sys.argv[1] if len(sys.argv) > 1 else 'ua'
if step == 'ua':
    url = BASE + '/posts.json'
    p = {'limit': 2, 'tags': 'rating:g scenery'}
    for ua in [None, 'python-requests/2.32', 'MangaCracker/1.0', 'MangaCracker/1.0 (Android; personal reader)',
               'okhttp/4.12.0',
               'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36']:
        r = get(url, ua, params=p)
        print('   ', r.text[:160].replace('\n', ' '))
else:
    ua = sys.argv[2]
    q = lambda name, path, params=None: get(BASE + path, ua, 'danbooru_' + name + '.json', params)
    q('posts', '/posts.json', {'limit': 2, 'tags': 'rating:g scenery'})
    q('posts_3tags', '/posts.json', {'limit': 1, 'tags': 'scenery sky cloud'})
    q('posts_meta', '/posts.json', {'limit': 1, 'tags': 'scenery order:score rating:g'})
    q('posts_page_b', '/posts.json', {'limit': 1, 'tags': 'rating:g scenery', 'page': 'b9000000'})
    q('posts_page_high', '/posts.json', {'limit': 1, 'tags': 'rating:g', 'page': 1001})
    q('post_one', '/posts/1.json')
    q('autocomplete', '/autocomplete.json', {'search[query]': 'scen', 'search[type]': 'tag_query', 'limit': 5})
    q('tags', '/tags.json', {'search[name_matches]': 'scen*', 'search[order]': 'count', 'limit': 5})
    q('artists', '/artists.json', {'search[any_name_matches]': 'kaoru', 'limit': 2})
    q('artist_tags', '/tags.json', {'search[category]': 1, 'search[order]': 'count', 'limit': 3})
    q('pools', '/pools.json', {'search[category]': 'series', 'search[name_matches]': '*scenery*', 'limit': 2})
    q('pools_order', '/pools.json', {'search[category]': 'series', 'search[order]': 'post_count', 'limit': 2,
                                     'only': 'id,name,post_count,category,is_deleted'})
    q('counts', '/counts/posts.json', {'tags': 'rating:g scenery'})
    q('related', '/related_tag.json', {'query': 'scenery', 'limit': 5})
    q('popular', '/explore/posts/popular.json', {'date': '2026-10-31', 'scale': 'day', 'limit': 2})
