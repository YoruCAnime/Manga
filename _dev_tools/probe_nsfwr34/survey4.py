"""nsfwr34.com: AND tag filter syntax, slim embedded featured image. 3 requests."""
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


st, h, d, _ = get('video?per_page=20&video_tag%5Bterms%5D=202,32237&video_tag%5Boperator%5D=AND&_fields=id,title,class_list')
print('AND', st, h.get('X-WP-Total'))
print('  all have both:', all('video_tag-final-fantasy' in x['class_list'] and 'video_tag-blowjob' in x['class_list'] for x in d) if isinstance(d, list) else d)

f = ('id,title,link,playbacks,class_list,_links.wp:featuredmedia,'
     '_embedded.wp:featuredmedia.source_url,_embedded.wp:featuredmedia.media_details.sizes.streamtube-image-medium.source_url')
st, h, d, n = get('video?per_page=20&_embed=wp:featuredmedia&_fields=' + f)
print('slim embed', st, n, 'bytes')
print(json.dumps(d[0].get('_embedded'), indent=1)[:600])
with open(os.path.join(OUT, 'api_slim.json'), 'w', encoding='utf-8') as fo:
    json.dump(d, fo, ensure_ascii=False, indent=1)
