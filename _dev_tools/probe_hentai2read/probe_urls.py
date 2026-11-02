"""hentai2read: which short URLs work (id without slug), and the image headers (cover + page, with / without Referer,
64 KB max each). Read-only, ~1 req/s."""
import json
from fetch import get
from inertia import page_json

B = 'https://hentai2read.com'

if __name__ == '__main__':
    for path in ('/original/33728', '/original/33728-x', '/doujinshi/33728-penetration-bare-fucking-family', '/books/33728',
                 '/reader/33728-penetration-bare-fucking-family/55650', '/reader/x/55650/page-1', '/reader/33728/55650/page-1'):
        r = get(B + path, allow_redirects=False)
        j = page_json(r.text) if r.status_code == 200 else None
        print('    ', path, '->', r.status_code, r.headers.get('location'), j and j['component'], j and len(j['props'].get('images') or []))
    print(' cache headers of a page:', {k: v for k, v in r.headers.items() if k.lower() in ('cache-control', 'vary', 'etag', 'expires')})
    for url in ('https://hentaicdn.com/hentai/cover/_S33728.jpg', 'https://hentaicdn.com/hentai/cover/73690-nhr2onw9.jpg',
                'https://hentaicdn.com/hentai/33728/11/ccdn0193.jpg', 'https://hentaicdn.com/hentai/33728/11/ccdn0194.jpg'):
        for ref in (None, 'https://hentai2read.com/', 'https://example.com/'):
            r, data = get(url, image=True, referer=ref)
            print('      ', {k: v for k, v in r.headers.items() if k.lower() in ('content-length', 'server', 'cache-control', 'access-control-allow-origin')})
