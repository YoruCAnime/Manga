"""E-Hentai structure probe: what the 451 body holds for each page type (read-only GETs, 1.2 s apart).

Samples are kept to the Non-H category (f_cats=767) with minor tags excluded, so nothing involving minors
is fetched. Cookies: only the anonymous display ones (sl=dm_2 extended list, nw=1 skip warnings), no login.
python -X utf8 probe_eh2.py list|gallery|page  (each step reads the previous step's saved file)
"""
import os, sys, time, re, json, requests
from urllib.parse import urlencode
from bs4 import BeautifulSoup

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'out')
os.makedirs(OUT, exist_ok=True)
UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36'
EXCL = '-female:lolicon$ -male:shotacon$ -female:toddlercon$ -male:toddlercon$ -lolicon -shotacon -toddlercon'
S = requests.Session()
S.headers['User-Agent'] = UA


def get(url, name, cookies=None):
    time.sleep(1.2)
    r = S.get(url, timeout=25, cookies=cookies or {}, allow_redirects=False)
    print('==', r.status_code, url, r.headers.get('content-type'), len(r.content), '->', name)
    if r.headers.get('location'):
        print('   location:', r.headers['location'])
    with open(os.path.join(OUT, name), 'wb') as f:
        f.write(r.content)
    return r


step = sys.argv[1] if len(sys.argv) > 1 else 'list'
if step == 'list':
    q = {'f_cats': 767, 'f_search': 'language:english$ ' + EXCL}
    get('https://e-hentai.org/?' + urlencode(q), 'list_compact.html')
    get('https://e-hentai.org/?' + urlencode(q), 'list_extended.html', {'sl': 'dm_2', 'nw': '1'})
    get('https://e-hentai.org/?' + urlencode(q), 'list_thumb.html', {'sl': 'dm_t', 'nw': '1'})
    # cursor paging: next = gid of the last row on the previous page
    soup = BeautifulSoup(open(os.path.join(OUT, 'list_extended.html'), encoding='utf-8').read(), 'html.parser')
    nxt = soup.select_one('#unext')
    print('unext href:', nxt and nxt.get('href'))
    if nxt and nxt.get('href'):
        get(nxt['href'], 'list_next.html', {'sl': 'dm_2', 'nw': '1'})
    # rating + page-count filters (advanced options)
    q2 = dict(q, f_sr='on', f_srdd=4, f_sp='on', f_spf=20, f_spt=200)
    get('https://e-hentai.org/?' + urlencode(q2), 'list_filters.html', {'sl': 'dm_2', 'nw': '1'})
elif step == 'gallery':
    soup = BeautifulSoup(open(os.path.join(OUT, 'list_extended.html'), encoding='utf-8').read(), 'html.parser')
    links = [a['href'] for a in soup.select('.itg a[href*="/g/"]')]
    # pick a gallery with > 20 pages so ?p=1 exists (page count is in the row text)
    pick = None
    for tr in soup.select('table.itg > tr, table.itg > tbody > tr'):
        a = tr.select_one('a[href*="/g/"]')
        m = re.search(r'(\d+) pages', tr.get_text(' '))
        if a and m and int(m.group(1)) > 45:
            pick = a['href']
            break
    pick = sys.argv[2] if len(sys.argv) > 2 else (pick or links[0])
    print('gallery:', pick)
    get(pick, 'gallery.html', {'nw': '1'})
    get(pick + '?p=1', 'gallery_p1.html', {'nw': '1'})
    get(pick, 'gallery_nocookie.html')  # does a content warning show without nw=1?
    json.dump({'gallery': pick}, open(os.path.join(OUT, 'pick.json'), 'w'))
elif step == 'page':
    soup = BeautifulSoup(open(os.path.join(OUT, 'gallery.html'), encoding='utf-8').read(), 'html.parser')
    pages = [a['href'] for a in soup.select('#gdt a[href*="/s/"]')]
    print(len(pages), 'page links; first', pages[:2])
    r = get(pages[0], 'spage1.html', {'nw': '1'})
    s2 = BeautifulSoup(r.text, 'html.parser')
    lf = s2.select_one('#loadfail')
    if lf and "'" in lf.get('onclick', ''):
        nl = lf['onclick'].split("'")[1]
        get(pages[0] + '?nl=' + nl, 'spage1_nl.html', {'nw': '1'})
