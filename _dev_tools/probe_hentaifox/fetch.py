"""Gentle page fetcher for the hentai2read / hentaifox probes (read-only GETs, ~1 request / s).
python fetch.py NAME URL [referer]   -> out/NAME.html (+ status line printed)
Images: only the first 64 KB are read (stream + stop)."""
import sys, time, pathlib, requests

UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36'
S = requests.Session()
S.headers.update({'User-Agent': UA, 'Accept-Language': 'en-GB,en;q=0.9'})
_last = [0.0]


def get(url, out=None, referer=None, image=False, headers=None, **kw):
    wait = _last[0] + 1.1 - time.time()
    if wait > 0:
        time.sleep(wait)
    h = dict(headers or {})
    if referer:
        h["Referer"] = referer
    r = S.get(url, headers=h, timeout=30, stream=image, **kw)
    _last[0] = time.time()
    if image:
        data = b''
        for chunk in r.iter_content(8192):
            data += chunk
            if len(data) >= 65536:
                break
        r.close()
        print(f'-- IMG {url[:150]} ref={referer!r} -> {r.status_code} {r.headers.get("content-type")} {data[:12]!r}')
        return r, data
    print(f'-- GET {r.url[:160]} -> {r.status_code} {r.headers.get("content-type")} {len(r.content)}B server={r.headers.get("server")}')
    if out:
        p = pathlib.Path(out)
        p.parent.mkdir(exist_ok=True, parents=True)
        p.write_text(r.text, encoding='utf-8')
    return r


if __name__ == '__main__':
    name, url = sys.argv[1], sys.argv[2]
    ref = sys.argv[3] if len(sys.argv) > 3 else None
    out = pathlib.Path(__file__).parent / 'out' / f'{name}.html'
    r = get(url, out, ref)
    if r.status_code in (403, 451, 503):
        print(r.text[:600])
