"""Headless: inspect comix apicore export used as `nn` by queryFns (main imports `f as nn`), and try
calling nn.qL(hid, params) (chapters) / nn.Xi(params) (list) with our own params.  -> out/apiobj.json"""
import json, pathlib, time
from playwright.sync_api import sync_playwright

OUT = pathlib.Path(__file__).parent / 'out'
JS = r"""
async () => {
  const main = [...document.querySelectorAll('script[src]')].map(s => s.src).find(u => /\/main-[^/]+\.js$/.test(u));
  const src = await (await fetch(main)).text();
  const api = src.match(/from"\.\/(apicore-[^"]+\.js)"/)[1];
  const A = await import(main.slice(0, main.lastIndexOf('/') + 1) + api);
  // which local name does main give each export?  e.g. "f as nn"
  const imp = (src.match(/import\{([^}]*)\}from"\.\/apicore-[^"]+"/) || [])[1] || '';
  const out = {imp, exp: {}};
  for (const k of Object.keys(A)) {
    const v = A[k];
    if (!v || typeof v !== 'object') continue;
    const names = Object.getOwnPropertyNames(v);
    const proto = Object.getPrototypeOf(v);
    const pn = proto && proto !== Object.prototype ? Object.getOwnPropertyNames(proto) : [];
    out.exp[k] = {own: names.slice(0, 80), proto: pn.slice(0, 80),
                  sample: names.slice(0, 12).map(n => { try { const x = v[n]; return n + '=' + (typeof x === 'function' ? 'fn/' + x.length + ':' + String(x).slice(0, 100) : JSON.stringify(x).slice(0, 100)); } catch (e) { return n + '=?'; } })};
  }
  window.__A = A;
  return out;
}
"""
TRY = r"""
async ([exp, fn, args]) => {
  try { const d = await window.__A[exp][fn](...args); return {ok: true, data: JSON.stringify(d).slice(0, 4000)}; }
  catch (e) { return {ok: false, err: String(e).slice(0, 300)}; }
}
"""
with sync_playwright() as pw:
    br = pw.chromium.launch(headless=True)
    pg = br.new_page()
    seen = []
    pg.on('request', lambda r: seen.append(r.url) if '/api/v1/' in r.url else None)
    pg.goto('https://comix.to/browse', wait_until='domcontentloaded', timeout=60000)
    pg.wait_for_timeout(5000)
    res = pg.evaluate(JS)
    print('main imports:', res['imp'][:400])
    for k, v in res['exp'].items():
        if v['own'] or v['proto']:
            print(k, 'own', v['own'][:30], 'proto', v['proto'][:30])
            for s in v['sample']:
                print('     ', s)
    nn = 'f'
    tries = [(nn, 'qL', ['50xvk', {'page': 2, 'limit': 20, 'order': {'number': 'desc'}}]),
             (nn, 'Xi', [{'keyword': 'dragon', 'genres_in': [6], 'genres_ex': [23], 'order': {'relevance': 'desc'}, 'page': 1, 'limit': 3}])]
    for t in tries:
        time.sleep(1.2)
        n0 = len(seen)
        r = pg.evaluate(TRY, list(t))
        print('TRY', t[1], r.get('ok'), r.get('err'), '| sent', seen[n0:])
        print('    ', (r.get('data') or '')[:700])
    (OUT / 'apiobj.json').write_text(json.dumps(res, indent=1, ensure_ascii=False), encoding='utf-8')
    br.close()
