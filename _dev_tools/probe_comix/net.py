"""Headless: open comix.to pages and log every XHR/fetch (url, status, headers asked, body start).
python net.py URL [URL...]  -> out/net_<n>.txt"""
import sys, json, time, pathlib
from playwright.sync_api import sync_playwright

OUT = pathlib.Path(__file__).parent / 'out'
OUT.mkdir(exist_ok=True)
urls = sys.argv[1:]
with sync_playwright() as pw:
    br = pw.chromium.launch(headless=True)
    ctx = br.new_context(viewport={'width': 1280, 'height': 900})
    pg = ctx.new_page()
    log = []

    def on_resp(r):
        rt = r.request.resource_type
        if rt not in ('xhr', 'fetch'):
            return
        try:
            body = r.text()[:1500]
        except Exception as e:
            body = f'<no body {e}>'
        log.append({'m': r.request.method, 'u': r.url, 's': r.status, 'reqh': {k: v for k, v in r.request.headers.items() if k.lower() not in ('user-agent',)},
                    'post': (r.request.post_data or '')[:400], 'ct': r.headers.get('content-type'), 'body': body})
    pg.on('response', on_resp)
    for i, u in enumerate(urls):
        log.clear()
        pg.goto(u, wait_until='domcontentloaded', timeout=60000)
        pg.wait_for_timeout(6000)
        try:
            pg.mouse.wheel(0, 3000); pg.wait_for_timeout(2500)
        except Exception:
            pass
        f = OUT / f'net_{i}.txt'
        with open(f, 'w', encoding='utf-8') as fh:
            fh.write(f'PAGE {u}\nTITLE {pg.title()}\nCOOKIES {[c["name"] for c in ctx.cookies()]}\n\n')
            for e in log:
                fh.write(json.dumps(e, ensure_ascii=False) + '\n\n')
        (OUT / f'page_{i}.html').write_text(pg.content(), encoding='utf-8')
        print(u, '->', len(log), 'calls', f)
        for e in log:
            print('  ', e['m'], e['s'], e['u'][:160])
        time.sleep(1.5)
    br.close()
