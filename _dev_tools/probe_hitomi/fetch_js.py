"""Fetch the site's own JS from the data host (gentle, ~1 req/s) and save into out/."""
import os, sys, time, requests

UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
H = {"User-Agent": UA, "Referer": "https://hitomi.la/"}
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
os.makedirs(OUT, exist_ok=True)

names = sys.argv[1:] or ["gg.js", "common.js", "results.js", "galleryblock.js", "searchlib.js", "search.js",
                         "gallery.js", "reader.js", "tagindex.js", "jquery.min.js"]
for n in names:
    url = n if n.startswith("http") else "https://ltn.gold-usergeneratedcontent.net/" + n
    try:
        r = requests.get(url, headers=H, timeout=20)
        print(r.status_code, len(r.content), r.headers.get("content-type"), url)
        if r.status_code == 200:
            with open(os.path.join(OUT, url.rsplit("/", 1)[-1].replace("?", "_")), "wb") as f:
                f.write(r.content)
    except Exception as e:
        print("ERR", url, e)
    time.sleep(1.0)
