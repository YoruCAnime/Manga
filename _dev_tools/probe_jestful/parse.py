"""jestful.net: the selectors the app uses, checked against live pages.
Prints a list page, a details page, the chapter list, page-image URLs, and fetches 3 covers + 3 pages (64 KB each)."""
import re
from datetime import datetime, timedelta, timezone
from bs4 import BeautifulSoup
from probe import fetch

BASE = "https://jestful.net"
SITE_TZ = timezone(timedelta(hours=7))  # chapter times are site-local (UTC+7), measured against "12 minutes ago"


def doc(url):
    r, b = fetch(url)
    assert r.status_code == 200, r.status_code
    return BeautifulSoup(b.decode("utf-8", "replace"), "lxml")


def best_src(img):
    # srcset of the signed cover.php: take the widest entry (400w)
    ss = img.get("srcset", "")
    best = None
    for part in ss.split(","):
        bits = part.strip().split()
        if len(bits) == 2 and bits[1].endswith("w"):
            w = int(bits[1][:-1])
            if best is None or w > best[0]:
                best = (w, bits[0])
    src = best[1] if best else img.get("src", "")
    return src if src.startswith("http") else BASE + src


def items(d):
    out = []
    for card in d.select("article.manga-update-card"):
        a = card.select_one("a.update-cover-link")
        slug = a["data-manga-slug"]
        prefix = a["href"].lstrip("/").split("-", 1)[0]
        title = card.select_one("h2.update-title a")["title"]
        ch = card.select_one("a.update-chapter-link")
        out.append(dict(slug=slug, prefix=prefix, title=title, cover=best_src(a.select_one("img")),
                        latest=ch.get_text(" ", strip=True).replace("◈", "").strip() if ch else ""))
    has_next = d.select_one('nav.modern-pagination a[title="Next Page"]') is not None
    return out, has_next


# ---- list
d = doc(BASE + "/manga-list.html?page=1&sort=last_update&sort_type=DESC")
lst, nxt = items(d)
print("LIST", len(lst), "next:", nxt)
for x in lst[:5]:
    print("  ", x)

# ---- details
slug = "one-piece-raw"
d = doc(f"{BASE}/hwms-{slug}.html")
title = d.select_one("h1.manga-detail-title").get_text(strip=True)
alt = d.select_one(".manga-detail-alt")
alt = alt.get_text(" ", strip=True).replace("Alternative:", "").strip() if alt else ""
meta = {}
for row in d.select(".manga-metadata-grid .meta-row"):
    b = row.select_one("b")
    if not b:
        continue
    k = b.get_text(strip=True).rstrip(":")
    links = [a.get_text(strip=True) for a in row.select("a")]
    meta[k] = links or [row.get_text(" ", strip=True).replace(b.get_text(strip=True), "").strip()]
genres = [(a.get_text(strip=True), a["href"]) for a in d.select(".manga-genres-cloud a.genre-tag-pill")]
desc = d.select_one("#synopsis-content").get_text(" ", strip=True)
poster = best_src(d.select_one(".manga-detail-poster img"))
print("\nDETAILS", title, "| alt:", alt)
print("  meta:", meta)
print("  genres:", genres)
print("  desc:", desc[:120])
print("  poster:", poster)
print("  bookmark data-cover:", d.select_one("#btn-manga-bookmark")["data-cover"])

# ---- chapters
chs = []
for a in d.select("#chapter-rows-container a.chapter-row"):
    name = a.select_one(".chapter-name-text").get_text(strip=True)
    t = a.select_one(".chapter-meta-col span[title]")["title"]
    ms = int(datetime.strptime(t, "%Y-%m-%d %H:%M:%S").replace(tzinfo=SITE_TZ).timestamp() * 1000)
    chs.append(dict(id=a["href"].lstrip("/").removesuffix(".html"), name=name, num=float(a["data-chapter"]), date=ms, t=t))
print("\nCHAPTERS", len(chs), "count pill:", d.select_one(".chapter-count-pill").get_text(strip=True))
for c in chs[:3] + chs[-2:]:
    print("  ", c)

# ---- pages
c = chs[0]
d = doc(f"{BASE}/{c['id']}.html")
pages = [i.get("data-src") or i.get("src") for i in d.select("img.reader-img")]
print("\nPAGES", len(pages))
for p in pages[:3]:
    print("  ", p)

# legacy "read-" prefix still serves the reader
rest = c["id"].split("-", 1)[1]
d2 = doc(f"{BASE}/read-{rest}.html")
print("  read- prefix pages:", len(d2.select("img.reader-img")))

# ---- images (no Referer: the app adds none for these hosts)
print("\nIMAGES")
for u in [x["cover"] for x in lst[:3]] + pages[:3]:
    r, b = fetch(u, rng=True)
    kind = "jpeg" if b[:3] == b"\xff\xd8\xff" else "png" if b[:4] == b"\x89PNG" else "webp" if b[:4] == b"RIFF" and b[8:12] == b"WEBP" else "gif" if b[:3] == b"GIF" else "NOT AN IMAGE"
    print("   ", r.status_code, kind, u[:110])
