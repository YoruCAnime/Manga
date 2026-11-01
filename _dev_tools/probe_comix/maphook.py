"""Headless test of the 'site pipeline' recipe for comix's encrypted endpoints, robust to name mangling:
 1. find the QueryClient (React fiber), find the query whose key is ["manga","chapters",hid,...] /
    ["chapters","detail",id], read the api method name out of its queryFn source ("()=>nn.qL(a,A)"),
 2. resolve `nn` -> apicore export letter from main.js's import line,
 3. call it with our own params while Array.prototype.map is wrapped: the site's mapper maps the PLAIN decrypted
    arrays (normal field names) -> we keep those.
-> out/maphook_*.json"""
import json, pathlib, time, sys
from playwright.sync_api import sync_playwright

OUT = pathlib.Path(__file__).parent / 'out'
TITLE = 'https://comix.to/title/50xvk-i-married-the-dragon-i-killed'
CHAP = TITLE + '/11576932-chapter-20'
LIB = r"""
window.__cxlib = {
  async api() {
    if (this._api) return this._api;
    const main = [...document.querySelectorAll('script[src]')].map(s => s.src).find(u => /\/main-[^/]+\.js$/.test(u));
    const src = await (await fetch(main)).text();
    const m = src.match(/from"\.\/(apicore-[^"]+\.js)"/);
    const apiUrl = main.slice(0, main.lastIndexOf('/') + 1) + m[1];
    const asrc = await (await fetch(apiUrl)).text();
    const ex = asrc.match(/export\{([^}]*)\};?\s*$/)[1];
    const alias = {};  // apicore local name (= name the chunks use) -> export letter
    for (const p of ex.split(',')) { const [l, e] = p.split(' as '); alias[l.trim()] = (e || l).trim(); }
    const A = await import(apiUrl);
    return (this._api = {A, alias});
  },
  qc() {
    if (this._qc) return this._qc;
    for (const el of document.querySelectorAll('*')) {
      const k = Object.keys(el).find(k => k.startsWith('__reactContainer$') || k.startsWith('__reactFiber$'));
      if (!k) continue;
      let f = el[k]; if (f && f.stateNode && f.stateNode.current) f = f.stateNode.current;
      while (f && f.return) f = f.return;
      const q = [f], seen = new Set();
      while (q.length) { const x = q.shift(); if (!x || seen.has(x)) continue; seen.add(x);
        const p = x.memoizedProps; if (p && p.client && p.client.getQueryCache) return (this._qc = p.client);
        if (x.child) q.push(x.child); if (x.sibling) q.push(x.sibling); }
      break;
    }
    return null;
  },
  // ["manga","chapters"] -> {obj, method}
  async fnFor(prefix) {
    const qc = this.qc(); if (!qc) throw new Error('no QueryClient');
    const q = qc.getQueryCache().getAll().find(q => JSON.stringify(q.queryKey).startsWith(JSON.stringify(prefix).slice(0, -1)));
    if (!q) throw new Error('no query ' + JSON.stringify(prefix));
    const src = String(q.options.queryFn);
    const mm = src.match(/([\w$]+)((?:\.[\w$]+)+)\(/);
    const {A, alias} = await this.api();
    const path = mm[2].slice(1).split('.');
    let obj = A[alias[mm[1]]];
    for (const p of path.slice(0, -1)) obj = obj[p];
    return {obj, method: path[path.length - 1], src};
  },
  async call(prefix, args) {
    const {obj, method, src} = await this.fnFor(prefix);
    const caught = [];
    const M = Array.prototype.map;
    Array.prototype.map = function (...a) {
      try { if (this.length && this[0] && typeof this[0] === 'object' && !Array.isArray(this[0])) caught.push(this); } catch (e) {}
      return M.apply(this, a);
    };
    let out;
    try { out = await obj[method](...args); } finally { Array.prototype.map = M; }
    return {src, method, mangled: out, plain: caught};
  },
};
"""
RUN = r"""
async ([prefix, args]) => {
  const r = await window.__cxlib.call(prefix, args);
  return {src: r.src, method: r.method, mangledHead: JSON.stringify(r.mangled).slice(0, 300),
          plain: r.plain.map(a => ({n: a.length, keys: Object.keys(a[0]), first: JSON.stringify(a[0]).slice(0, 1500), all: JSON.stringify(a).slice(0, 30000)}))};
}
"""
with sync_playwright() as pw:
    br = pw.chromium.launch(headless=True)
    ctx = br.new_context(viewport={'width': 1280, 'height': 900})
    pg = ctx.new_page()
    seen = []
    pg.on('request', lambda r: seen.append(r.url) if '/api/v1/' in r.url and 'threads' not in r.url else None)
    jobs = [
        (TITLE, ['manga', 'chapters'], ['50xvk', {'page': 2, 'limit': 20, 'order': {'number': 'desc'}}], 'chapters_p2'),
        (CHAP, ['chapters', 'detail'], [11576932], 'chapter'),
    ]
    for url, prefix, args, name in jobs:
        pg.goto(url, wait_until='domcontentloaded', timeout=60000)
        pg.wait_for_timeout(6000)
        pg.evaluate(LIB)
        print('   queryFns:', pg.evaluate("window.__cxlib.qc().getQueryCache().getAll().filter(q=>q.queryKey[0]!=='me').map(q=>JSON.stringify(q.queryKey).slice(0,80)+' => '+String(q.options.queryFn).slice(0,120))"))
        n0 = len(seen)
        r = pg.evaluate(RUN, [prefix, args])
        print(f'== {name}: queryFn {r["src"]} -> method {r["method"]}; sent {seen[n0:]}')
        print('   mangled:', r['mangledHead'][:200])
        for a in r['plain']:
            print(f'   plain array x{a["n"]} keys={a["keys"]}')
            print('      first:', a['first'][:700])
        (OUT / f'maphook_{name}.json').write_text(json.dumps(r, indent=1, ensure_ascii=False), encoding='utf-8')
        time.sleep(1.5)
    br.close()
