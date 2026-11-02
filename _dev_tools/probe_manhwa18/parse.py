"""Verify the parsers the Kotlin sources use, against the live sites.
For each site: a list page, a search, a genre list, a details page, the chapter list and the page-image URLs,
then 3 covers + 3 page images fetched (64 KB max, no Referer) and checked by magic bytes.
Usage: parse.py [net|cc|club]   (default: all three)"""
import sys, re, json, html, base64
from datetime import datetime, timezone
from bs4 import BeautifulSoup
from probe import get, magic

NUM = re.compile(r"(?:chapter|chap|ch|ep|episode)\.?\s*(\d+(?:\.\d+)?)", re.I)


def chap_num(name, fallback=-1.0):
    m = NUM.search(name) or re.search(r"(\d+(?:\.\d+)?)", name)
    return float(m.group(1)) if m else fallback


def check_images(label, urls):
    for u in urls[:3]:
        r = get(u, img=True)
        print(f"   {label} {r.status_code} {magic(r.content)} {u}")


# ---------------- manhwa18.net (Laravel + Inertia: JSON in #app[data-page]) ----------------
def net_page(url):
    r = get(url)
    s = BeautifulSoup(r.text, "lxml")
    app = s.select_one("#app[data-page]")
    return json.loads(app["data-page"])["props"]


def net_cover(m):
    v = [x for x in (m.get("cover_image") or {}).get("variants") or [] if x.get("width", 0) >= 300]
    return v[0]["url"] if v else m.get("cover_url") or m.get("thumb_url")


def net_row(m):
    lc = m.get("latest_chapter") or {}
    return dict(id=m["slug"], title=m["name"], cover=net_cover(m), tags=[g["name"] for g in m.get("genres") or []],
                sub=lc.get("name", ""))


def net():
    B = "https://manhwa18.net"
    p = net_page(B + "/manga-list?sort=top&page=1")["paginate"]
    rows = [net_row(m) for m in p["data"]]
    print("NET popular", len(rows), "hasNext", p["current_page"] < p["last_page"])
    for r in rows[:3]: print("  ", r)
    covers = [r["cover"] for r in rows]
    sp = net_page(B + "/tim-kiem?q=wife&accept_genres=4&reject_genres=165,166&completed=1")
    print("NET search q+genre+completed", sp["mangas"]["total"], [(m["slug"], m["status_id"]) for m in sp["mangas"]["data"][:5]])
    print("NET genres", len(sp["genres"]), [g["name"] for g in sp["genres"]][:10])
    d = net_page(B + "/manga/milf-exchange-plan")
    m = d["manga"]
    print("NET details", m["name"], "| alt", m["other_name"], "| status", m["status_id"], "| artists", [a["name"] for a in m["artists"]],
          "| genres", [g["name"] for g in m["genres"]], "| tags", [t["name"] for t in m["tags"]])
    print("   desc", BeautifulSoup(m.get("pilot") or "", "lxml").get_text(" ", strip=True)[:120])
    ch = d["chapters"]
    print("NET chapters", len(ch), [(c["slug"], c["name"], chap_num(c["name"], c["order"]),
                                      int(datetime.fromisoformat(c["created_at"].replace("Z", "+00:00")).timestamp() * 1000)) for c in ch[:3]])
    c = net_page(B + f"/manga/milf-exchange-plan/{ch[1]['slug']}")
    imgs = [i["src"] for i in c.get("chapterImages") or []]
    if not imgs:
        imgs = re.findall(r'<img[^>]+src="([^"]+)"', c.get("chapterContent") or "")
    print("NET pages", len(imgs), imgs[:2])
    check_images("cover", covers)
    check_images("page", imgs)


# ---------------- manhwa18.cc (plain HTML) ----------------
def cc_list(url):
    s = BeautifulSoup(get(url).text, "lxml")
    rows = []
    for it in s.select("div.manga-lists div.manga-item"):
        a = it.select_one("div.thumb a[href]")
        img = it.select_one("div.thumb img")
        ch = it.select_one("div.chapter-item a")
        rows.append(dict(id=a["href"].rstrip("/").rsplit("/", 1)[-1], title=a.get("title"),
                         cover=img.get("data-src") or img.get("src"), sub=ch.text.strip() if ch else ""))
    return rows, s.select_one("ul.pagination li.next a") is not None


def cc_date(t):
    t = t.strip()
    try:
        return int(datetime.strptime(t, "%d %b %Y").replace(tzinfo=timezone.utc).timestamp() * 1000)
    except ValueError:
        return 0


