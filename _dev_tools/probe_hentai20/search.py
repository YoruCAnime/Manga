"""hentai20.io: list / search / genre parameters (MangaReader theme). Prints items, pagination."""
import re
from bs4 import BeautifulSoup
from probe import fetch


def show(url, name):
    r, b = fetch(url, name)
    d = BeautifulSoup(b.decode("utf-8", "replace"), "lxml")
    items = d.select(".listupd .bs .bsx > a")
    nxt = d.select_one(".pagination a.next, .hpage a.r")
    print("   ", len(items), [a["href"].rstrip("/").rsplit("/", 1)[-1] for a in items[:4]], "| next:", nxt and nxt.get("href"))


show("https://hentai20.io/manga/?order=popular", "list_popular.html")
show("https://hentai20.io/manga/?order=update&page=2", "list_update_p2.html")
show("https://hentai20.io/manga/?page=58&order=update", "list_last.html")
show("https://hentai20.io/?s=secret", "search_s.html")
show("https://hentai20.io/page/2/?s=sister", "search_s_p2.html")
show("https://hentai20.io/manga/?genre%5B%5D=7&genre%5B%5D=6&order=popular", "genre_and.html")
show("https://hentai20.io/manga/?genre%5B%5D=-6&order=popular", "genre_neg.html")
show("https://hentai20.io/manga/?status=completed&type=manhwa&order=popular", "status_type.html")
show("https://hentai20.io/manga/?title=secret", "title_param.html")
