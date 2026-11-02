"""nsfwr34.com: stream kinds across the whole catalogue (API pages 5/15/25/35/45 of 51, newest first),
plus whether the Bunny iframe embeds of each library still answer (one embed each). ~10 requests."""
import time, os, json, collections, re
import requests

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out')
UA = ('Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) '
      'Chrome/130.0.0.0 Mobile Safari/537.36')
S = requests.Session()
S.headers.update({'User-Agent': UA, 'Accept-Language': 'en-GB,en;q=0.9'})


def kind(pb):
    if not pb:
        return 'empty'
    m = re.search(r'iframe\.mediadelivery\.net/embed/(\d+)/', pb)
    if m:
        return 'bunny-embed-' + m.group(1)
    if 'rule34video.com/embed' in pb:
        return 'rule34video-embed'
    if pb.startswith('http') and '.m3u8' in pb:
        return 'hls ' + pb.split('/')[2]
    if pb.startswith('http') and re.search(r'\.(mp4|webm)', pb):
        return 'file ' + pb.split('/')[2]
    return 'other ' + pb[:80]


emb = {}
for pg in (5, 15, 25, 35, 45):
    time.sleep(1.1)
    r = S.get('https://nsfwr34.com/wp-json/wp/v2/video?per_page=100&page=%d&_fields=id,date,playbacks' % pg, timeout=30)
    d = r.json()
    c = collections.Counter(kind(p['playbacks']) for p in d)
    print('page', pg, d[0]['date'][:10], '..', d[-1]['date'][:10], dict(c))
    for p in d:
        k = kind(p['playbacks'])
        if k.startswith('bunny-embed') or k.startswith('other'):
            emb.setdefault(k, []).append((p['id'], p['playbacks']))
for k, lst in emb.items():
    if not k.startswith('bunny-embed'):
        print(k, lst[:2])
        continue
    pid, pb = lst[len(lst) // 2]
    url = re.search(r'src="([^"]+)"', pb).group(1).split('?')[0]
    time.sleep(1.1)
    r = S.get(url, headers={'Referer': 'https://nsfwr34.com/'}, timeout=30)
    title = re.search(r'<title>\s*(.*?)\s*</title>', r.text, re.S)
    hosts = sorted(set(re.findall(r'https://([a-z0-9.-]+b-cdn\.net)', r.text)))
    m3u8 = sorted(set(re.findall(r'https://[^"\' ]+\.m3u8[^"\' ]*', r.text)))[:3]
    print(k, pid, r.status_code, 'title=', title.group(1)[:60] if title else None, hosts, m3u8)
    with open(os.path.join(OUT, 'embed_%s.html' % k), 'wb') as f:
        f.write(r.content)
