"""Gentle probe for manhwa18.net / manhwa18.cc / manga18.club.
Usage: probe.py URL [outname] [--ref REFERER] [--img]
Saves the reply in out/<outname> and prints status, headers and a short head of the body.
"""
import sys, os, time, re
import requests

UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
os.makedirs(OUT, exist_ok=True)
_last = [0.0]


def get(url, ref=None, img=False, extra=None):
    gap = time.time() - _last[0]
    if gap < 1.1:
        time.sleep(1.1 - gap)
    h = {"User-Agent": UA, "Accept-Language": "en-GB,en;q=0.9"}
    if ref:
        h["Referer"] = ref
    if img:
        h["Range"] = "bytes=0-65535"
    if extra:
        h.update(extra)
    r = requests.get(url, headers=h, timeout=30, stream=img, allow_redirects=True)
    _last[0] = time.time()
    if img:
        data = b""
        for chunk in r.iter_content(8192):
            data += chunk
            if len(data) >= 65536:
                break
        r.close()
        r._content = data
    with open(os.path.join(OUT, "requests.log"), "a", encoding="utf-8") as f:
        f.write(f"{time.strftime('%H:%M:%S')} {r.status_code} {url} ref={ref}\n")
    return r


def magic(b):
    if b[:3] == b"\xff\xd8\xff": return "jpeg"
    if b[:8] == b"\x89PNG\r\n\x1a\n": return "png"
    if b[:4] == b"RIFF" and b[8:12] == b"WEBP": return "webp"
    if b[:6] in (b"GIF87a", b"GIF89a"): return "gif"
    if b[4:12] in (b"ftypavif",): return "avif"
    return "?" + repr(b[:12])


if __name__ == "__main__":
    url = sys.argv[1]
    name = sys.argv[2] if len(sys.argv) > 2 and not sys.argv[2].startswith("--") else None
    ref = None
    if "--ref" in sys.argv:
        ref = sys.argv[sys.argv.index("--ref") + 1]
    img = "--img" in sys.argv
    r = get(url, ref=ref, img=img)
    print(r.status_code, r.url)
    for k in ("content-type", "server", "cf-ray", "location", "content-length", "content-range", "set-cookie"):
        if k in r.headers:
            print(f"  {k}: {r.headers[k][:200]}")
    if img:
        print("  magic:", magic(r.content), len(r.content))
    else:
        t = r.text
        print("  len", len(t))
        m = re.search(r"<title>(.*?)</title>", t, re.S)
        print("  title:", m.group(1).strip()[:150] if m else None)
        if name:
            with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
                f.write(t)
