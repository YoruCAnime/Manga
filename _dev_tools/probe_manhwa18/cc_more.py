"""manhwa18.cc: what orderby=latest means, author / artist pages, search sort."""
from bs4 import BeautifulSoup
from cc_lists import show, BASE
from probe import get

show("webtoons p1 default", BASE + "/webtoons/1")
show("webtoons p1 latest", BASE + "/webtoons/1?orderby=latest")
show("webtoons p1 rating", BASE + "/webtoons/1?orderby=rating")
show("artist page", BASE + "/artist/seokbong")
show("author page", BASE + "/author/singa")
show("raw p2 trending", BASE + "/raw/2?orderby=trending")
show("raw p2", BASE + "/raw/2")
# first chapter dates of the first 'latest' item, to see if it is a new title
s = BeautifulSoup(get(BASE + "/webtoons/1?orderby=latest").text, "lxml")
a = s.select_one("div.manga-item div.thumb a")
d = BeautifulSoup(get(BASE + a["href"]).text, "lxml")
lis = d.select("ul.row-content-chapter li")
print("latest#1", a["href"], len(lis), [t.text for t in d.select("span.chapter-time")][-2:])
