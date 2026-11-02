"""nsfwr34.com: minors-related tag names, the API list with embedded featured images, multi-tag AND search.
~12 requests, 1.1 s apart."""
import time, os, json
import requests

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out')
UA = ('Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) '
      'Chrome/130.0.0.0 Mobile Safari/537.36')
S = requests.Session()
S.headers.update({'User-Agent': UA, 'Accept-Language': 'en-GB,en;q=0.9'})
API = 'https://nsfwr34.com/wp-json/wp/v2/'


def get(path):
    time.sleep(1.1)
    r = S.get(API + path, timeout=30)
    return r.status_code, r.headers, r.json(), len(r.content)


for w in ('child', 'kid', 'young', 'toddler', 'underage', 'little', 'cub', 'baby', 'petite'):
    st, h, d, _ = get('video_tag?search=%s&per_page=100&_fields=id,name,slug,count' % w)
    print(w, st, [(t['id'], t['slug'], t['count']) for t in d])

st, h, d, n = get('video?per_page=20&_embed=wp:featuredmedia&_fields=id,title,link,playbacks,class_list,_links,_embedded')
with open(os.path.join(OUT, 'api_embed.json'), 'w', encoding='utf-8') as f:
    json.dump(d, f, ensure_ascii=False, indent=1)
fm = d[0].get('_embedded', {}).get('wp:featuredmedia', [{}])[0]
print('embed list', st, n, 'bytes; media keys', list(fm)[:12])
print(' source_url', fm.get('source_url'), list((fm.get('media_details') or {}).get('sizes', {}))[:10])

# two tags, AND (final-fantasy = 202, blowjob = ?) -> look ids up first
st, h, d, _ = get('video_tag?slug=final-fantasy,blowjob,lolicon&_fields=id,slug,count')
ids = {t['slug']: t['id'] for t in d}
print('ids', ids)
st, h, d, _ = get('video?per_page=10&video_tag=%d,%d&tax_relation=AND&_fields=id,title,class_list' % (ids['final-fantasy'], ids['blowjob']))
print('AND', st, h.get('X-WP-Total'), [x['title']['rendered'][:40] for x in d[:3]])
ok = all('video_tag-final-fantasy' in x['class_list'] and 'video_tag-blowjob' in x['class_list'] for x in d)
print('  all have both tags:', ok)
st, h, d, _ = get('video?per_page=10&search=tifa&video_tag_exclude=%d&_fields=id,title' % ids['blowjob'])
print('text+exclude', st, h.get('X-WP-Total'), [x['title']['rendered'][:40] for x in d[:3]])
st, h, d, _ = get('video?per_page=10&page=999&_fields=id')
print('past end', st, d if isinstance(d, dict) else len(d))
