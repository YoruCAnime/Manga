"""Danbooru probe 4: page size cap, id: batch lookup for pool covers, pool sort orders, pool by name.
Read-only GETs, 1.2 s apart, rating:g / neutral pools only."""
import time, json, requests

B = 'https://danbooru.donmai.us'
S = requests.Session()
S.headers['User-Agent'] = 'MangaCracker/1.0'


def get(path, p):
    time.sleep(1.2)
    r = S.get(B + path, params=p, timeout=30)
    try:
        j = r.json()
        s = ('[%d] ' % len(j) + json.dumps(j[:3])) if isinstance(j, list) else json.dumps(j)
    except Exception:
        s = r.text
    print('==', r.status_code, r.url[len(B):], '\n   ', s[:400])


get('/posts.json', {'limit': 200, 'tags': 'rating:g scenery', 'only': 'id'})
get('/posts.json', {'limit': 201, 'tags': 'rating:g scenery', 'only': 'id'})
get('/posts.json', {'limit': 20, 'tags': 'id:1042604,1125948,1042607 rating:g', 'only': 'id,rating,preview_file_url'})
for order in ('name', 'created_at', 'updated_at', 'post_count'):
    get('/pools.json', {'search[category]': 'series', 'search[name_contains]': 'scenery', 'search[order]': order,
                        'limit': 3, 'only': 'id,name,post_count,updated_at'})
get('/pools.json', {'search[name_matches]': 'To_Aru_Majutsu_no_Index_-_A_Certain_Ordinary_Scenery_of_Colorful_Summer',
                    'only': 'id,name'})
get('/pools.json', {'search[id]': '4606,5261', 'only': 'id,name,post_count'})
