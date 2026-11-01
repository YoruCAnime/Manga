"""Headless: import comix's own ES modules (same URL = same live instance) and look for the API object,
the axios instance and the secure (sign / decrypt) exports.  -> out/modules.json"""
import json, pathlib
from playwright.sync_api import sync_playwright

OUT = pathlib.Path(__file__).parent / 'out'
JS = r"""
async () => {
  const urls = [...document.querySelectorAll('script[src]')].map(s => s.src);
  const main = urls.find(u => /\/main-[^/]+\.js$/.test(u));
  const src = await (await fetch(main)).text();
  const api = (src.match(/from"\.\/(apicore-[^"]+\.js)"/) || [])[1];
  const sec = (src.match(/from"\.\/(secure-[^"]+\.js)"/) || [])[1];
  const base = main.slice(0, main.lastIndexOf('/') + 1);
  const A = await import(base + api), S = await import(base + sec);
  const desc = o => Object.fromEntries(Object.keys(o).map(k => {
    const v = o[k]; let t = typeof v;
    if (v && t === 'object') t = 'object{' + Object.keys(v).slice(0, 60).join(',') + '}';
    if (t === 'function') t = 'fn/' + v.length + ' ' + String(v).slice(0, 80);
    return [k, t];
  }));
  const out = {main, api, sec, apiExports: desc(A), secExports: desc(S)};
  // which export has interceptors (axios)?
  for (const k of Object.keys(A)) { const v = A[k]; if (v && (v.interceptors || (v.defaults && v.get))) out.axiosExport = k; }
  return out;
}
"""
with sync_playwright() as pw:
    br = pw.chromium.launch(headless=True)
    pg = br.new_page()
    pg.goto('https://comix.to/browse', wait_until='domcontentloaded', timeout=60000)
    pg.wait_for_timeout(5000)
    res = pg.evaluate(JS)
    (OUT / 'modules.json').write_text(json.dumps(res, indent=1, ensure_ascii=False), encoding='utf-8')
    print(json.dumps(res, indent=1, ensure_ascii=False)[:9000])
    br.close()