def cc():
    B = "https://manhwa18.cc"
    rows, nxt = cc_list(B + "/webtoons/1?orderby=trending")
    print("CC popular", len(rows), "hasNext", nxt)
    for r in rows[:3]: print("  ", r)
    covers = [r["cover"] for r in rows]
    rows, nxt = cc_list(B + "/search?q=wife&page=1")
    print("CC search", len(rows), nxt, [r["id"] for r in rows[:4]])
    rows, nxt = cc_list(B + "/webtoon-genre/romance/2")
    print("CC genre romance p2", len(rows), nxt, [r["id"] for r in rows[:4]])
    s = BeautifulSoup(get(B + "/webtoons").text, "lxml")
    genres = [(a["href"].rstrip("/").rsplit("/", 1)[-1], a.text.strip()) for a in s.select("a[href*='/webtoon-genre/']")]
    genres = list(dict.fromkeys(genres))
    print("CC genres", len(genres), genres)
    slug = "amai-seikatsu"
    s = BeautifulSoup(get(B + "/webtoon/" + slug).text, "lxml")
    info = {}
    for it in s.select("div.post-content_item"):
        h = it.select_one("div.summary-heading")
        v = it.select_one("div.summary-content")
        if h and v:
            info[h.get_text(" ", strip=True).rstrip(":")] = v
    print("CC details", s.select_one("div.post-title h1").get_text(" ", strip=True), "| cover",
          s.select_one("meta[property='og:image']")["content"],
          {k: (", ".join(a.text.strip() for a in v.select("a")) or v.get_text(" ", strip=True))[:80] for k, v in info.items() if k != "Rating"})
    print("   desc", s.select_one("div.panel-story-description div.dsct").get_text(" ", strip=True)[:120])
    lis = s.select("ul.row-content-chapter li")
    chs = []
    for li in lis:
        a = li.select_one("a.chapter-name")
        t = li.select_one("span.chapter-time")
        chs.append((a["href"].rsplit("/", 1)[-1], a.text.strip(), chap_num(a.text), cc_date(t.text) if t else "new"))
    print("CC chapters", len(chs), chs[:3], chs[-1])
    s = BeautifulSoup(get(B + f"/webtoon/{slug}/{chs[1][0]}").text, "lxml")
    imgs = [i.get("data-src") or i.get("src") for i in s.select("div.read-content img")]
    print("CC pages", len(imgs), imgs[:2])
    check_images("cover", covers)
    check_images("page", imgs)


# ---------------- manga18.club (plain HTML, page list base64 in a script) ----------------
def club_list(url):
    s = BeautifulSoup(get(url).text, "lxml")
    rows = []
    for it in s.select("div.recoment_box div.story_item"):
        a = it.select_one("div.story_images a[href]")
        img = it.select_one("div.story_images img")
        ch = it.select_one("div.chapter_count a")
        rows.append(dict(id=a["href"].rstrip("/").rsplit("/", 1)[-1], title=a.get("title") or it.select_one("div.mg_name a").text,
                         cover=img.get("data-src") or img.get("src"), sub=ch.text.strip() if ch else ""))
    nxt = any(x.text.strip() == "›" for x in s.select("div.section_pagination li a"))
    return rows, nxt


def club():
    B = "https://manga18.club"
    rows, nxt = club_list(B + "/list-manga/1?order_by=views")
    print("CLUB popular", len(rows), "hasNext", nxt)
    for r in rows[:3]: print("  ", r)
    covers = [r["cover"] for r in rows]
    rows, nxt = club_list(B + "/list-manga?search=wife&page=2&order_by=views")
    print("CLUB search p2 views", len(rows), nxt, [r["id"] for r in rows[:4]])
    rows, nxt = club_list(B + "/manga-list/romance/3?order_by=views")
    print("CLUB genre romance p3", len(rows), nxt, [r["id"] for r in rows[:4]])
    s = BeautifulSoup(get(B + "/manhwa/secret-class").text, "lxml")
    genres = [(a["href"].rstrip("/").rsplit("/", 1)[-1], a.get_text(" ", strip=True)) for a in s.select("a[href*='/manga-list/']")]
    genres = list(dict.fromkeys(genres))
    print("CLUB genres", len(genres), genres)
    info = {}
    for it in s.select("div.detail_listInfo div.item"):
        info[it.select_one("div.info_label").get_text(" ", strip=True)] = it.select_one("div.info_value")
    print("CLUB details", s.select_one("div.detail_name h1").text.strip(), "| cover", s.select_one("div.detail_avatar img")["src"],
          {k: (", ".join(a.text.strip() for a in v.select("a")) or v.get_text(" ", strip=True))[:80] for k, v in info.items()})
    print("   desc", s.select_one("div.detail_reviewContent").get_text(" ", strip=True)[:120])
    chs = []
    for li in s.select("div.chapter_box li"):
        a = li.select_one("a.chapter_num")
        t = li.select_one("p.chapter_info")
        try:
            d = int(datetime.strptime(t.text.strip(), "%d-%m-%Y").replace(tzinfo=timezone.utc).timestamp() * 1000)
        except ValueError:
            d = t.text.strip()
        chs.append((a["href"].rstrip("/").rsplit("/", 1)[-1], a.text.strip(), chap_num(a.text), d))
    print("CLUB chapters", len(chs), chs[:3], chs[-1])
    t = get(B + f"/manhwa/secret-class/{chs[1][0]}").text
    m = re.search(r"slides_p_path\s*=\s*\[(.*?)\]", t, re.S)
    imgs = [base64.b64decode(x).decode() for x in re.findall(r'"([^"]+)"', m.group(1))]
    print("CLUB pages", len(imgs), imgs[:2])
    check_images("cover", covers)
    check_images("page", imgs)


if __name__ == "__main__":
    which = sys.argv[1:] or ["net", "cc", "club"]
    for w in which:
        globals()[w]()
        print()
