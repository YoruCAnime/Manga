"""hentaifox: gallery page fields (tag groups, posted date, related block), image servers and headers
(64 KB max per image, with / without Referer), last-page paging. Read-only, ~1 req/s.
python probe_gallery.py [gallery_id ...]"""
import json, re, sys
from bs4 import BeautifulSoup
from fetch import get

B = 'https://hentaifox.com'
MINOR_IDS = {'8', '11', '45', '111', '268', '3321', '3322', '715', '1463', '1472'}
MAGIC = {b'\xff\xd8\xff': 'jpeg', b'\x89PNG': 'png', b'RIFF': 'webp', b'GIF8': 'gif'}


def kind(data):
    return next((v for k, v in MAGIC.items() if data.startswith(k)), None)


def gallery(gid):
    r = get(f'{B}/gallery/{gid}/', out=f'out/gallery_{gid}.html')
    s = BeautifulSoup(r.text, 'lxml')
    info = s.select_one('div.gallery_right div.info')
    slugs = {a['href'].strip('/').split('/')[-1] for a in info.select('ul.tags a.tag_btn')}
    if slugs & {'lolicon', 'shotacon', 'oppai-loli', 'low-lolicon', 'low-shotacon', 'toddlercon', 'kodomo-doushi', 'kodomo-only', 'loli', 'shota'}:
        print('  ', gid, 'carries a minors tag: skipped')
        return None, {}, None
    groups = {}
    for ul in info.select('ul'):
        cls = ' '.join(ul.get('class') or [])
        if cls == 'g_buttons':
            continue
        groups[cls] = [(a['href'], a.find(string=True, recursive=False).strip(), a.select_one('.t_badge').get_text(strip=True)) for a in ul.select('a.tag_btn')]
    print('  ', gid, r.status_code, '| h1', info.select_one('h1').get_text(strip=True)[:60], '| h2', [h.get_text(strip=True)[:60] for h in info.select('h2')])
    for k, v in groups.items():
        print('     ', k, v[:6])
    print('      spans', [x.get_text(strip=True) for x in info.select('span.i_text.pages')])
    print('      inputs', {i.get('id'): i.get('value') for i in s.select('input[type=hidden]') if i.get('id') in ('load_id', 'load_dir', 'load_pages', 'gallery_id', 'load_server')})
    m = re.search(r"g_th = \$\.parseJSON\('(.*?)'\)", r.text)
    th = json.loads(m.group(1)) if m else {}
    print('      g_th', len(th), list(th.items())[:3])
    rel = s.select('div.related_galleries div.thumb, div.related div.thumb')
    print('      related block:', [d.get('class') for d in s.select('div[class*=related]')][:4], len(rel))
    cover = info.find_parent('div', class_='gallery_top').select_one('div.cover img')
    return s, th, cover and cover.get('src')


if __name__ == '__main__':
    ids = sys.argv[1:] or ['176553', '121051', '30000']
    for gid in ids:
        s, th, cover = gallery(gid)
        if s is None:
            continue
        load_id = s.select_one('#load_id')['value']
        load_dir = s.select_one('#load_dir')['value']
        ext = {'j': 'jpg', 'p': 'png', 'b': 'bmp', 'g': 'gif', 'w': 'webp'}
        servers = ['i3.hentaifox.com'] if int(gid) > 140236 else ['i.hentaifox.com', 'i2.hentaifox.com', 'i3.hentaifox.com']
        for host in servers:
            n = '1'
            url = f'https://{host}/{load_dir}/{load_id}/{n}.{ext[th[n].split(",")[0]]}'
            for ref in (None, 'https://hentaifox.com/', 'https://example.com/'):
                r, data = get(url, image=True, referer=ref)
                print('        ->', kind(data))
        r, data = get(cover, image=True)
        print('        cover ->', kind(data))
    # last page of a search: is there still a Next link?
    r = get(B + '/search/?q=glasses&page=964')
    s = BeautifulSoup(r.text, 'lxml')
    print('   last page pagination:', [(li.get('class'), li.a.get('href'), li.get_text(strip=True)) for li in s.select('ul.pagination li')][-3:])
    r = get(B + '/search/?q=glasses&page=963')
    s = BeautifulSoup(r.text, 'lxml')
    print('   2nd last pagination:', [(li.get('class'), li.a.get('href'), li.get_text(strip=True)) for li in s.select('ul.pagination li')][-3:])
