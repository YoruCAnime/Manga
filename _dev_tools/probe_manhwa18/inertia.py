"""manhwa18.net is Laravel + Inertia: the page data is JSON in <div id="app" data-page="...">.
Usage: inertia.py FILE.html  -> writes FILE.json and prints the top-level props keys with a short view.
"""
import sys, json, html, re

src = sys.argv[1]
t = open(src, encoding="utf-8").read()
m = re.search(r'data-page="([^"]*)"', t)
if not m:
    print("no data-page")
    sys.exit()
d = json.loads(html.unescape(m.group(1)))
json.dump(d, open(src.rsplit(".", 1)[0] + ".json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print("component:", d.get("component"), "url:", d.get("url"), "version:", d.get("version"))


def show(v, depth=0, key=""):
    pad = "  " * depth
    if isinstance(v, dict):
        print(f"{pad}{key}: dict {list(v.keys())[:30]}")
        if depth < 3:
            for k, x in list(v.items())[:30]:
                show(x, depth + 1, k)
    elif isinstance(v, list):
        print(f"{pad}{key}: list[{len(v)}]")
        if v and depth < 3:
            show(v[0], depth + 1, "[0]")
    else:
        print(f"{pad}{key}: {str(v)[:120]!r}")


show(d.get("props"), 0, "props")
