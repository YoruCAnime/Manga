"""Headless end-to-end test of cxlib.js (what the Android WebView would do). ~1 API call per 1.5 s.
-> out/cx_<name>.json and a summary."""
import json, pathlib, time
from playwright.sync_api import sync_playwright

HERE = pathlib.Path(__file__).parent
OUT = HERE / 'out'
LIB = (HERE / 'cxlib.js').read_text(encoding='utf-8')
TITLE = 'https://comix.to/title/50xvk-i-married-the-dragon-i-killed'
CHAP = TITLE + '/11576932-chapter-20'
CR = ['safe', 'suggestive']


def ev(pg, js, arg=None):
    time.sleep(1.5)
    try:
        return pg.evaluate(js, arg)
    except Exception as e:
        return {'error': str(e)[:400]}


def save(name, r):
    (OUT / f'cx_{name}.json').write_text(json.dumps(r, indent=1, ensure_ascii=False), encoding='utf-8')


with sync_playwright() as pw:
    br = pw.chromium.launch(headless=True)
    ctx = br.new_context(viewport={'width': 1280, 'height': 900})
    pg = ctx.new_page()
    # reader page first: learn chapters,detail
    pg.goto(CHAP, wait_until='domcontentloaded', timeout=60000); pg.wait_for_timeout(6000)
    pg.evaluate(LIB)
    learnt = pg.evaluate('window.__cxlib.learn()')
    build = pg.evaluate('window.__cxlib.api().then(a => a.build)')
    print('build', build); print('learnt on reader:', learnt)
    r = ev(pg, '(id) => window.__cxlib.chapter(id)', 11576932); save('chapter', r)
    print('chapter pages:', len(r.get('pages', [])), r.get('status'), (r.get('url') or '')[:120], r.get('pages', [{}])[:1], r.get('error'))

    pg.goto(TITLE, wait_until='domcontentloaded', timeout=60000); pg.wait_for_timeout(6000)
    pg.evaluate(LIB)
    pg.evaluate('(m) => window.__cxlib.teach(m)', learnt)
    print('learnt on title:', pg.evaluate('window.__cxlib.learn()'))
    tests = [
        ('chapters_asc100', '([h,p]) => window.__cxlib.chapters(h,p)', ['50xvk', {'page': 1, 'limit': 100, 'order': {'number': 'asc'}}]),
        ('chapters_p2', '([h,p]) => window.__cxlib.chapters(h,p)', ['50xvk', {'page': 2, 'limit': 20, 'order': {'number': 'desc'}}]),
        ('chapter_other', '(id) => window.__cxlib.chapter(id)', 11590440),
        ('search_filters', '(p) => window.__cxlib.list(p)', {'keyword': 'dragon', 'genres_in': [6, 12], 'genres_ex': [23], 'genres_mode': 'and',
                                                              'order': {'relevance': 'desc'}, 'page': 1, 'limit': 5, 'content_rating': CR}),
        ('popular_views30', '(p) => window.__cxlib.list(p)', {'order': {'views_30d': 'desc'}, 'page': 2, 'limit': 5, 'content_rating': CR}),
        ('latest', '(p) => window.__cxlib.list(p)', {'order': {'chapter_updated_at': 'desc'}, 'page': 1, 'limit': 5, 'content_rating': CR}),
        ('by_author', '(p) => window.__cxlib.list(p)', {'authors': [108813], 'page': 1, 'limit': 5}),
        ('types_status', '(p) => window.__cxlib.list(p)', {'types': ['manhwa'], 'statuses': ['finished'], 'order': {'follows_total': 'desc'}, 'page': 1, 'limit': 3, 'content_rating': CR}),
    ]
    for name, js, arg in tests:
        r = ev(pg, js, arg); save(name, r)
        if 'items' in r and isinstance(r['items'], list):
            print(f'== {name}: {len(r["items"])} chapters, status {r.get("status")}, url {(r.get("url") or "")[:150]}')
            if r['items']:
                print('    first', json.dumps(r['items'][0], ensure_ascii=False)[:300]); print('    last ', json.dumps(r['items'][-1], ensure_ascii=False)[:200])
        elif 'pages' in r:
            print(f'== {name}: {len(r["pages"])} pages {r.get("status")}', r['pages'][:1])
        elif 'result' in r:
            res = r['result']
            print(f'== {name}: {len(res.get("items", []))} items meta={res.get("meta")} url={r.get("_url", "")[:200]}')
            for it in res.get('items', [])[:5]:
                print('    ', it.get('hid'), '|', it.get('title')[:50], '|', it.get('type'), it.get('status'), it.get('contentRating'))
        else:
            print(f'== {name}: {json.dumps(r)[:400]}')
    br.close()
