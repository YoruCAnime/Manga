"""manhwa18.net: list / search / genre feeds through the Inertia data-page JSON."""
import json, html, re, sys
from probe import get


def page(url):
    r = get(url)
    m = re.search(r'data-page="([^"]*)"', r.text)
    if not m:
        return r.status_code, None
    return r.status_code, json.loads(html.unescape(m.group(1)))


def summary(label, url, key):
    code, d = page(url)
    if not d:
        print(label, code, "no data-page")
        return None
    p = d["props"].get(key)
    if not p:
        print(label, code, "no", key, list(d["props"].keys()))
        return None
    items = p["data"]
    print(f"{label} [{code}] page {p['current_page']}/{p['last_page']} per {p['per_page']} total {p['total']} next={p['next_page_url']}")
    for it in items[:4]:
        lc = it.get("latest_chapter") or (it.get("latest_chapters") or [None])[0]
        print("   ", it["slug"], "|", it["name"][:40], "| st", it["status_id"], "| views", it["view"], "| lc", lc and lc.get("name"),
              "| genres", [g["name"] for g in it.get("genres") or []][:6])
    print("    item keys:", sorted(set(items[0].keys()) - {"pilot", "note"}) if items else None)
    return d


if __name__ == "__main__":
    summary("list default", "https://manhwa18.net/manga-list", "paginate")
    summary("list top p2", "https://manhwa18.net/manga-list?sort=top&page=2", "paginate")
    summary("list completed", "https://manhwa18.net/manga-list?completed=1", "paginate")
    summary("genre ntr", "https://manhwa18.net/genre/ntr?sort=top", "paginate")
    summary("search incl 33", "https://manhwa18.net/tim-kiem?accept_genres=33&sort=top", "mangas")
    summary("search incl 33 excl 133", "https://manhwa18.net/tim-kiem?accept_genres=33&reject_genres=133&sort=top", "mangas")
    summary("search q p2", "https://manhwa18.net/tim-kiem?q=wife&page=2", "mangas")
    summary("search artist", "https://manhwa18.net/tim-kiem?artist=Mmyeong", "mangas")
