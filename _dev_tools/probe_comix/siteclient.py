"""Headless: use comix's OWN axios instance (apicore export with .interceptors) from page JS:
its interceptors add the ?_= token and decrypt {"e":..} -> plain JSON with normal field names.
Tests: chapters page 1+2, chapter detail (images), manga list with keyword + genre include/exclude + sort,
author filter, tags search.  -> out/site_<name>.json   (one call every ~1.2 s)"""
import json, pathlib, time
from playwright.sync_api import sync_playwright

OUT = pathlib.Path(__file__).parent / 'out'
SETUP = r"""
async () => {
  if (window.__cx) return 'ok';
  const main = [...document.querySelectorAll('script[src]')].map(s => s.src).find(u => /\/main-[^/]+\.js$/.test(u));
  const src = await (await fetch(main)).text();
  const api = src.match(/from"\.\/(apicore-[^"]+\.js)"/)[1];
  const A = await import(main.slice(0, main.lastIndexOf('/') + 1) + api);
  const ax = Object.values(A).find(v => v && v.interceptors && typeof v.get === 'function');
  if (!ax) return 'no axios';
  window.__cx = ax;
  return 'ok';
}
"""
GET = r"""
async ([path, params]) => {
  const t0 = performance.now();
  try {
    const r = await window.__cx.get(path, {params});
    return {ms: Math.round(performance.now() - t0), status: r.status, url: r.request && r.request.responseURL, data: r.data};
  } catch (e) {
    return {err: String(e), status: e.response && e.response.status, body: e.response && JSON.stringify(e.response.data).slice(0, 500)};
  }
}
"""
CALLS = [
    ('chapters_p1', '/api/v1/manga/50xvk/chapters', {'page': 1, 'limit': 20, 'order': {'number': 'desc'}}),
    ('chapters_p2', '/api/v1/manga/50xvk/chapters', {'page': 2, 'limit': 20, 'order': {'number': 'desc'}}),
    ('chapters_big', '/api/v1/manga/50xvk/chapters', {'page': 1, 'limit': 100, 'order': {'number': 'asc'}}),
    ('chapter', '/api/v1/chapters/11576932', {}),
    ('search', '/api/v1/manga', {'keyword': 'dragon', 'genres_in': [6], 'genres_ex': [23], 'order': {'relevance': 'desc'}, 'page': 1, 'limit': 5,
                          'content_rating': ['safe', 'suggestive']}),
    ('popular', '/api/v1/manga', {'order': {'views_30d': 'desc'}, 'page': 2, 'limit': 5, 'content_rating': ['safe', 'suggestive']}),
    ('by_author', '/api/v1/manga', {'authors': [108813], 'page': 1, 'limit': 5}),
    ('top_trending', '/api/v1/manga/top', {'type': 'trending', 'days': 7, 'limit': 5, 'content_rating': ['safe', 'suggestive']}),
    ('recommended', '/api/v1/manga/50xvk/recommended', {'page': 1, 'content_rating': ['safe', 'suggestive']}),
    ('detail', '/api/v1/manga/50xvk', {}),
    ('tags', '/api/v1/tags/search', {'type': 'author', 'q': 'oda', 'limit': 5}),
]
with sync_playwright() as pw:
    br = pw.chromium.launch(headless=True)
    ctx = br.new_context(viewport={'width': 1280, 'height': 900})
    pg = ctx.new_page()
    seen = []
    pg.on('request', lambda r: seen.append(r.url) if '/api/' in r.url else None)
    pg.goto('https://comix.to/browse', wait_until='domcontentloaded', timeout=60000)
    pg.wait_for_timeout(5000)
    print('setup:', pg.evaluate(SETUP))
    for name, path, params in CALLS:
        time.sleep(1.2)
        n0 = len(seen)
        r = pg.evaluate(GET, [path, params])
        (OUT / f'site_{name}.json').write_text(json.dumps(r, indent=1, ensure_ascii=False), encoding='utf-8')
        s = json.dumps(r.get('data', r), ensure_ascii=False)
        print(f'== {name}: status={r.get("status")} ms={r.get("ms")} err={r.get("err")}')
        print('   sent:', [u[:200] for u in seen[n0:]])
        print('   ', s[:500])
    br.close()
