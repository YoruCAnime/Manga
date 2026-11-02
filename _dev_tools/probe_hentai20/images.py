"""hentai20.io: covers and page images with / without Referer (64 KB max each), and filter semantics."""
import re
from probe import fetch

cover_thumb = "https://hentai20.io/wp-content/uploads/2024/08/secret-class-01-193x278.jpg"
cover_full = "https://hentai20.io/wp-content/uploads/2024/08/secret-class-01.jpg"
page = "https://img.hentai1.io/secret-class-01/chapter-322/001-615a2cd9d3d1.jpg"

for url in (cover_thumb, cover_full, page):
    for ref in (None, "https://hentai20.io/", "https://example.com/"):
        r, b = fetch(url, None, rng=True, ref=ref)
        print("    magic", b[:4], "len", len(b), r.headers.get("content-range"), r.headers.get("server"))


def last_page(url):
    r, b = fetch(url)
    s = b.decode("utf-8", "replace")
    nums = [int(x) for x in re.findall(r'page-numbers"[^>]*>(\d+)<', s)]
    items = len(re.findall(r'<div class="bs">', s))
    print("    pages:", max(nums) if nums else 1, "items on p1:", items)


last_page("https://hentai20.io/manga/?genre%5B%5D=7")
last_page("https://hentai20.io/manga/?genre%5B%5D=6")
last_page("https://hentai20.io/manga/?genre%5B%5D=7&genre%5B%5D=6")
last_page("https://hentai20.io/manga/?status=completed")
last_page("https://hentai20.io/manga/?type=manhwa")
last_page("https://hentai20.io/manga/?type=manga")
