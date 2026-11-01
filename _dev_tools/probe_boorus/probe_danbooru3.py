"""Danbooru probe 3: exactly which metatags are free of the 2-tag limit (two normal tags + one metatag each),
plus raw bodies of a few small replies. Read-only GETs, 1.2 s apart."""
import time, requests

B = 'https://danbooru.donmai.us'
S = requests.Session()
S.headers['User-Agent'] = 'MangaCracker/1.0'
for meta in ['rating:g', '-rating:e', 'order:score', 'status:active', 'limit:5', 'date:2026-10-30', 'is:sfw',
             'age:<1y', 'score:>10', 'user:albert', 'pool:any', 'parent:none', 'filetype:png', 'width:>1000']:
    time.sleep(1.2)
    r = S.get(B + '/posts.json', params={'limit': 1, 'tags': 'scenery cloud ' + meta, 'only': 'id'})
    print(r.status_code, 'FREE' if r.status_code == 200 else 'counts', meta, r.text[:80])
for path, p in [('/autocomplete.json', {'search[query]': 'dair', 'search[type]': 'artist', 'limit': 2}),
                ('/artists.json', {'search[name]': 'dairi', 'only': 'id,name,other_names,group_name,is_banned,urls'}),
                ('/posts.json', {'limit': 1, 'tags': 'scenery rating:g',
                                 'only': 'id,rating,file_url,large_file_url,preview_file_url,media_asset[variants]'}),
                ('/pools/4606.json', {'only': 'id,name,post_ids,post_count,category,description,is_deleted'})]:
    time.sleep(1.2)
    r = S.get(B + path, params=p)
    print('==', r.status_code, r.url[len(B):], dict((k, v) for k, v in r.headers.items() if k.lower().startswith('x-rate')))
    print(r.text[:1200])
