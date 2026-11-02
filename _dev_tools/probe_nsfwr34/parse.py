"""nsfwr34.com parser the app copies (spec: _dev_tools/specs/nsfwr34.md). Proves, live and gently (~1 req/s):
  1. a list page (latest = the HTML search page sorted by date) parsed
  2. popular (views, last 7 days) parsed
  3. a text search parsed, and a two-tag AND search through the REST API
  4. one video page -> the stream (HLS master + one variant + a 64 KB Range read of the first segment)
  5. 3 thumbnails answer as images (no Referer)
  6. the tag list (top tags with counts) and the minors filter
Never downloads a whole video: segments / files are read with Range: bytes=0-65535 only.
"""
import re, time, json, os, html as htmllib
import requests
from bs4 import BeautifulSoup

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out')
os.makedirs(OUT, exist_ok=True)
BASE = 'https://nsfwr34.com'
API = BASE + '/wp-json/wp/v2/'
UA = ('Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) '
      'Chrome/130.0.0.0 Mobile Safari/537.36')
S = requests.Session()
S.headers.update({'User-Agent': UA, 'Accept-Language': 'en-GB,en;q=0.9'})

# minors: tag slugs (the site has "lolicon"; two artist names spell out lolis)
HIDDEN = {'loli', 'lolicon', 'shota', 'shotacon', 'toddlercon', 'deathbylolis', 'nekololisama'}
WORDS = re.compile(r'\b(loli|lolicon|lolis|shota|shotacon|shotas|toddler|toddlercon|child|children|kids?|elementary)\b', re.I)


def get(url, **kw):
    time.sleep(1.05)
    return S.get(url, timeout=30, **kw)


def rng(url, ref=None):
    """64 KB at most."""
    time.sleep(1.05)
    h = {'Range': 'bytes=0-65535'}
    if ref:
        h['Referer'] = ref
    r = S.get(url, headers=h, timeout=30, stream=True)
    data = b''
    for c in r.iter_content(8192):
        data += c
        if len(data) >= 65536:
            break
    r.close()
    return r, data[:65536]


def views(t):
    t = t.lower().replace(',', '').replace('views', '').replace('view', '').strip()
    m = re.match(r'([\d.]+)\s*([km]?)', t)
    return t if m else ''


def artist(title):
    """'… [LorgeGucas]' / '… [4K][SemiDraws]' -> the last bracket that is not a quality mark."""
    for b in reversed(re.findall(r'\[([^\]]+)\]', title)):
        if not re.fullmatch(r'(?i)\s*(4k|full|hd|1080p|60fps|update|4k update)\s*', b):
            return b.strip()
    return ''


def item(a):
    """article.type-video -> dict (None when hidden)."""
    cls = a.get('class', [])
    pid = next((c[5:] for c in cls if re.fullmatch(r'post-\d+', c)), '')
    tags = [c[len('video_tag-'):] for c in cls if c.startswith('video_tag-')]
    cats = [c[len('categories-'):] for c in cls if c.startswith('categories-')]
    link = a.select_one('a.post-permalink')
    title = htmllib.unescape(link.get('title', '')) if link else ''
    img = a.select_one('.post-thumbnail img')
    cover = img.get('src', '') if img else ''
    full = re.sub(r'-\d+x\d+(?=\.\w+$)', '', cover)
    prev = a.get('data-thumbnail-image-2', '')  # Bunny preview.webp (moving)
    alts = [u for u in (full, prev.replace('preview.webp', 'thumbnail.jpg') if prev else '', prev) if u and u != cover]
    dur = a.select_one('.video-length')
    v = a.select_one('.post-meta__views')
    d = dict(id=pid, title=title, url=link.get('href', '') if link else '', cover=cover, alts=alts,
             duration=dur.get_text(strip=True) if dur else '', views=views(v.get_text()) if v else '',
             tags=tags, cats=cats, artist=artist(title))
    if any(t in HIDDEN for t in tags) or WORDS.search(title):
        return None
    return d


def parse_list(html_text):
    s = BeautifulSoup(html_text, 'lxml')
    # the main loop only: on video pages the related widget is separate (#posts-widget-*)
    arts = s.select('#main article.type-video, .site-main article.type-video') or s.select('article.type-video')
    items = [x for x in (item(a) for a in arts) if x]
    return items, s.select_one('link[rel=next]') is not None


def search_url(page, text='', orderby='date', date=''):
    p = '' if page <= 1 else 'page/%d/' % page
    q = 's=%s&post_type=video&orderby=%s' % (requests.utils.quote(text), orderby)
    if date:
        q += '&date=' + date
    return '%s/%s?%s' % (BASE, p, q)


def show(name, items, nxt):
    print('%s: %d items, next page = %s' % (name, len(items), nxt))
    for x in items[:4]:
        print('   %s | %s | %s | %s views | %s | tags %s' % (x['id'], x['title'][:50], x['duration'], x['views'], x['artist'], x['tags'][:4]))


