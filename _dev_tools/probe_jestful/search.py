"""jestful.net: list / search / genre parameters. Prints count line, page indicator and first titles."""
import re
from probe import fetch

def show(url, name):
    r, b = fetch(url, name)
    s = b.decode("utf-8", "replace")
    cnt = re.search(r'library-count">([^<]+)<', s)
    pg = re.search(r'library-sort-page-indicator">\s*([^<]+?)\s*<', s)
    titles = re.findall(r'<h2 class="update-title">\s*<a href="/hwms-([^"]+)\.html"', s)
    nxt = 'title="Next Page"' in s
    print("   ", cnt and cnt.group(1), "|", pg and pg.group(1), "| next:", nxt, "|", len(titles), titles[:4])

show("https://jestful.net/manga-list.html?page=1&sort=views&sort_type=DESC", "list_views.html")
show("https://jestful.net/manga-list.html?name=one+piece", "search_name.html")
show("https://jestful.net/manga-list.html?title=one+piece", "search_title.html")
show("https://jestful.net/manga-list.html?title=%E3%83%AF%E3%83%B3%E3%83%94%E3%83%BC%E3%82%B9", "search_jp.html")
show("https://jestful.net/manga-list.html?include_genres%5B%5D=romance&include_genres%5B%5D=comedy&exclude_genres%5B%5D=harem&sort=views&sort_type=DESC", "genre_inc_exc.html")
show("https://jestful.net/manga-list.html?include_genres%5B%5D=romance&sort=views&sort_type=DESC&page=2", "genre_p2.html")
show("https://jestful.net/manga-list.html?m_status=1&sort=views&sort_type=DESC", "status_completed.html")
show("https://jestful.net/manga-list.html?author=ODA+Eiichiro", "author.html")
show("https://jestful.net/manga-list.html?page=394", "last_page.html")
show("https://jestful.net/manga-list-genre-adult.html", "genre_adult.html")
