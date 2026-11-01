"""Headless: what the comix app sees AFTER its own decryption (JSON.parse hook at document start),
plus token replay tests: can a captured ?_= token be reused by a plain client (with / without the cookies)?
-> out/decrypted_*.json, prints a summary."""
import json, time, pathlib, requests
from playwright.sync_api import sync_playwright

OUT = pathlib.Path(__file__).parent / 'out'
TITLE = 'https://comix.to/title/50xvk-i-married-the-dragon-i-killed'
CHAP = TITLE + '/11576932-chapter-20'
HOOK = r"""
(() => {
  if (window.__capHooked) return; window.__capHooked = true;
  window.__cap = [];
  const P = JSON.parse;
  JSON.parse = function (s, r) {
    const v = P.call(this, s, r);
    try {
      if (v && typeof v === 'object' && typeof s === 'string' && s.length > 200 && s.length < 3e6) {
        const k = Object.keys(v);
        if (!k.includes('queries')) window.__cap.push({keys: k, len: s.length, text: s.slice(0, 200000)});
      }
    } catch (e) {}
    return v;
  };
})();
"""
reqs = []
with sync_playwright() as pw:
    br = pw.chromium.launch(headless=True)
    ctx = br.new_context(viewport={'width': 1280, 'height': 900})
    ctx.add_init_script(HOOK)
    pg = ctx.new_page()
    pg.on('request', lambda r: reqs.append((r.url, dict(r.headers))) if '/api/v1/' in r.url else None)
    res = {}
    for name, u in (('title', TITLE), ('chapter', CHAP)):
        pg.goto(u, wait_until='domcontentloaded', timeout=60000)
        pg.wait_for_timeout(7000)
        cap = pg.evaluate('window.__cap || []')
        res[name] = cap
        print(name, len(cap), [(c['keys'], c['len']) for c in cap][:10])
        time.sleep(1)
    (OUT / 'decrypted.json').write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding='utf-8')
    cookies = {c['name']: c['value'] for c in ctx.cookies()}
    ua = pg.evaluate('navigator.userAgent')
    br.close()

print('\nAPI requests seen:')
for u, h in reqs:
    print('  ', u[:170])
# replay tests
tok = [u for u, h in reqs if '/chapters?' in u or '/api/v1/chapters/' in u]
S = requests.Session()
S.headers.update({'User-Agent': ua, 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest', 'Referer': TITLE})
for u in tok[:2]:
    for label, ck in (('no cookies', {}), ('with browser cookies', cookies)):
        time.sleep(1.2)
        r = S.get(u, cookies=ck, timeout=25)
        print(f'replay [{label}] {r.status_code} {r.text[:120]}')
    alt = u.replace('page=1', 'page=2') if 'page=1' in u else u + '&x=1'
    time.sleep(1.2)
    r = S.get(alt, cookies=cookies, timeout=25)
    print(f'replay changed url [cookies] {r.status_code} {r.text[:120]}')
