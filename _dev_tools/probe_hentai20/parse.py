"""hentai20.io: the selectors the app uses, checked against live pages.
Prints a list page, a search page, a details page, the chapter list, page-image URLs (ts_reader JSON and #readerarea),
and fetches 3 covers + 3 pages (64 KB each)."""
import json
import re
from datetime import datetime, timezone
from bs4 import BeautifulSoup
from probe import fetch

BASE = "https://hentai20.io"


def doc(url):
    r, b = fetch(url)
    assert r.status_code == 200, r.status_code
    return BeautifulSoup(b.decode("utf-8", "replace"), "lxml"), b.decode("utf-8", "replace")


def items(d):
    out = []
    for a in d.select(".listupd .bs .bsx > a"):
        slug = a["href"].rstrip("/").rsplit("/", 1)[-1]
        img = a.select_one("img")
        ch = a.select_one(".epxs")
        out.append(dict(slug=slug, title=a.get("title") or a.select_one(".tt").get_text(strip=True),
                        cover=img.get("data-src") or img.get("src"), latest=ch.get_text(strip=True) if ch else ""))
    nxt = d.select_one(".pagination a.next, .hpage a.r")
    return out, nxt is not None


for url in (BASE + "/manga/?order=popular&page=1", BASE + "/manga/?genre%5B%5D=7&genre%5B%5D=-6&order=update&page=2", BASE + "/page/1/?s=teacher"):
    d, _ = doc(url)
    lst, nxt = items(d)
    print("LIST", url[19:], len(lst), "next:", nxt)
    for x in lst[:3]:
        print("  ", x)

# ---- genre list from the filter form
d, _ = doc(BASE + "/manga/")
genres = []
for i in d.select('.quickfilter input[name="genre[]"]'):
    lab = d.select_one(f'label[for="{i.get("id")}"]')
    genres.append((i["value"], lab.get_text(strip=True) if lab else "?"))
print("\nGENRES", len(genres), genres)

# ---- details
slug = "milf-hunting-in-another-world"
d, s = doc(f"{BASE}/manga/{slug}/")
title = d.select_one(".seriestuheader h1.entry-title").get_text(strip=True)
alt = d.select_one(".seriestualt")
info = {tr.select("td")[0].get_text(strip=True): tr.select("td")[-1].get_text(" ", strip=True) for tr in d.select("table.infotable tr")}
gen = [a.get_text(strip=True) for a in d.select(".seriestugenre a")]
desc = d.select_one(".seriestuhead .entry-content").get_text(" ", strip=True)
cover = d.select_one(".seriestucontl .thumb img")
print("\nDETAILS", title, "| alt:", alt and alt.get_text(strip=True))
print("  info:", info)
print("  genres:", gen)
print("  desc:", desc[:120])
print("  cover:", cover.get("data-src") or cover.get("src"))

# ---- chapters
chs = []
for li in d.select("#chapterlist li"):
    a = li.select_one("a")
    date = li.select_one(".chapterdate").get_text(strip=True)
    ms = int(datetime.strptime(date, "%B %d, %Y").replace(tzinfo=timezone.utc).timestamp() * 1000) if date else 0
    chs.append(dict(id=a["href"].rstrip("/").rsplit("/", 1)[-1], name=li.select_one(".chapternum").get_text(strip=True),
                    num=float(li["data-num"]) if re.fullmatch(r"[\d.]+", li.get("data-num", "")) else -1, date=ms, raw=date))
print("\nCHAPTERS", len(chs))
for c in chs[:3] + chs[-2:]:
    print("  ", c)
odd = [c for c in chs if c["num"] < 0]
print("  odd data-num:", odd[:5])

# ---- pages
c = chs[0]
d, s = doc(f"{BASE}/{c['id']}/")
m = re.search(r"ts_reader\.run\((\{.*?\})\);", s, re.S)
j = json.loads(m.group(1))
src = j["sources"][0]["images"]
area = [i.get("data-src") or i.get("src") for i in d.select("#readerarea img")]
print("\nPAGES ts_reader:", len(src), "servers:", [x["source"] for x in j["sources"]], "| #readerarea:", len(area), "same:", src == area)
for p in src[:3]:
    print("  ", p)

print("\nIMAGES")
for u in [x["cover"] for x in lst[:3]] + src[:3]:
    r, b = fetch(u, rng=True)
    kind = "jpeg" if b[:3] == b"\xff\xd8\xff" else "png" if b[:4] == b"\x89PNG" else "webp" if b[:4] == b"RIFF" and b[8:12] == b"WEBP" else "gif" if b[:3] == b"GIF" else "NOT AN IMAGE"
    print("   ", r.status_code, kind, u[:110])
