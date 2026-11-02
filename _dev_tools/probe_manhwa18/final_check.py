"""Last checks: date formats on freshly updated titles, manhwa18.net tag / genre filters."""
import re
from bs4 import BeautifulSoup
from probe import get
from net_lists import summary

# manga18.club: the newest chapter's date cell on a title updated minutes ago
s = BeautifulSoup(get("https://manga18.club/manhwa/diaries-of-depravity-shorts").text, "lxml")
print("club dates", [li.select_one("p.chapter_info").text.strip() for li in s.select("div.chapter_box li")][:3])
# manhwa18.cc: a title whose newest chapter shows the 'new' picture
s = BeautifulSoup(get("https://manhwa18.cc/webtoon/the-seed-of-destiny-uncensored").text, "lxml")
print("cc dates", [(li.select_one("a.chapter-name").text.strip(), (li.select_one("span.chapter-time") or li).get_text(" ", strip=True)[:30])
                   for li in s.select("ul.row-content-chapter li")][:3])
summary("net tag older-woman top", "https://manhwa18.net/tag/older-woman?sort=top&page=1", "paginate")
summary("net genre adult completed", "https://manhwa18.net/genre/adult?sort=update&completed=1&page=1", "paginate")
summary("net tac-gia p1", "https://manhwa18.net/tac-gia/bangkki?page=1", "mangas")
