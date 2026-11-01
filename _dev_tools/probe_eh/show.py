"""Print trimmed markup from a saved page: python -X utf8 show.py out/FILE.html "css selector" [max_items] [max_chars]"""
import re, sys
from bs4 import BeautifulSoup

soup = BeautifulSoup(open(sys.argv[1], encoding='utf-8', errors='replace').read(), 'html.parser')
n = int(sys.argv[3]) if len(sys.argv) > 3 else 1
w = int(sys.argv[4]) if len(sys.argv) > 4 else 3000
els = soup.select(sys.argv[2])
print(len(els), 'matches')
for e in els[:n]:
    print(re.sub(r'\s+', ' ', str(e))[:w])
    print('--')
