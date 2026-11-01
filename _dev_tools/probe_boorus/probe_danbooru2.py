"""Danbooru probe 2: which tags count toward the anonymous 2-tag limit, pools as chapters, 'only=' trimming.
Read-only GETs, 1.2 s apart, UA MangaCracker/1.0, neutral rating:g samples only.
python -X utf8 probe_danbooru2.py
"""
import os, time, json, requests

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out')
B = 'https://danbooru.donmai.us'
S = requests.Session()
S.headers['User-Agent'] = 'MangaCracker/1.0'


def get(path, params=None, save=None, show=200):
    time.sleep(1.2)
    r = S.get(B + path, params=params, timeout=25)
    body = r.text
    try:
        j = r.json()
        if isinstance(j, list):
            body = '[%d items] ' % len(j) + json.dumps([{k: x.get(k) for k in ('id', 'rating', 'name', 'post_count')
                                                          if k in x} for x in j[:6]])
        elif isinstance(j, dict) and 'message' in j:
            body = j['message']
    except Exception:
        pass
    print('==', r.status_code, r.url[len(B):], '\n   ', body[:show])
    if save:
        open(os.path.join(OUT, save), 'wb').write(r.content)
    return r


# what counts toward the 2-tag limit for anonymous users
for tags in ['scenery -cat rating:g', 'scenery sky -cat', 'scenery rating:g order:score status:active',
             'scenery rating:g -rating:e', 'scenery is:sfw', 'scenery order:rank', 'scenery age:<1w order:score',
             'scenery ~sky ~cloud', 'scenery sky*', 'scenery rating:g,s', 'scenery limit:5', 'scenery score:>50 order:random',
             'scenery cloud date:2026-10-30 rating:g']:
    get('/posts.json', {'limit': 1, 'tags': tags, 'only': 'id,rating'})
# pools as chapters
get('/pools/4606.json', save='danbooru_pool_one.json')
get('/posts.json', {'tags': 'ordpool:4606', 'limit': 3, 'only': 'id,rating'})
get('/posts.json', {'tags': 'pool:4606 rating:g', 'limit': 3, 'only': 'id,rating'})
get('/posts.json', {'tags': 'id:1042604,1042607,1042609', 'limit': 3, 'only': 'id,rating'})
get('/posts.json', {'tags': 'ordpool:4606', 'limit': 2, 'page': 2, 'only': 'id'})
get('/pools.json', {'search[name_contains]': 'scenery', 'search[category]': 'series', 'search[order]': 'updated_at',
                    'limit': 2, 'only': 'id,name,post_count,post_ids'})
get('/pools.json', {'search[post_tags_match]': 'scenery rating:g', 'limit': 2, 'only': 'id,name,post_count'})
# artist page as a 'manga': artist + their posts
get('/artists.json', {'search[name]': 'dairi', 'only': 'id,name,other_names,group_name,is_banned,urls'},
    save='danbooru_artist_urls.json', show=800)
# post 'only' trimming + a sample with variants
get('/posts.json', {'limit': 1, 'tags': 'scenery rating:g',
                    'only': 'id,rating,score,tag_string_artist,file_url,large_file_url,preview_file_url,media_asset[variants]'},
    show=900)
# wiki / tag info + autocomplete for artists
get('/autocomplete.json', {'search[query]': 'dair', 'search[type]': 'tag_query', 'limit': 3, 'only': 'type,label,value,category,post_count'}, show=500)
get('/autocomplete.json', {'search[query]': 'dair', 'search[type]': 'artist', 'limit': 3}, show=500)
get('/autocomplete.json', {'search[query]': 'touhou', 'search[type]': 'pool', 'limit': 3}, show=500)
