"""hentaigasm.com probe: one polite GET per call (>= 1.5 s apart across runs), saves the body to out/<name>.
python fetch.py NAME URL [--range] [--referer URL] [--head]
--range  = only the first 64 KB (Range: bytes=0-65535, stream + stop), for video / m3u8 checks. Never whole videos."""
import sys, time, pathlib, requests

OUT = pathlib.Path(__file__).parent / 'out'
OUT.mkdir(exist_ok=True)
STAMP = OUT / '.last'
UA = ('Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) '
      'Chrome/130.0.0.0 Mobile Safari/537.36')


def main():
    a = sys.argv[1:]
    name, url = a[0], a[1]
    rng = '--range' in a
    head = '--head' in a
    ref = a[a.index('--referer') + 1] if '--referer' in a else None
    try:
        wait = float(STAMP.read_text()) + 1.5 - time.time()
        if wait > 0:
            time.sleep(wait)
    except Exception:
        pass
    h = {'User-Agent': a[a.index('--ua') + 1] if '--ua' in a else UA, 'Accept-Language': 'en-GB,en;q=0.8'}
    if ref:
        h['Referer'] = ref
    if rng:
        h['Range'] = 'bytes=0-65535'
    if head:
        r = requests.head(url, headers=h, timeout=30, allow_redirects=True)
        body = b''
    else:
        r = requests.get(url, headers=h, timeout=30, stream=True, allow_redirects=True)
        body = b''
        for chunk in r.iter_content(16384):
            body += chunk
            if len(body) >= 65536 and (rng or 'video' in r.headers.get('content-type', '')):
                break
            if len(body) > 8_000_000:
                break
        r.close()
    STAMP.write_text(str(time.time()))
    print(f'-- {r.request.method} {url} -> {r.status_code} final={r.url}')
    for hist in r.history:
        print(f'   redirect {hist.status_code} {hist.url} -> {hist.headers.get("location")}')
    for k, v in r.headers.items():
        print(f'   {k}: {v[:200]}')
    print(f'   body {len(body)} B; first bytes {body[:16]!r}')
    if body:
        (OUT / name).write_bytes(body)


main()
