"""First look at each source: status + the start of the reply."""
import sys, requests

UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36'
S = requests.Session(); S.headers['User-Agent'] = UA

URLS = sys.argv[1:] or [
    'https://atsu.moe/api/home2/updates?offset=0&limit=3',
    'https://atsu.moe/api/search/page?query=dragon&page=0',
    'https://atsu.moe/api/infinite/trending?page=0',
    'https://comix.to/api/v2/manga?keyword=dragon&limit=2',
    'https://comix.to/browser',
    'https://nhentai.net/api/galleries/search?query=english&page=1',
    'https://nhentai.net/api/v2/search?query=english&page=1',
    'https://e-hentai.org/?f_search=english',
    'https://danbooru.donmai.us/posts.json?limit=2&tags=rating:g',
    'https://gelbooru.com/index.php?page=dapi&s=post&q=index&json=1&limit=2',
]
for u in URLS:
    try:
        r = S.get(u, timeout=20)
        print('==', r.status_code, u, r.headers.get('content-type'), len(r.content))
        print(r.text[:600].replace('\n', ' '))
    except Exception as e:
        print('==', 'ERR', u, e)
