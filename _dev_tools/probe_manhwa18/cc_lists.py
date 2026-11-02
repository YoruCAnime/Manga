"""manhwa18.cc: list feeds, sorting, paging end."""
from bs4 import BeautifulSoup
from probe import get

BASE = "https://manhwa18.cc"


def items(url):
    r = get(url)
    s = BeautifulSoup(r.text, "lxml")
    out = []
    for it in s.select("div.manga-lists div.manga-item, div.manga-item"):
        a = it.select_one("div.thumb a")
        if not a:
            continue
        img = it.select_one("div.thumb img")
        ch = it.select_one("div.chapter-item a, span.chapter a")
        out.append((a["href"].rsplit("/", 1)[-1], a.get("title"), img and (img.get("data-src") or img.get("src")), ch and ch.text.strip()))
    nxt = s.select_one("ul.pagination li.next a")
    last = s.select("ul.pagination li a")
    return r.status_code, out, nxt and nxt.get("href"), [x.get("href") for x in last][-3:], s


def show(label, url):
    code, out, nxt, last, _ = items(url)
    print(f"{label} [{code}] {len(out)} items next={nxt} pagelinks={last}")
    for o in out[:3]:
        print("    ", o)
    return out


if __name__ == "__main__":
    show("webtoons p2 trending", BASE + "/webtoons/2?orderby=trending")
    show("webtoons p2 latest", BASE + "/webtoons/2?orderby=latest")
    show("webtoons p2 default", BASE + "/webtoons/2")
    show("drama p2 trending", BASE + "/webtoon-genre/drama/2?orderby=trending")
    show("drama p2", BASE + "/webtoon-genre/drama/2")
    show("raw", BASE + "/raw")
    show("completed", BASE + "/completed")
    show("search p4", BASE + "/search?q=wife&page=4")
    show("search p40", BASE + "/search?q=wife&page=40")
    show("webtoons p9999", BASE + "/webtoons/9999")
