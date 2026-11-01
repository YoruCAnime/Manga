"""Headless: largest chapter-list page comix accepts (limit 300 / 1000), via cxlib.js. 2 API calls, 4 s apart."""
import json, pathlib, time
from playwright.sync_api import sync_playwright

HERE = pathlib.Path(__file__).parent
LIB = (HERE / 'cxlib.js').read_text(encoding='utf-8')
with sync_playwright() as pw:
    br = pw.chromium.launch(headless=True)
    pg = br.new_page()
    pg.goto('https://comix.to/title/50xvk-i-married-the-dragon-i-killed', wait_until='domcontentloaded', timeout=60000)
    pg.wait_for_timeout(6000)
    pg.evaluate(LIB)
    for lim in (300, 1000):
        time.sleep(4)
        try:
            r = pg.evaluate('([h,p]) => window.__cxlib.chapters(h,p)', ['50xvk', {'page': 1, 'limit': lim, 'order': {'number': 'desc'}}])
            nums = sorted({c['number'] for c in r['items']})
            print(f'limit {lim}: {len(r["items"])} entries, status {r["status"]}, chapters {nums[:3]}..{nums[-3:]}, groups {len({c["groupId"] for c in r["items"]})}')
        except Exception as e:
            print(f'limit {lim}: ERR {str(e)[:300]}')
    br.close()
