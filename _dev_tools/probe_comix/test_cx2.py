"""Headless: (1) /api/v1/manga/top through the site (days 7 / follows), (2) content_rating default, (3) tag ids in
genres_ex (minors tags) change the totals. Prints meta / counts only (no titles for the exclusion test)."""
import json, pathlib, time
from playwright.sync_api import sync_playwright

HERE = pathlib.Path(__file__).parent
OUT = HERE / 'out'
LIB = (HERE / 'cxlib.js').read_text(encoding='utf-8')
TOP = r"""
async (p) => {
  const L = window.__cxlib; const f = await L.fn('manga,top');
  const c = await L.capture(() => f(p));
  return c.bodies.map(b => ({url: b.url, status: b.status, text: b.text.slice(0, 3000)}));
}
"""
with sync_playwright() as pw:
    br = pw.chromium.launch(headless=True)
    pg = br.new_page()
    pg.goto('https://comix.to/', wait_until='domcontentloaded', timeout=60000); pg.wait_for_timeout(6000)
    pg.evaluate(LIB)
    m = pg.evaluate('window.__cxlib.learn()')
    print('home methods:', {k: v for k, v in m.items() if not k.startswith('me,')})
    print('home queryFns:', pg.evaluate("window.__cxlib.qc().getQueryCache().getAll().filter(q=>q.queryKey[0]==='manga').map(q=>JSON.stringify(q.queryKey).slice(0,140)+' => '+String(q.options.queryFn).slice(0,60))"))
    for p in ({'type': 'trending', 'days': 7, 'limit': 3, 'content_rating': ['safe', 'suggestive']},
              {'type': 'follows', 'days': 30, 'limit': 3, 'content_rating': ['safe', 'suggestive']}):
        time.sleep(1.5)
        try:
            r = pg.evaluate(TOP, p)
        except Exception as e:
            r = str(e)[:300]
        print('TOP', p, '->', json.dumps(r, ensure_ascii=False)[:700])
    base = {'page': 1, 'limit': 1}
    for label, p in (('no content_rating', {}),
                     ('all 4 ratings', {'content_rating': ['safe', 'suggestive', 'erotica', 'pornographic']}),
                     ('safe+suggestive', {'content_rating': ['safe', 'suggestive']}),
                     ('all 4 minus minors tags', {'content_rating': ['safe', 'suggestive', 'erotica', 'pornographic'],
                                                  'genres_ex': [101462, 113446, 100753, 113898, 107660]}),
                     ('comma string rating', {'content_rating': 'safe,suggestive'})):
        time.sleep(1.5)
        try:
            r = pg.evaluate('(p) => window.__cxlib.list(p)', {**base, **p})
            print(f'{label}: total={r["result"]["meta"]["total"]}  url={r["_url"][:220]}')
        except Exception as e:
            print(label, 'ERR', str(e)[:300])
    br.close()
