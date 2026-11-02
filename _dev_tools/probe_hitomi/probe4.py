"""Fourth probe: does the plain tn. host (as written in galleryblock HTML) serve thumbnails; the sorted tag lists
(area/popular/<key>/tag, area/date/published/tag); the 'blocked' flag; gg.js caching headers. ~1 request/s."""
import time, requests

UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
LTN = "https://ltn.gold-usergeneratedcontent.net"
H = {"User-Agent": UA, "Referer": "https://hitomi.la/"}


def head(url, extra=None):
    r = requests.get(url, headers={**H, **(extra or {})}, timeout=25, stream=True)
    b = r.raw.read(64)
    r.close()
    time.sleep(1)
    return r.status_code, r.headers.get("content-type"), r.headers.get("content-range"), b[:12]


h = "1f889e0ee502df973de0ec66ee966bc1103d05299ad2f2e5d0ebf40c06463031"
for host in ["tn", "atn", "btn"]:
    print(host, head("https://%s.gold-usergeneratedcontent.net/webpbigtn/1/03/%s.webp" % (host, h)))
print("avifbigtn btn", head("https://btn.gold-usergeneratedcontent.net/avifbigtn/1/03/%s.avif" % h))
print("webpsmalltn btn", head("https://btn.gold-usergeneratedcontent.net/webpsmalltn/1/03/%s.webp" % h))

for p in ["tag/popular/week/female:big%20breasts-english", "tag/date/published/female:big%20breasts-all",
          "artist/popular/month/shindol-all", "series/popular/year/touhou%20project-all", "type/popular/week/manga-all",
          "date/published-english", "popular/year-japanese"]:
    print(p, head(LTN + "/" + p + ".nozomi", {"Range": "bytes=0-7"}))

r = requests.get(LTN + "/gg.js", headers=H, timeout=20)
print("gg.js cache headers:", {k: v for k, v in r.headers.items() if k.lower() in ("cache-control", "expires", "last-modified", "etag", "age")})
