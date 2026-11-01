"""Read-only GET probe for atsu.moe. Usage:
    python -X utf8 probe.py NAME URL [NAME URL ...]
Saves each body to out/NAME.json (or .txt), prints status, key headers, top-level shape.
No login, no cookies kept between runs, ~1.2 s between requests."""
import json, os, sys, time
import requests

UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36'
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out')
os.makedirs(OUT, exist_ok=True)
HDRS = ['content-type', 'cache-control', 'cf-cache-status', 'set-cookie', 'x-ratelimit-limit',
        'x-ratelimit-remaining', 'retry-after', 'access-control-allow-origin', 'age', 'server', 'cf-mitigated']


def shape(v, depth=0):
    if isinstance(v, dict):
        if depth >= 2:
            return '{' + ','.join(list(v)[:25]) + '}'
        return {k: shape(x, depth + 1) for k, x in list(v.items())[:40]}
    if isinstance(v, list):
        return [f'len={len(v)}', shape(v[0], depth + 1)] if v else []
    if isinstance(v, str):
        return v[:80]
    return v


def main(argv):
    s = requests.Session()
    s.headers.update({'User-Agent': UA, 'Accept': 'application/json, */*'})
    pairs = list(zip(argv[0::2], argv[1::2]))
    for i, (name, url) in enumerate(pairs):
        if i:
            time.sleep(1.2)
        t0 = time.time()
        try:
            r = s.get(url, timeout=30)
        except Exception as e:
            print('##', name, 'ERR', e); continue
        dt = time.time() - t0
        print(f'## {name}  {r.status_code}  {len(r.content)} B  {dt:.2f}s  {url}')
        for h in HDRS:
            if h in r.headers:
                print(f'   {h}: {r.headers[h][:160]}')
        try:
            js = r.json()
            open(os.path.join(OUT, name + '.json'), 'w', encoding='utf8').write(json.dumps(js, indent=1, ensure_ascii=False))
            print(json.dumps(shape(js), ensure_ascii=False)[:2500])
        except Exception:
            open(os.path.join(OUT, name + '.txt'), 'w', encoding='utf8').write(r.text)
            print('   (not json)', r.text[:400].replace('\n', ' '))
    print('cookies in jar:', s.cookies.keys())


if __name__ == '__main__':
    main(sys.argv[1:])
