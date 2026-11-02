"""manhwa18.net: does reject_genres work, is accept AND or OR, artist search, tag pages."""
from net_lists import summary, page

summary("incl adult", "https://manhwa18.net/tim-kiem?accept_genres=4", "mangas")
summary("incl adult excl manhwa", "https://manhwa18.net/tim-kiem?accept_genres=4&reject_genres=26", "mangas")
summary("incl adult+ntr", "https://manhwa18.net/tim-kiem?accept_genres=4,33", "mangas")
summary("excl manhwa only", "https://manhwa18.net/tim-kiem?reject_genres=26", "mangas")
# a title with artists
code, d = page("https://manhwa18.net/manga/taming-my-stepsister")
m = d["props"]["manga"]
print("artists", [(a.get("name"), a.get("slug")) for a in m["artists"]], "tags", [(t.get("name"), t.get("slug")) for t in m["tags"]][:10],
      "chars", [c.get("name") for c in m["characters"]][:5], "status", m["status_id"])
print("chapters", len(d["props"]["chapters"]), d["props"]["chapters"][:1])
if m["artists"]:
    a = m["artists"][0]
    summary("artist param", "https://manhwa18.net/tim-kiem?artist=" + a["name"], "mangas")
