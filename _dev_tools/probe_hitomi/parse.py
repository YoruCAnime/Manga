"""Proof for the hitomi.la source, data host only (the website answers 404 from here).
Shows: latest pages 1 + 2 (25 ids each) with titles + covers, a tag list (include / exclude + minors subtracted),
text search through the galleries B-tree index, tag suggestions, one gallery's page URLs, and 3 covers + 3 pages
fetched (first 64 KB) and checked by magic bytes. ~1 request/s.
Run: python -X utf8 parse.py"""
import hashlib, json, os, re, struct, sys, time
import requests

UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
LTN = "https://ltn.gold-usergeneratedcontent.net"
DOMAIN2 = "gold-usergeneratedcontent.net"
H = {"User-Agent": UA, "Referer": "https://hitomi.la/"}
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
S = requests.Session()
PER_PAGE = 25
MINOR_TAGS = ["female:loli", "male:shota", "female:toddlercon", "male:toddlercon"]  # see spec section 7


def _get(url, rng=None, stream=False):
    h = dict(H)
    if rng:
        h["Range"] = "bytes=%d-%d" % rng
    r = S.get(url, headers=h, timeout=25, stream=stream)
    time.sleep(1.0)
    return r


# ---------- gg.js ----------

class GG:
    def __init__(self, js):
        self.default = int(re.search(r"var o = (\d+)", js).group(1))
        self.cases = {}
        pending = []
        for line in js.splitlines():
            m = re.match(r"\s*case (\d+):", line)
            if m:
                pending.append(int(m.group(1)))
                continue
            m = re.search(r"o = (\d+); break;", line)
            if m:
                for c in pending:
                    self.cases[c] = int(m.group(1))
                pending = []
        self.b = re.search(r"b: '(\d+)/'", js).group(1) + "/"

    def m(self, g):
        return self.cases.get(g, self.default)

    @staticmethod
    def s(h):  # last 3 hex: (..)(.)$ -> int(m2+m1, 16)
        return int(h[-1] + h[-3:-1], 16)


def page_url(gg, h, ext="webp"):
    # url_from_url_from_hash(gid, file, 'webp'): https://w{1+m}.<domain2>/<b><s>/<hash>.webp ; avif host 'a{1+m}'
    g = GG.s(h)
    sub = ("w" if ext == "webp" else "a") + str(1 + gg.m(g))
    return "https://%s.%s/%s%d/%s.%s" % (sub, DOMAIN2, gg.b, g, h, ext)


def thumb_url(gg, h, kind="webpbigtn", ext="webp"):
    # url_from_url_from_hash(gid, file, 'webpbigtn', 'webp', 'tn'): https://{a|b}tn.<domain2>/webpbigtn/<c>/<ba>/<hash>.webp
    g = GG.s(h)
    sub = chr(97 + gg.m(g)) + "tn"
    return "https://%s.%s/%s/%s/%s/%s.%s" % (sub, DOMAIN2, kind, h[-1], h[-3:-1], h, ext)


# ---------- nozomi lists ----------

def nozomi_url(area, tag, lang="all", popular=None):
    # index-all, popular/week-all, tag/female:x-all, tag/popular/week/female:x-all, artist/x-all ...
    if area is None:
        path = "%s-%s" % (tag, lang)                                    # index-all
    elif area == "popular":
        path = "popular/%s-%s" % (tag, lang)                            # popular/week-all
    elif popular:
        path = "%s/popular/%s/%s-%s" % (area, popular, tag, lang)       # tag/popular/week/female:x-all
    else:
        path = "%s/%s-%s" % (area, tag, lang)
    return LTN + "/" + requests.utils.quote(path) + ".nozomi"


