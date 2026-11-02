"""manhwa18.net: accept AND/OR, artist pages, tag pages, an animation title."""
from net_lists import summary, page

summary("incl ntr", "https://manhwa18.net/tim-kiem?accept_genres=33", "mangas")
summary("incl ntr+comedy", "https://manhwa18.net/tim-kiem?accept_genres=33,129", "mangas")
summary("incl comedy", "https://manhwa18.net/tim-kiem?accept_genres=129", "mangas")
found = None
for slug in ["milf-exchange-plan", "panty-note-engsub", "hero-villain", "her-bodyguard-uncensored"]:
    code, d = page("https://manhwa18.net/manga/" + slug)
    m = d["props"]["manga"]
    print(slug, "artists", [(a.get("name"), a.get("slug")) for a in m["artists"]], "tags", [(t.get("name"), t.get("slug")) for t in m["tags"]][:8])
    if m["artists"] and not found:
        found = m["artists"][0]
    if found and m["tags"]:
        break
if found:
    code, d = page("https://manhwa18.net/tac-gia/" + found["slug"])
    print("artist page", code, d and d.get("component"), d and [k for k in d["props"] if k not in ("adSlots",)][-6:])
    for k in ("paginate", "mangas"):
        if d and k in d["props"]:
            print("  ", k, d["props"][k]["total"], [x["slug"] for x in d["props"][k]["data"]][:5])
code, d = page("https://manhwa18.net/manga/ai-animation-a-dangerous-deal-and-the-woman-next-door-animation")
m = d["props"]["manga"]
print("anim genres", [g["name"] for g in m["genres"]], "chapters", d["props"]["chapters"][:1])
