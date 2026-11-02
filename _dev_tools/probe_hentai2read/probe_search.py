"""hentai2read: search params and the Inertia JSON mode (X-Inertia header). Read-only GETs, ~1 req/s.
Prints item counts / paging / first titles. Items whose listing shows nothing about tags are not filtered here
(lists carry no tags); the 'without=6,23' guard (Lolicon, Shota) is on every search."""
import json, re, sys
from fetch import get, S
from inertia import page_json

B = 'https://hentai2read.com'
GUARD = 'without=6,23'


def summary(tag, j):
    p = j['props']
    b = p.get('books') or {}
    data = b.get('data') or []
    print(f'  [{tag}] component={j.get("component")} page={b.get("page")} last={b.get("lastPage")} total={b.get("total")} '
          f'next={(b.get("links") or {}).get("next")} n={len(data)} with={p.get("with")} without={p.get("without")} q={p.get("q")!r} '
          f'rest={p.get("rest")!r} typed={p.get("typed")} matching={str(p.get("matchingTags"))[:200]}')
    for d in data[:3]:
        print('     ', d['id'], d['type'], d['title'][:60], d.get('chapters'), d.get('pageCount'), 'tags' in d)


def html(url):
    r = get(url)
    j = page_json(r.text)
    summary(url.replace(B, ''), j)
    return j


if __name__ == '__main__':
    home = html(B + f'/search?{GUARD}')
    ver = home['version']
    html(B + f'/search?with=8&{GUARD}&sort=most-viewed')
    html(B + f'/search?with=8,113&{GUARD}&match=any&sort=top-rated&page=2')
    html(B + f'/search?q=mother&{GUARD}')
    html(B + f'/search?q=big+breasts+glasses&{GUARD}')
    html(B + f'/search?q=&type=doujinshi&status=completed&{GUARD}&sort=latest')
    html(B + f'/search?{GUARD}&page=1722')
    html(B + f'/search?{GUARD}&page=99999')
    # Inertia JSON mode: same props, no HTML around them
    r = get(B + f'/search?with=8&{GUARD}', headers={'X-Inertia': 'true', 'X-Inertia-Version': ver, 'X-Requested-With': 'XMLHttpRequest', 'Accept': 'text/html, application/xhtml+xml'})
    print('  inertia json:', r.status_code, r.headers.get('content-type'), r.headers.get('x-inertia'), len(r.content))
    if r.headers.get('content-type', '').startswith('application/json'):
        summary('inertia', r.json())
        open('out/search_inertia.json', 'w', encoding='utf-8').write(json.dumps(r.json(), indent=1, ensure_ascii=False))
    r = get(B + f'/search?with=8&{GUARD}', headers={'X-Inertia': 'true', 'X-Inertia-Version': 'old', 'X-Requested-With': 'XMLHttpRequest'})
    print('  inertia stale version:', r.status_code, r.headers.get('x-inertia-location'))