def main():
    # 1 latest
    r = get(search_url(1))
    items, nxt = parse_list(r.text)
    show('latest p1', items, nxt)
    assert r.status_code == 200 and len(items) >= 10 and nxt
    latest = items

    # 2 popular, this week
    r = get(search_url(2, orderby='post_view', date='7daysago'))
    items, nxt = parse_list(r.text)
    show('popular 7 days p2', items, nxt)

    # 3a text search
    r = get(search_url(1, text='tifa', orderby='relevance'))
    items, nxt = parse_list(r.text)
    show('search "tifa"', items, nxt)
    assert items

    # 3b two tags AND through the API (ids looked up by slug)
    t = get(API + 'video_tag?slug=final-fantasy,blowjob&_fields=id,slug,name,count').json()
    ids = [x['id'] for x in t]
    r = get(API + 'video?per_page=20&page=1&video_tag%5Bterms%5D=' + ','.join(map(str, ids)) +
            '&video_tag%5Boperator%5D=AND&_embed=wp:featuredmedia&_fields=id,title,link,playbacks,class_list,_links,_embedded')
    total, pages = r.headers.get('X-WP-Total'), r.headers.get('X-WP-TotalPages')
    api_items = r.json()
    print('API tags %s AND: %s found, %s pages' % ([x['slug'] for x in t], total, pages))
    for p in api_items[:3]:
        fm = (p.get('_embedded') or {}).get('wp:featuredmedia') or [{}]
        print('   ', p['id'], htmllib.unescape(p['title']['rendered'])[:50], '| cover', fm[0].get('source_url', '')[-50:])

    # 4 video page -> stream
    v = latest[0]
    r = get(v['url'])
    s = BeautifulSoup(r.text, 'lxml')
    vj = s.select_one('video-js[data-settings]')
    stream, kind = None, None
    if vj:
        st = json.loads(vj['data-settings'])
        src = st['sources'][0]
        stream = src['src']
        kind = 'hls' if 'mpegurl' in src.get('type', '').lower() or '.m3u8' in stream else 'mp4'
        print('video page: poster', st.get('poster'), '| poster2', st.get('poster2'))
    else:
        ifr = s.select_one('.player-embed iframe')
        print('no video-js; iframe =', ifr.get('src') if ifr else None)
    main_art = s.select_one('article#post-%s' % v['id'])
    tags = [a.get_text(strip=True) for a in s.select('.post-tags a[rel=tag]')]
    cats = [a.get_text(strip=True) for a in s.select('.post-categories a[rel=tag]')]
    desc = s.select_one('.post-content .js-read')
    dur = re.search(r'"duration":"P0DT(\d+)H(\d+)M(\d+)S"', r.text)
    rel_box = next((w for w in s.select('div.widget') if 'Related Videos' in (w.select_one('.widget-title').get_text() if w.select_one('.widget-title') else '')), None)
    related = [x for x in (item(a) for a in (rel_box.select('article.type-video') if rel_box else [])) if x]
    print('details:', v['title'][:50], '| stream', kind, stream)
    print('   tags', tags, '| categories', cats, '| duration', dur.groups() if dur else None,
          '| main article classes', len(main_art.get('class', [])) if main_art else None)
    print('   description', (desc.get_text(' ', strip=True) if desc else '')[:120])
    print('   related', len(related), [x['title'][:30] for x in related[:3]])
    assert stream and kind == 'hls'
    r = get(stream)
    print('master', r.status_code, r.headers.get('content-type'))
    variants = [(int(m.group(2)), m.group(3)) for m in re.finditer(r'#EXT-X-STREAM-INF:[^\n]*BANDWIDTH=(\d+)[^\n]*RESOLUTION=\d+x(\d+)[^\n]*\n([^\n]+)', r.text)]
    print('   variants', variants)
    best = sorted(variants)[-1][1]
    vurl = requests.compat.urljoin(stream, best)
    r = get(vurl)
    seg = [l for l in r.text.splitlines() if l and not l.startswith('#')][0]
    surl = requests.compat.urljoin(vurl, seg)
    rr, data = rng(surl)
    print('segment', rr.status_code, rr.headers.get('content-type'), rr.headers.get('content-range'), 'sync byte', data[:1] == b'G')
    assert rr.status_code in (200, 206) and data[:1] == b'G'

    # 5 three thumbnails, no Referer
    for x in latest[1:4]:
        rr, data = rng(x['cover'])
        print('thumb', rr.status_code, rr.headers.get('content-type'), len(data), x['cover'][-60:])
        assert rr.status_code in (200, 206) and rr.headers.get('content-type', '').startswith('image/')

    # 6 tags with counts + categories
    t = get(API + 'video_tag?per_page=100&orderby=count&order=desc&_fields=id,name,slug,count').json()
    shown = [x for x in t if x['slug'] not in HIDDEN and not WORDS.search(x['name'])]
    print('tags top:', [(x['id'], htmllib.unescape(x['name']), x['count']) for x in shown[:12]])
    c = get(API + 'video_category?per_page=100&_fields=id,name,slug,count').json()
    print('categories:', [(x['id'], htmllib.unescape(x['name']), x['count']) for x in c])
    print('ALL OK')


if __name__ == '__main__':
    main()
