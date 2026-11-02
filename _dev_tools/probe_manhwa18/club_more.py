"""manga18.club: bigger covers, author / artist filters, genre + sort, a [MOTION] title."""
import re
from probe import get, magic
from club_lists import show, BASE

for u in ["https://cdn.manga18.club/manga/moby-dick/cover/cover.jpg",
          "https://cdn.manga18.club/manga/moby-dick/cover/cover_thumb.jpg",
          "https://cdn.manga18.club/manga/moby-dick/cover/cover_thumb_2.webp"]:
    r = get(u, img=True)
    print(r.status_code, magic(r.content), len(r.content), r.headers.get("content-length"), r.headers.get("content-range"), u)

show("author", BASE + "/list-manga?author=Yansae")
show("artist", BASE + "/list-manga?artist=Yansae")
show("genre drama views p1", BASE + "/manga-list/drama?order_by=views")
show("genre drama name p1", BASE + "/manga-list/drama?order_by=name")
show("search + views", BASE + "/list-manga?search=wife&order_by=views")
show("list name", BASE + "/list-manga?order_by=name")
r = get(BASE + "/manhwa/motion-the-messiah-and-his-followers-uncensored")
ch = re.findall(r'href="(/manhwa/motion-the-messiah-and-his-followers-uncensored/chapter-[^"]+)"', r.text)
print("motion chapters", len(ch), ch[:2])
if ch:
    r = get(BASE + ch[0])
    print("motion chapter: slides_p_path" in r.text, "slides_p_path" in r.text, "<video" in r.text, re.findall(r'<video[^>]*>|\.mp4[^"\']*', r.text)[:5])
