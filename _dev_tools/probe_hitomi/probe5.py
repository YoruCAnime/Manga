"""Fifth probe: the gzip 'n/' whole-file paths the Kotlin search builds for each sort (status only, first bytes),
and a tagindex miss. ~1 request/s."""
import time, requests

UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
LTN = "https://ltn.gold-usergeneratedcontent.net"
H = {"User-Agent": UA, "Referer": "https://hitomi.la/", "Accept-Encoding": "gzip"}

for p in ["n/index-english", "n/popular/week-all", "n/date/published-english", "n/tag/popular/week/female:big%20breasts-english",
          "n/tag/date/published/female:big%20breasts-all", "n/artist/shindol-all", "n/type/doujinshi-english",
          "n/series/popular/month/touhou%20project-all", "n/tag/female:nonexistent%20tag%20xyz-all"]:
    r = requests.get(LTN + "/" + p + ".nozomi", headers=H, timeout=30, stream=True)
    r.raw.read(16)
    print(r.status_code, r.headers.get("content-encoding"), r.headers.get("content-length"), p)
    r.close()
    time.sleep(1)

r = requests.get("https://tagindex.hitomi.la/global/z/q/x/z/q.json", headers=H, timeout=20)
print("tagindex miss:", r.status_code, r.text[:60])
