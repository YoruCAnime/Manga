"""Second probe: tag flag format in galleries/<id>.js, minors tag names in the suggestion index, character / group
nozomi names, language list. ~1 request/s."""
import json, os, time, requests

UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
LTN = "https://ltn.gold-usergeneratedcontent.net"
H = {"User-Agent": UA, "Referer": "https://hitomi.la/"}
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
S = requests.Session()


def get(url, **kw):
    r = S.get(url, headers={**H, **kw.pop("headers", {})}, timeout=25, **kw)
    time.sleep(1.0)
    return r


def sugg(field, term):
    enc = {" ": "_", "/": "slash", ".": "dot"}
    u = "https://tagindex.hitomi.la/%s/%s.json" % (field, "/".join(enc.get(c, c) for c in term))
    r = get(u)
    return r.status_code, (r.json() if r.status_code == 200 else r.text[:80])


r = get(LTN + "/galleries/4298581.js")
t = r.text
open(os.path.join(OUT, "gallery_4298581.js"), "w", encoding="utf-8").write(t)
j = json.loads(t[t.index("{"):])
print("tags sample:", json.dumps(j["tags"][:4], ensure_ascii=False))
print("artists:", j.get("artists"), "groups:", j.get("groups"), "parodys:", j.get("parodys"), "characters:", j.get("characters"))
print("file keys:", sorted(j["files"][0].keys()))

for term in ["lol", "loli", "shota", "toddler", "child", "kodomo", "oppai loli", "low", "young", "age"]:
    print("global", term, sugg("global", term))

print("character reimu", sugg("character", "hakurei reimu"))
print("group alice", sugg("group", "alice"))
print("language e", sugg("language", "e"))
print("artist shin", sugg("artist", "shin"))
print("series touh", sugg("series", "touh"))
print("type d", sugg("type", "d"))
print("global empty", get("https://tagindex.hitomi.la/global.json").status_code)

for p in ["character/hakurei%20reimu-all", "group/alice%20soft-all", "type/manga-all", "type/artistcg-all",
          "type/gamecg-all", "type/imageset-all", "type/anime-all", "popular/week-english", "index-japanese"]:
    r = get(LTN + "/" + p + ".nozomi", headers={"Range": "bytes=0-7"})
    print(r.status_code, r.headers.get("content-range"), p)
