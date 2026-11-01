"""Gelbooru probe (read-only GETs, 1.2 s apart, anonymous, no key). Bodies saved to out/gelbooru_<name>.*
Samples stay on rating:general with neutral tags (scenery), minor tags excluded.
python -X utf8 probe_gelbooru.py ua|html|api|view ID|pool ID
"""
import os, sys, time, requests

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out')
os.makedirs(OUT, exist_ok=True)
B = 'https://gelbooru.com/index.php'
CHROME = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36'
SAFE = 'scenery rating:general -loli -shota -lolicon -shotacon -toddlercon'


def get(params, name=None, ua='MangaCracker/1.0', cookies=None, url=B):
    time.sleep(1.2)
    s = requests.Session()
    if ua:
        s.headers['User-Agent'] = ua
    else:
        s.headers.pop('User-Agent')
    r = s.get(url, params=params, timeout=25, cookies=cookies or {}, allow_redirects=False)
    ct = r.headers.get('content-type', '')
    print('==', r.status_code, r.url, '| UA', repr(ua)[:24], '|', ct, len(r.content))
    for k in ('server', 'location', 'set-cookie', 'retry-after', 'x-ratelimit-limit', 'x-ratelimit-remaining', 'cache-control'):
        if r.headers.get(k):
            print('   ', k + ':', r.headers[k][:200])
    if name:
        ext = '.json' if 'json' in ct else ('.xml' if 'xml' in ct else '.html')
        open(os.path.join(OUT, 'gelbooru_' + name + ext), 'wb').write(r.content)
    return r


step = sys.argv[1] if len(sys.argv) > 1 else 'ua'
if step == 'ua':
    for ua in [None, 'MangaCracker/1.0', CHROME, 'python-requests/2.32']:
        r = get({'page': 'post', 's': 'list', 'tags': SAFE}, ua=ua)
        print('   ', r.text[:200].replace('\n', ' '))
elif step == 'html':
    get({'page': 'post', 's': 'list', 'tags': SAFE}, 'list')
    get({'page': 'post', 's': 'list', 'tags': SAFE, 'pid': 42}, 'list_pid42')
    get({'page': 'post', 's': 'list', 'tags': SAFE + ' sort:score'}, 'list_score')
    get({'page': 'post', 's': 'list', 'tags': 'scenery sky cloud ocean beach rating:general -loli -shota'}, 'list_manytags')
    get({'page': 'autocomplete2', 'term': 'scen', 'type': 'tag_query', 'limit': 5}, 'autocomplete2')
    get({'page': 'autocomplete', 'term': 'scen'}, 'autocomplete')
    get({'page': 'tags', 's': 'list', 'tags': 'scen*', 'sort': 'desc', 'order_by': 'index_count'}, 'tags_list')
elif step == 'api':
    get({'page': 'dapi', 's': 'post', 'q': 'index', 'json': 1, 'limit': 2, 'tags': SAFE}, 'dapi_post')
    get({'page': 'dapi', 's': 'post', 'q': 'index', 'limit': 2, 'tags': SAFE}, 'dapi_post_xml')
    get({'page': 'dapi', 's': 'tag', 'q': 'index', 'json': 1, 'limit': 3, 'name_pattern': 'scen%'}, 'dapi_tag')
    get({'page': 'dapi', 's': 'comment', 'q': 'index', 'post_id': 1}, 'dapi_comment')
    get({'page': 'dapi', 's': 'user', 'q': 'index', 'json': 1, 'limit': 1}, 'dapi_user')
elif step == 'view':
    get({'page': 'post', 's': 'view', 'id': sys.argv[2]}, 'view')
elif step == 'pool':
    if len(sys.argv) > 2:
        get({'page': 'pool', 's': 'show', 'id': sys.argv[2]}, 'pool_show')
    else:
        get({'page': 'pool', 's': 'list'}, 'pool_list')
