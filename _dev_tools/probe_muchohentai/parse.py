"""Parse saved list pages the way the app would (Jsoup-like selectors via regex/html.parser).

Usage: parse.py FILE...   -> per file: item count, pagination, and per item: id, has-hidden-tag, ep no,
                             lang, thumb url, series slugs, tag slugs. Items with a hidden tag print only 'HIDDEN'.
Titles are not printed (only counts / slugs), so nothing hidden is described.
"""
import sys, re, html

HIDE = {'lolicon', 'shotacon'}
ITEM = re.compile(r'<div id="post-(\d+)" class="item cf item-video ([^"]*)">(.*?)<!-- end #post-\1 -->', re.S)
EP = re.compile(r'\bEpisode\s+(\d+(?:\.\d+)?)', re.I)


def main():
    for fn in sys.argv[1:]:
        t = open(fn, encoding='utf-8', errors='replace').read()
        items = ITEM.findall(t)
        pages = re.findall(r'href="([^"]*/page/(\d+)[^"]*)"', t)
        last = max([int(p[1]) for p in pages], default=1)
        cur = re.search(r'<span class="current">(\d+)</span>|<span aria-current="page" class="current">(\d+)', t)
        print(f'## {fn}: {len(items)} items, last page link {last}, current {cur.group(0) if cur else None}')
        if pages:
            print('   page link sample:', pages[0][0])
        for pid, cls, body in items:
            tags = re.findall(r'\btag-([a-z0-9-]+)', cls)
            cats = re.findall(r'\bcategory-([a-z0-9%-]+)', cls)
            if HIDE & set(tags):
                print(f'   {pid} HIDDEN')
                continue
            title = html.unescape(re.search(r'title="([^"]*)"', body).group(1))
            ep = EP.search(title)
            lang = ('es' if 'Español' in title else 'en-sub' if 'English Subbed' in title else
                    'raw' if title.endswith('Raw') else 'pv' if '【PV】' in title else '?')
            img = re.search(r'<img src="([^"]*)"', body)
            print(f'   {pid} ep={ep.group(1) if ep else None} lang={lang} img={img.group(1) if img else None}'
                  f' cats={cats} tags={tags}')


if __name__ == '__main__':
    main()
