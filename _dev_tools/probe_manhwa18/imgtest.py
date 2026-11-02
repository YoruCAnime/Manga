"""Fetch picture URLs (first 64 KB) with and without a Referer and report the magic bytes.
Usage: imgtest.py REFERER URL [URL ...]"""
import sys
from probe import get, magic

ref = sys.argv[1]
for u in sys.argv[2:]:
    for r_ in (None, ref):
        r = get(u, ref=r_, img=True)
        print(f"{r.status_code} {magic(r.content):6} {len(r.content):6} ref={r_!s:28} {r.headers.get('content-type')} {u}")
