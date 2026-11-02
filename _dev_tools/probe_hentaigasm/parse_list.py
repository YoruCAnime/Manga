"""Parse a saved hentaigasm list page the way the app would (regex here; Jsoup selectors in the spec).
Prints counts, sticky items, pagination, and per-item fields WITHOUT titles (ids, views, classes only).
python parse_list.py out/home.html [...]"""
import sys, re, html

ITEM = re.compile(r'<div id="post-(\d+)" class="(item cf item-post[^"]*)">(.*?)<!-- end #post-\1 -->', re.S)
for fn in sys.argv[1:]:
    t = open(fn, encoding='utf-8', errors='replace').read()
    # the main loop only: from loop-content to the end of #content
    a = t.find('loop-content')
    b = t.find('<!-- end #content -->', a)
    main = t[a:b if b > 0 else len(t)]
    items = ITEM.findall(main)
    print(f'== {fn}: {len(items)} items in main loop (all items on page: {len(ITEM.findall(t))})')
    for pid, cls, body in items[:40]:
        cats = re.findall(r'category-([\w-]+)', cls)
        tags = re.findall(r'tag-([\w-]+)', cls)
        img = re.search(r'<img src="([^"]+)"', body)
        views = re.search(r'class="views"><i class="count">([^<]*)', body)
        when = re.search(r'class="time">([^<]*)', body)
        href = re.search(r'class="clip-link"[^>]*href="([^"]+)"', body)
        print(f'  {pid} sticky={"sticky" in cls.split()} cats={len(cats)} tags={tags} views={views and views.group(1)} '
              f'time={when and when.group(1)} img_host={img and img.group(1).split("/")[2]} img_dir={img and img.group(1).split("/")[3]} '
              f'img_ext={img and img.group(1).rsplit(".", 1)[1]} hrefslug_ok={bool(href)}')
    pg = re.findall(r'<a class="(?:page-numbers|nextpostslink|last|larger page|page larger)[^"]*"[^>]*href="([^"]+)"', t)
    pg2 = re.search(r'<div class=["\']wp-pagenavi["\'][^>]*>(.*?)</div>', t, re.S)
    print('  pagenavi:', pg2 and re.sub(r'\s+', ' ', pg2.group(1))[:600])
    print('  page links:', sorted(set(re.findall(r'href="([^"]*/page/\d+/[^"]*)"', t)))[:12])
