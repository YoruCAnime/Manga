"""Plain GET a comix page and summarise script#initial-data (.queries keys + shape).
python initdata.py URL [save_name]"""
import sys, json, re, pathlib, requests

UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36'
OUT = pathlib.Path(__file__).parent / 'out'
r = requests.get(sys.argv[1], headers={'User-Agent': UA, 'Accept': 'text/html'}, timeout=25)
print(r.status_code, len(r.content))
m = re.search(r'<script[^>]*id="initial-data"[^>]*>(.*?)</script>', r.text, re.S)
if not m:
    print('no initial-data'); sys.exit()
d = json.loads(m.group(1))
if len(sys.argv) > 2:
    (OUT / (sys.argv[2] + '.json')).write_text(json.dumps(d, ensure_ascii=False, indent=1), encoding='utf-8')
print('top keys:', list(d))


def shape(v, depth=0):
    if isinstance(v, dict):
        return '{' + ', '.join(f'{k}: {shape(x, depth+1) if depth < 1 else type(x).__name__}' for k, x in list(v.items())[:40]) + '}'
    if isinstance(v, list):
        return f'[{len(v)}x ' + (shape(v[0], depth) if v else '') + ']'
    return repr(v)[:60]


for k, v in (d.get('queries') or {}).items():
    print('\nKEY', k)
    print('   ', shape(v)[:1500])
