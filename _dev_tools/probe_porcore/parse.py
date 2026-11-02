"""porcore.com proof for the app (spec: _dev_tools/specs/porcore.md).

Parses a latest page (ajax fragment), a search, one video page -> HLS master -> a variant -> a 64 KB Range read of one
segment, and checks 3 thumbnails. About 1 request per 1.1 s, ~10 requests in all. Saves to out/parse_*.
"""
import os, re, time
import requests
from bs4 import BeautifulSoup

BASE = 'https://porcore.com'
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out')
os.makedirs(OUT, exist_ok=True)
UA = ('Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) '
      'Chrome/130.0.0.0 Mobile Safari/537.36')
S = requests.Session()
S.headers.update({'User-Agent': UA, 'Accept-Language': 'en-GB,en;q=0.9'})
HIDDEN = {'teenage-sex'}  # the site's only tag that names an age; loli / shota searches are refused by the site itself


def get(url, **kw):
    time.sleep(1.1)
    r = S.get(url, timeout=30, **kw)
    with open(os.path.join(OUT, 'requests.log'), 'a', encoding='utf-8') as f:
        f.write(time.strftime('%H:%M:%S ') + f'PARSE {r.status_code} {url}\n')
    return r


def items(html):
    soup = BeautifulSoup(html, 'lxml')
    out = []
    for d in soup.select('div.onevideothumb'):
        a = d.select_one('a.clip-link[href*="/video/"]')
        if not a:
            continue
        img = d.select_one('img')
        dur = d.select_one('.floatlefttop')
        views = d.select_one('.rating .floatright')
        rating = d.select_one('.rating .floatleft')
        cover = img['src'] if img else ''
        if cover.startswith('/'):
            cover = BASE + cover
        # the raw picture behind res.php (fallback)
        raw = re.search(r'src=(media/thumbs/\d+/i\d+\.jpg)', cover)
        out.append({
            'id': a.get('data-id'), 'title': a.get('title'), 'url': requests.compat.urljoin(BASE, a['href']),
            'cover': cover, 'alt': BASE + '/' + raw.group(1) if raw else '',
            'duration': dur.get_text(strip=True) if dur else '',
            'views': views.get_text(strip=True).replace(' views', '') if views else '',
            'rating': rating.get_text(strip=True) if rating else '',
        })
    nxt = soup.select_one('#page_nav a[href]')
    return out, nxt['href'] if nxt else None


def video(url):
    r = get(url)
    soup = BeautifulSoup(r.text, 'lxml')
    src = soup.select_one('video#currentvideo source[src]')
    tags = [a['href'].rstrip('/').rsplit('/', 1)[-1] for a in soup.select('a.label.label-default[href*="/show/"]')]
    related, _ = items(str(soup.select_one('.related') or ''))
    return {
        'title': soup.select_one('h1').get_text(strip=True),
        'poster': soup.select_one('video#currentvideo')['poster'],
        'master': src['src'] if src else None, 'type': src.get('type') if src else None,
        'tags': tags, 'related': related,
        'desc': (soup.select_one('#longD') or soup).get_text(' ', strip=True)[:200],
    }


def range_read(url, ref=None):
    h = {'Range': 'bytes=0-65535'}
    if ref:
        h['Referer'] = ref
    time.sleep(1.1)
    r = S.get(url, headers=h, timeout=30, stream=True)
    data = b''
    for c in r.iter_content(8192):
        data += c
        if len(data) >= 65536:
            break
    r.close()
    return r.status_code, r.headers.get('content-type'), r.headers.get('content-range'), data[:65536]


def main():
    # 1. latest, page 2 as the ajax fragment (page 1 = /&ajax&p=1)
    r = get(BASE + '/&ajax&p=2')
    lst, nxt = items(r.text)
    print('LATEST p2:', len(lst), 'items, next =', nxt)
    for it in lst[:3]:
        print('  ', it)
    # 2. search (text + tag slug, sorted by views)
    r = get(BASE + '/show/' + requests.utils.quote('tifa anal-sex') + '?ajax&p=1&sort=mostviewed')
    found, nxt = items(r.text)
    print('SEARCH "tifa anal-sex" by views:', len(found), 'items, next =', nxt)
    for it in found[:3]:
        print('  ', it['id'], it['title'], it['views'])
    # 3. video page -> HLS
    v = video(lst[0]['url'])
    print('VIDEO', v['title'], v['type'], v['master'])
    print('  tags', v['tags'], 'hidden' if set(v['tags']) & HIDDEN else '')
    print('  related', len(v['related']), [x['title'] for x in v['related'][:3]])
    st, ct, cr, data = range_read(v['master'])
    master = data.decode('utf-8', 'replace')
    open(os.path.join(OUT, 'parse_master.m3u8'), 'w', encoding='utf-8').write(master)
    variants = re.findall(r'RESOLUTION=(\d+x\d+)[^\n]*\n(https?://\S+)', master)
    print('  master', st, ct, 'qualities', [q for q, _ in variants])
    best = max(variants, key=lambda x: int(x[0].split('x')[1]))[1]
    st, ct, cr, data = range_read(best)
    seg = re.search(r'^(https?://\S+\.ts)$', data.decode('utf-8', 'replace'), re.M).group(1)
    st, ct, cr, data = range_read(seg)
    print('  segment', st, ct, cr, 'TS sync byte ok' if data[:1] == b'G' else 'NO TS SYNC')
    # 4. thumbnails
    for it in lst[:3]:
        st, ct, cr, data = range_read(it['cover'])
        print('  thumb', st, ct, len(data), 'JPEG' if data[:2] == b'\xff\xd8' else data[:8])


if __name__ == '__main__':
    main()
