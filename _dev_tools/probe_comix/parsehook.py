"""Headless: does comix's decrypt go through JSON.parse / TextDecoder / Response.json? Log every call (no filter)
on a chapter page, then check JSON.parse is still ours after load."""
import json, pathlib
from playwright.sync_api import sync_playwright

OUT = pathlib.Path(__file__).parent / 'out'
CHAP = 'https://comix.to/title/50xvk-i-married-the-dragon-i-killed/11576932-chapter-20'
HOOK = r"""
(() => {
  if (window.top !== window || window.__ph) return; window.__ph = [];
  const P = JSON.parse;
  const mine = function parse(s, r) {
    const v = P.call(this, s, r);
    try { window.__ph.push({f: 'JSON.parse', n: (s && s.length) || 0, head: String(s).slice(0, 120),
                            keys: v && typeof v === 'object' ? Object.keys(v).slice(0, 8) : null}); } catch (e) {}
    return v;
  };
  JSON.parse = mine; window.__mine = mine;
  const D = TextDecoder.prototype.decode;
  TextDecoder.prototype.decode = function (...a) {
    const s = D.apply(this, a);
    try { if (s.length > 300) window.__ph.push({f: 'TextDecoder', n: s.length, head: s.slice(0, 160)}); } catch (e) {}
    return s;
  };
})();
"""
with sync_playwright() as pw:
    br = pw.chromium.launch(headless=True)
    ctx = br.new_context()
    ctx.add_init_script(HOOK)
    pg = ctx.new_page()
    pg.goto(CHAP, wait_until='domcontentloaded', timeout=60000)
    pg.wait_for_timeout(8000)
    ph = pg.evaluate('window.__ph')
    still = pg.evaluate('JSON.parse === window.__mine')
    br.close()
print('JSON.parse still hooked:', still, 'calls:', len(ph))
for c in ph:
    if c['n'] > 150:
        print(c)
(OUT / 'parsehook.json').write_text(json.dumps(ph, indent=1, ensure_ascii=False), encoding='utf-8')
