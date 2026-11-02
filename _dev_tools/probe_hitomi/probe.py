"""hitomi.la data-host probe. Steps: python probe.py nozomi | gallery | versions | suggest | misc
Gentle: ~1 request/s. Saves replies into out/."""
import os, sys, time, struct, json, requests

UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
LTN = "https://ltn.gold-usergeneratedcontent.net"
H = {"User-Agent": UA, "Referer": "https://hitomi.la/"}
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
os.makedirs(OUT, exist_ok=True)
S = requests.Session()


def get(url, rng=None, headers=None, save=None):
    h = dict(H)
    if rng:
        h["Range"] = "bytes=%d-%d" % rng
    if headers:
        h.update(headers)
    r = S.get(url, headers=h, timeout=25)
    print(r.status_code, len(r.content), r.headers.get("content-type"), r.headers.get("content-range"),
          r.headers.get("content-encoding"), url)
    if save:
        with open(os.path.join(OUT, save), "wb") as f:
            f.write(r.content)
    time.sleep(1.0)
    return r


def ids(b):
    return list(struct.unpack(">%di" % (len(b) // 4), b[: len(b) // 4 * 4]))


def step_nozomi():
    for path in ["index-all", "index-english", "popular/week-all", "popular/today-all", "popular/month-all",
                 "popular/year-all", "tag/female:big breasts-all", "tag/female:loli-all", "tag/male:shota-all",
                 "artist/shindol-all", "series/touhou project-all", "character/hakurei reimu-all", "group/alice soft-all",
                 "type/doujinshi-all", "tag/full color-all", "tag/popular/week/female:big breasts-all", "date/published-all"]:
        r = get(LTN + "/" + path.replace(" ", "%20") + ".nozomi", rng=(0, 99))
        if r.status_code in (200, 206):
            print("   ids:", ids(r.content)[:6])
    # second page
    r = get(LTN + "/index-all.nozomi", rng=(100, 199))
    print("   page2 ids:", ids(r.content)[:6])
    # compressed n/ path (search uses it, whole file)
    r = get(LTN + "/n/tag/female:big%20breasts-all.nozomi", rng=(0, 99))
    print("   n/ ids:", ids(r.content)[:6])


def step_gallery(gid=None):
    if gid is None:
        r = get(LTN + "/index-all.nozomi", rng=(0, 11))
        gid = ids(r.content)[0]
    r = get(LTN + "/galleries/%d.js" % gid, save="gallery_%d.js" % gid)
    txt = r.text
    j = json.loads(txt[txt.index("{"):])
    j2 = dict(j)
    j2["files"] = j["files"][:2] + ["... %d files" % len(j["files"])]
    print(json.dumps(j2, ensure_ascii=False, indent=1)[:3500])
    get(LTN + "/galleryblock/%d.html" % gid, save="galleryblock_%d.html" % gid)


def step_versions():
    for n in ["galleriesindex", "tagindex", "languagesindex", "nozomiurlindex"]:
        r = get(LTN + "/%s/version?_=%d" % (n, int(time.time() * 1000)))
        print("   ", n, r.text[:60])


def step_suggest():
    for u in ["https://tagindex.hitomi.la/global.json", "https://tagindex.hitomi.la/global/b/i/g.json",
              "https://tagindex.hitomi.la/female/b/i/g.json"]:
        r = get(u, save=u.split("//")[1].replace("/", "_"))
        print("   ", r.text[:400])


def step_misc():
    get("https://hitomi.la/", save="site_root.html")
    get("https://hitomi.la/galleries/1.html")


if __name__ == "__main__":
    for s in sys.argv[1:]:
        print("=====", s)
        if s.startswith("gallery:"):
            step_gallery(int(s.split(":")[1]))
        else:
            globals()["step_" + s]()
