"""Headless: reach comix's own TanStack QueryClient through the React fiber tree, list its queries
(decrypted data), and call a query's own queryFn with a changed queryKey (site signs + decrypts for us).
-> out/rq_<page>.json"""
import json, time, pathlib, sys
from playwright.sync_api import sync_playwright

OUT = pathlib.Path(__file__).parent / 'out'
TITLE = 'https://comix.to/title/50xvk-i-married-the-dragon-i-killed'
CHAP = TITLE + '/11576932-chapter-20'
BROWSE = 'https://comix.to/browse'

FIND_QC = r"""
() => {
  if (window.__qc) return true;
  const seen = new Set();
  const els = [document.getElementById('root'), document.getElementById('app'), ...document.querySelectorAll('body > div, body > main, [data-reactroot]')].filter(Boolean);
  for (const el of document.querySelectorAll('*')) {
    const k = Object.keys(el).find(k => k.startsWith('__reactContainer$') || k.startsWith('__reactFiber$'));
    if (!k) continue;
    let f = el[k];
    if (f && f.stateNode && f.stateNode.current) f = f.stateNode.current;
    // walk up to the root, then down breadth-first
    while (f && f.return) f = f.return;
    const q = [f]; let n = 0;
    while (q.length && n < 20000) {
      const x = q.shift(); n++;
      if (!x || seen.has(x)) continue; seen.add(x);
      const p = x.memoizedProps;
      if (p && p.client && typeof p.client.getQueryCache === 'function') { window.__qc = p.client; return true; }
      if (x.child) q.push(x.child);
      if (x.sibling) q.push(x.sibling);
    }
    break;
  }
  return false;
}
"""
LIST = r"""
() => window.__qc.getQueryCache().getAll().map(q => ({
  key: q.queryKey, status: q.state.status, hasFn: typeof q.options.queryFn === 'function',
  data: JSON.stringify(q.state.data === undefined ? null : q.state.data).slice(0, 60000)
}))
"""
CALL = r"""
async ([match, newKey]) => {
  const q = window.__qc.getQueryCache().getAll().find(q => JSON.stringify(q.queryKey).includes(match));
  if (!q) return {err: 'no query ' + match};
  try {
    const ac = new AbortController();
    const d = await q.options.queryFn({queryKey: newKey, signal: ac.signal, meta: q.options.meta, pageParam: undefined, client: window.__qc});
    return {fnSrc: String(q.options.queryFn).slice(0, 600), data: JSON.stringify(d).slice(0, 60000)};
  } catch (e) { return {err: String(e), fnSrc: String(q.options.queryFn).slice(0, 600)}; }
}
"""
which = sys.argv[1:] or ['title', 'chapter', 'browse']
with sync_playwright() as pw:
    br = pw.chromium.launch(headless=True)
    ctx = br.new_context(viewport={'width': 1280, 'height': 900})
    pg = ctx.new_page()
    for name, u in (('title', TITLE), ('chapter', CHAP), ('browse', BROWSE)):
        if name not in which:
            continue
        pg.goto(u, wait_until='domcontentloaded', timeout=60000)
        pg.wait_for_timeout(7000)
        ok = pg.evaluate(FIND_QC)
        print(name, 'queryClient found:', ok)
        if not ok:
            continue
        lst = pg.evaluate(LIST)
        out = {'queries': lst}
        for q in lst:
            print('  KEY', json.dumps(q['key'])[:200], q['status'], 'fn' if q['hasFn'] else '', len(q['data']))
        if name == 'title':
            k = next((q['key'] for q in lst if 'chapters' in json.dumps(q['key'])), None)
            if k:
                k2 = json.loads(json.dumps(k).replace('"page":1', '"page":2'))
                print('  call chapters with', k2)
                out['call'] = pg.evaluate(CALL, ['chapters', k2])
                print('  ->', json.dumps(out['call'])[:600])
        if name == 'browse':
            k = next((q['key'] for q in lst if '"list"' in json.dumps(q['key'])), None)
            if k:
                k2 = json.loads(json.dumps(k))
                p = k2[-1] if isinstance(k2[-1], dict) else {}
                p.update({'keyword': 'dragon', 'genres_in': [6], 'genres_ex': [23], 'order': {'relevance': 'desc'}, 'page': 1})
                print('  call list with', k2)
                out['call'] = pg.evaluate(CALL, ['"list"', k2])
                print('  ->', json.dumps(out['call'])[:600])
        (OUT / f'rq_{name}.json').write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding='utf-8')
        time.sleep(1)
    br.close()
