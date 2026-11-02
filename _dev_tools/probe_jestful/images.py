"""jestful.net: covers and page images with / without Referer (64 KB max each)."""
import base64
from probe import fetch

cover_php = "https://jestful.net/app/manga/themes/modern/cover.php?u=aHR0cHM6Ly9qNC5qZmltdjIueHl6L2ltYWdlcy8yMDIwMDgwMy81ZjI3ODc2MjMzMTExXzVmMjc4NzY1NDJlNmUuanBn&w=400&s=5bb221e1f0b63670db81a91ae111cf553a94929bc329c24abc60b612def19cfd"
u = "aHR0cHM6Ly9qNC5qZmltdjIueHl6L2ltYWdlcy8yMDIwMDgwMy81ZjI3ODc2MjMzMTExXzVmMjc4NzY1NDJlNmUuanBn"
direct = base64.urlsafe_b64decode(u + "=" * (-len(u) % 4)).decode()
print("decoded cover:", direct)
page = "https://j2.jfimv2.xyz/images4/20261101/a95288288df3dd44c2bd2e179f490016067.jpg"

for url in (cover_php, direct, page):
    for ref in (None, "https://jestful.net/", "https://example.com/"):
        r, b = fetch(url, None, rng=True, ref=ref)
        print("    magic", b[:4], "len", len(b), r.headers.get("content-range"), r.headers.get("server"))