def read_ids(url, page=None):
    """page = 1-based: Range read of 25 ids; None = whole file. Returns (ids, total)."""
    if page:
        a = (page - 1) * PER_PAGE * 4
        r = _get(url, rng=(a, a + PER_PAGE * 4 - 1))
    else:
        r = _get(url)
    if r.status_code == 404:
        return [], 0
    if r.status_code not in (200, 206):
        raise RuntimeError("HTTP %d %s" % (r.status_code, url))
    b = r.content
    ids = list(struct.unpack(">%di" % (len(b) // 4), b[: len(b) // 4 * 4]))
    cr = r.headers.get("Content-Range")
    total = int(cr.rsplit("/", 1)[1]) // 4 if cr else len(ids)
    return ids, total


def tag_area(tag):
    """'female:big breasts' -> ('tag','female:big breasts'); 'artist:shindol' -> ('artist','shindol'); 'full color' -> ('tag','full color')"""
    ns, _, name = tag.partition(":")
    if not name:
        return "tag", tag
    if ns in ("female", "male"):
        return "tag", tag
    if ns == "parody":
        ns = "series"
    return ns, name


# ---------- text search: galleriesindex B-tree ----------

def decode_node(b):
    pos = 0
    n = struct.unpack_from(">i", b, pos)[0]; pos += 4
    keys = []
    for _ in range(n):
        ks = struct.unpack_from(">i", b, pos)[0]; pos += 4
        keys.append(b[pos:pos + ks]); pos += ks
    nd = struct.unpack_from(">i", b, pos)[0]; pos += 4
    datas = []
    for _ in range(nd):
        off, ln = struct.unpack_from(">Qi", b, pos); pos += 12
        datas.append((off, ln))
    subs = list(struct.unpack_from(">17Q", b, pos))
    return keys, datas, subs


def text_ids(word, version):
    key = hashlib.sha256(word.encode("utf-8")).digest()[:4]
    idx = "%s/galleriesindex/galleries.%s.index" % (LTN, version)
    addr = 0
    for _ in range(20):
        r = _get(idx, rng=(addr, addr + 463))
        keys, datas, subs = decode_node(r.content)
        i = 0
        cmp = -1
        for i, k in enumerate(keys):
            cmp = (key > k) - (key < k)
            if cmp <= 0:
                break
        else:
            i = len(keys)
        if keys and cmp == 0:
            off, ln = datas[i]
            r = _get("%s/galleriesindex/galleries.%s.data" % (LTN, version), rng=(off, off + ln - 1))
            n = struct.unpack_from(">i", r.content, 0)[0]
            return list(struct.unpack_from(">%di" % n, r.content, 4))
        if not any(subs) or not subs[i]:
            return []
        addr = subs[i]
    return []


# ---------- galleries/<id>.js ----------

_ginfo = {}


def gallery(gid):
    if gid in _ginfo:
        return _ginfo[gid]
    r = _get("%s/galleries/%d.js" % (LTN, gid))
    if r.status_code != 200:
        return None
    t = r.text
    j = json.loads(t[t.index("{"):])
    _ginfo[gid] = j
    return j


def gallery_tags(j):
    out = []
    for t in j.get("tags") or []:
        ns = "female" if str(t.get("female")) == "1" else "male" if str(t.get("male")) == "1" else "tag"
        out.append("%s:%s" % (ns, t["tag"]))
    for key, ns, field in (("artists", "artist", "artist"), ("groups", "group", "group"), ("parodys", "parody", "parody"),
                           ("characters", "character", "character")):
        for t in j.get(key) or []:
            out.append("%s:%s" % (ns, t[field]))
    if j.get("language"):
        out.append("language:" + j["language"])
    if j.get("type"):
        out.append("type:" + j["type"])
    return out


def magic(b):
    if b[:3] == b"\xff\xd8\xff": return "jpeg"
    if b[:8] == b"\x89PNG\r\n\x1a\n": return "png"
    if b[:4] == b"RIFF" and b[8:12] == b"WEBP": return "webp"
    if b[4:12] in (b"ftypavif", b"ftypavis"): return "avif"
    if b[:6] in (b"GIF87a", b"GIF89a"): return "gif"
    return "?? " + b[:16].hex()


def fetch_head(url, referer=True):
    h = dict(H)
    if not referer:
        h.pop("Referer")
    r = S.get(url, headers=h, timeout=25, stream=True)
    data = b""
    for chunk in r.iter_content(8192):
        data += chunk
        if len(data) >= 65536:
            break
    r.close()
    time.sleep(1.0)
    return r.status_code, r.headers.get("content-type"), magic(data)


def show_list(title, ids, minors):
    print("--", title)
    shown = 0
    for gid in ids:
        if gid in minors:
            print("   %d  (minor tag: subtracted)" % gid)
            continue
        j = gallery(gid)
        if not j:
            print("   %d  (no galleries/<id>.js)" % gid)
            continue
        tags = gallery_tags(j)
        print("   %d  %-60.60s  %3d p  %s" % (gid, j.get("title"), len(j["files"]), thumb_url(GGI, j["files"][0]["hash"])))
        shown += 1
    return shown


def main():
    global GGI
    GGI = GG(_get(LTN + "/gg.js?_=%d" % int(time.time() * 1000)).text)
    print("gg.js: b=%s default=%d cases=%d (o values %s)" % (GGI.b, GGI.default, len(GGI.cases), sorted(set(GGI.cases.values()))))

    # minors: whole nozomi files of the hidden tags (as sets) - lists subtract these
    minors = set()
    for t in MINOR_TAGS:
        area, name = tag_area(t)
        ids, total = read_ids(nozomi_url(area, name))
        print("minor list %s: %d ids" % (t, len(ids)))
        minors |= set(ids)

    # latest pages 1 + 2 (only page 1 resolved fully to stay gentle; page 2 ids + 3 titles)
    p1, total = read_ids(nozomi_url(None, "index"), page=1)
    p2, _ = read_ids(nozomi_url(None, "index"), page=2)
    print("latest total=%d  page1=%d ids  page2=%d ids  overlap=%d" % (total, len(p1), len(p2), len(set(p1) & set(p2))))
    show_list("latest page 1", p1, minors)
    show_list("latest page 2 (first 6)", p2[:6], minors)

    # tag list: female:big breasts, English, minus full color (include / exclude by set maths on whole files)
    inc, _ = read_ids(nozomi_url("tag", "female:big breasts", "english"))
    exc, _ = read_ids(nozomi_url("tag", "full color", "all"))
    exs = set(exc) | minors
    res = [i for i in inc if i not in exs]
    print("tag search female:big breasts (english) - full color: %d -> %d" % (len(inc), len(res)))
    show_list("tag search page 1 (first 5)", res[:5], minors)

    # artist list (paged by Range)
    a, at = read_ids(nozomi_url("artist", "shindol"), page=1)
    print("artist:shindol total=%d page1=%d" % (at, len(a)))

    # text search via B-tree
    ver = _get(LTN + "/galleriesindex/version?_=%d" % int(time.time() * 1000)).text.strip()
    for w in ["maid", "tsunade"]:
        ids = text_ids(w, ver)
        print("text '%s' (index %s): %d ids, first %s" % (w, ver, len(ids), ids[:5]))

    # tag suggestions with counts
    r = _get("https://tagindex.hitomi.la/global/" + "/".join("maid") + ".json")
    print("suggest 'maid':", r.status_code, r.json()[:5])

    # one gallery's page URLs
    gid = next(i for i in p1 if i not in minors and len(gallery(i)["files"]) >= 3)
    j = gallery(gid)
    print("gallery %d '%s' type=%s lang=%s files=%d" % (gid, j["title"], j["type"], j["language"], len(j["files"])))
    print("   tags:", gallery_tags(j)[:12])
    pages = [page_url(GGI, f["hash"]) for f in j["files"]]
    for u in pages[:4]:
        print("  ", u)

    # 3 covers + 3 pages (first 64 KB)
    print("-- images")
    for gid in [i for i in p1 if i not in minors][:3]:
        u = thumb_url(GGI, gallery(gid)["files"][0]["hash"])
        print("   cover", fetch_head(u), u)
    for u in pages[:3]:
        print("   page ", fetch_head(u), u)
    f0 = j["files"][0]
    if f0.get("hasavif"):
        u = page_url(GGI, f0["hash"], "avif")
        print("   avif ", fetch_head(u), u)
    print("   page without Referer", fetch_head(pages[0], referer=False))
    print("   cover without Referer", fetch_head(thumb_url(GGI, f0["hash"]), referer=False))
    # wrong host letter (proves gg.m matters)
    g = GG.s(f0["hash"])
    wrong = pages[0].replace("//w%d." % (1 + GGI.m(g)), "//w%d." % (2 - GGI.m(g)))
    print("   page on the other host", fetch_head(wrong), wrong)


if __name__ == "__main__":
    main()
