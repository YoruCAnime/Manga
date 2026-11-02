"""sfmcompile.club WP REST probe: one GET, saves JSON to out/NAME, prints status, paging headers and a summary.

Usage: api.py PATH_AND_QUERY NAME     (PATH relative to /wp-json/wp/v2/)
"""
import sys, time, os, json
import requests

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out')
os.makedirs(OUT, exist_ok=True)
UA = ('Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) '
      'Chrome/130.0.0.0 Mobile Safari/537.36')


def main():
    path, name = sys.argv[1], sys.argv[2]
    time.sleep(1.1)
    url = 'https://sfmcompile.club/wp-json/wp/v2/' + path
    r = requests.get(url, headers={'User-Agent': UA, 'Accept': 'application/json'}, timeout=30)
    with open(os.path.join(OUT, name), 'wb') as f:
        f.write(r.content)
    with open(os.path.join(OUT, 'requests.log'), 'a', encoding='utf-8') as f:
        f.write(time.strftime('%H:%M:%S ') + f'API {r.status_code} {url}\n')
    print(r.status_code, len(r.content), 'bytes', 'total', r.headers.get('x-wp-total'), 'pages', r.headers.get('x-wp-totalpages'))
    try:
        d = r.json()
    except Exception:
        print(r.text[:500])
        return
    if isinstance(d, list):
        for p in d[:int(sys.argv[3]) if len(sys.argv) > 3 else 5]:
            if 'title' in p:
                emb = p.get('_embedded', {})
                terms = [[(t.get('taxonomy'), t.get('slug')) for t in grp] for grp in emb.get('wp:term', [])]
                print(p.get('id'), p['title']['rendered'], p.get('categories'), p.get('tags'), terms)
            else:
                print({k: p.get(k) for k in ('id', 'name', 'slug', 'count', 'parent')})
    else:
        print(json.dumps(d)[:800])


if __name__ == '__main__':
    main()
