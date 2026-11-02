"""nsfwr34.com: what the REST API's `playbacks` field holds across many videos (newest + oldest pages),
and the tag / category lists with counts. ~6 requests, 1.1 s apart."""
import time, os, json, collections, re
import requests

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out')
UA = ('Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) '
      'Chrome/130.0.0.0 Mobile Safari/537.36')
S = requests.Session()
S.headers.update({'User-Agent': UA, 'Accept-Language': 'en-GB,en;q=0.9'})
API = 'https://nsfwr34.com/wp-json/wp/v2/'


def get(path, name):
    time.sleep(1.1)
    r = S.get(API + path, timeout=30)
    with open(os.path.join(OUT, name), 'wb') as f:
        f.write(r.content)
    print(r.status_code, path, 'total', r.headers.get('X-WP-Total'), 'pages', r.headers.get('X-WP-TotalPages'))
    return r.json(), r


kinds = collections.Counter()
examples = {}
for name, q in (('survey_new.json', 'video?per_page=100&_fields=id,link,playbacks,featured_media,video_category'),
                ('survey_old.json', 'video?per_page=100&order=asc&_fields=id,link,playbacks,featured_media,video_category')):
    d, _ = get(q, name)
    for p in d:
        pb = p.get('playbacks')
        if isinstance(pb, list):
            k = 'list:' + ','.join(sorted({re.sub(r'https?://([^/]+)/.*', r'\1', str(x)) for x in pb}))
        elif not pb:
            k = 'empty'
        else:
            host = re.sub(r'https?://([^/]+)/.*', r'\1', pb)
            k = host + ' ' + pb.rsplit('.', 1)[-1][:8]
        kinds[k] += 1
        examples.setdefault(k, (p['id'], p['link'], pb))
for k, n in kinds.most_common():
    print(n, k, examples[k])

d, r = get('video_tag?per_page=100&orderby=count&order=desc&_fields=id,name,slug,count', 'tags_top.json')
print([(t['slug'], t['count']) for t in d[:15]])
d, r = get('video_category?per_page=100&_fields=id,name,slug,count,parent', 'categories.json')
print([(t['id'], t['slug'], t['count']) for t in d])
d, r = get('video_tag?search=loli&per_page=100&_fields=id,name,slug,count', 'tags_loli.json')
print('loli-like:', [(t['slug'], t['count']) for t in d])
d, r = get('video_tag?search=shota&per_page=100&_fields=id,name,slug,count', 'tags_shota.json')
print('shota-like:', [(t['slug'], t['count']) for t in d])
