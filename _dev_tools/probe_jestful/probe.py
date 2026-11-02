"""Gentle GET probe for jestful.net: saves the reply in out/ and logs status lines.
Usage: probe.py URL [name] [--range] [--ref REFERER] [--post k=v&k=v]"""
import sys, os, time, requests

UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
os.makedirs(OUT, exist_ok=True)


def fetch(url, name=None, rng=False, ref=None, post=None, extra=None):
    h = {"User-Agent": UA, "Accept-Language": "en-GB,en;q=0.9"}
    if ref:
        h["Referer"] = ref
    if rng:
        h["Range"] = "bytes=0-65535"
    if extra:
        h.update(extra)
    time.sleep(1.0)
    if post is not None:
        r = requests.post(url, headers=h, data=post, timeout=30, stream=True)
    else:
        r = requests.get(url, headers=h, timeout=30, stream=True)
    body = b""
    for chunk in r.iter_content(8192):
        body += chunk
        if rng and len(body) >= 65536:
            break
        if len(body) > 8_000_000:
            break
    r.close()
    line = f"{r.status_code} {len(body)}B {r.headers.get('content-type','')} {url} ref={ref} final={r.url}"
    print(line)
    with open(os.path.join(OUT, "requests.log"), "a", encoding="utf-8") as f:
        f.write(line + "\n")
    if name:
        with open(os.path.join(OUT, name), "wb") as f:
            f.write(body)
    return r, body


if __name__ == "__main__":
    a = sys.argv[1:]
    url = a[0]
    name = a[1] if len(a) > 1 and not a[1].startswith("--") else None
    ref = a[a.index("--ref") + 1] if "--ref" in a else None
    post = a[a.index("--post") + 1] if "--post" in a else None
    r, body = fetch(url, name, "--range" in a, ref, post)
    for k in ("server", "cf-ray", "cf-mitigated", "location", "set-cookie"):
        if k in r.headers:
            print(f"  {k}: {r.headers[k][:200]}")
    print("  magic:", body[:16])
