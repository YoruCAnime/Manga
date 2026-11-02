"""Find the numeric ids of the "ai generated" tag on nhentai (API) and HentaiFox (data-tags of its tag page)."""
import collections, json, re, urllib.parse, urllib.request

UA = "MangaCracker/1.0 (personal Android reader)"


def get(url, ua=UA):
    req = urllib.request.Request(url, headers={"User-Agent": ua})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8", "replace")


q = urllib.parse.quote('tag:"ai generated"')
j = json.loads(get(f"https://nhentai.net/api/v2/search?query={q}&page=1"))
items = j.get("result") or j.get("results") or j.get("galleries") or []
c = collections.Counter(t for it in items for t in it.get("tag_ids", []))
print("nhentai results", len(items), "most common ids", c.most_common(4))

html = get("https://hentaifox.com/tag/ai-generated/",
           "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")
rows = re.findall(r'data-tags="([^"]*)"', html)
c = collections.Counter(t for r in rows for t in r.split())
print("hentaifox cards", len(rows), "most common ids", c.most_common(4))
