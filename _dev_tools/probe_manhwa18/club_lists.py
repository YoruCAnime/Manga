"""manga18.club: list feeds, sorting, paging end, search, genre."""
from bs4 import BeautifulSoup
from probe import get

BASE = "https://manga18.club"


def items(url):
    r = get(url)
    s = BeautifulSoup(r.text, "lxml")
    out = []
    for it in s.select("div.recoment_box div.story_item"):
        a = it.select_one("div.story_images a")
        if not a:
            continue
        img = it.select_one("div.story_images img")
        ch = it.select_one("div.chapter_count a")
        tm = it.select_one("div.chapter_time")
        out.append((a["href"].rstrip("/").rsplit("/", 1)[-1], a.get("title"), img and (img.get("data-src") or img.get("src")),
                    ch and ch.text.strip(), tm and tm.text.strip()))
    pag = s.select("div.section_pagination ul.pagination li a")
    nxt = [x.get("href") for x in pag if x.text.strip() == "›"]
    return r.status_code, out, nxt, [x.get("href") for x in pag][-2:], s


def show(label, url):
    code, out, nxt, last, s = items(url)
    print(f"{label} [{code}] {len(out)} items next={nxt} pagelinks={last}")
    for o in out[:3]:
        print("    ", o)
    return s


if __name__ == "__main__":
    show("list views p2", BASE + "/list-manga/2?order_by=views")
    show("list latest p2", BASE + "/list-manga/2?order_by=lastest")
    show("list default p2", BASE + "/list-manga/2")
    show("latest-release p2", BASE + "/latest-release/2")
    show("search wife", BASE + "/list-manga?search=wife")
    show("search wife p2", BASE + "/list-manga/2?search=wife")
    show("genre drama", BASE + "/manga-list/drama")
    show("genre drama p2 views", BASE + "/manga-list/drama/2?order_by=views")
    show("list p9999", BASE + "/list-manga/9999")
