"""nhentai v2 API probe (read-only GETs, anonymous, ~1 request / 4.5 s to stay far under the per-IP limits).
Content rule: every gallery list is filtered against the minors tag ids BEFORE anything is printed or saved,
and search queries carry the negations. -> out/*.json + printed summary.
python probe.py [step ...]   steps: base hide search lists detail images html"""
import sys, json, time, pathlib, requests

OUT = pathlib.Path(__file__).parent / 'out'
OUT.mkdir(exist_ok=True)
API = 'https://nhentai.net/api/v2'
UA = 'MangaCracker/0.1 (personal Android reader; probe)'
S = requests.Session()
S.headers.update({'User-Agent': UA, 'Accept': 'application/json'})
GAP = 4.5
_last = [0.0]
# minors-related tag slugs to look up (type tag); ids are filled in by step 'hide'
HIDE_SLUGS = ['lolicon', 'shotacon', 'toddlercon', 'oppai-loli', 'low-lolicon', 'low-shotacon', 'low-toddlercon',
              'loli', 'shota', 'school-age', 'child', 'kodomo-doushi']
HIDE_FILE = OUT / 'hide_tags.json'
NEG = ' '.join(f'-tag:"{s.replace("-", " ")}"' for s in ('lolicon', 'shotacon', 'toddlercon', 'oppai loli', 'low lolicon', 'low shotacon', 'kodomo doushi'))


def get(path, params=None, **kw):
    wait = _last[0] + GAP - time.time()
    if wait > 0:
        time.sleep(wait)
    r = S.get(path if path.startswith('http') else API + path, params=params, timeout=30, **kw)
    _last[0] = time.time()
    rl = {k: v for k, v in r.headers.items() if 'ratelimit' in k.lower() or k.lower() in ('retry-after', 'cf-cache-status', 'cache-control', 'server')}
    print(f'-- GET {r.url[:200]} -> {r.status_code} {r.headers.get("content-type")} {len(r.content)}B {rl}')
    return r


def hidden_ids():
    return set(json.loads(HIDE_FILE.read_text())['ids']) if HIDE_FILE.exists() else set()


def clean(items):
    """drop galleries carrying a minors tag; returns (kept, dropped_count)"""
    h = hidden_ids()
    if not h:
        raise SystemExit('run step hide first')
    kept = [g for g in items if not (set(g.get('tag_ids') or []) & h)]
    return kept, len(items) - len(kept)


def save(name, obj):
    (OUT / f'{name}.json').write_text(json.dumps(obj, indent=1, ensure_ascii=False), encoding='utf-8')


def step_base():
    for p in ('', '/cdn', '/config'):
        r = get(p)
        print('  ', r.text[:600])
        save('base' + p.replace('/', '_'), r.json() if r.ok else r.text)


def step_hide():
    found = {}
    for s in HIDE_SLUGS:
        r = get(f'/tags/tag/{s}')
        if r.ok:
            t = r.json()
            found[t['id']] = {'name': t['name'], 'slug': t['slug'], 'count': t.get('count'), 'url': t.get('url')}
            print('   tag', t['id'], t['name'], t.get('count'))
        else:
            print('   no tag', s, r.text[:120])
    HIDE_FILE.write_text(json.dumps({'ids': list(found), 'tags': found}, indent=1), encoding='utf-8')


def show_list(name, r):
    if not r.ok:
        print('  ', r.text[:400]); return []
    j = r.json()
    items = j['result'] if isinstance(j, dict) else j
    kept, dropped = clean(items)
    meta = {k: v for k, v in j.items() if k != 'result'} if isinstance(j, dict) else {'list': True}
    print(f'   {name}: {len(items)} items, {dropped} dropped (minors tags), meta={meta}')
    for g in kept[:3]:
        print('     ', g['id'], g['media_id'], g.get('num_pages'), g.get('thumbnail'), g.get('thumbnail_width'), g.get('thumbnail_height'), '|', g['english_title'][:60])
    save(name, {'meta': meta, 'kept_sample': kept[:3], 'dropped': dropped, 'keys': list(items[0]) if items else []})
    return kept


def step_search():
    show_list('search_basic', get('/search', {'query': f'english {NEG}', 'sort': 'popular-week', 'page': 1}))
    show_list('search_tags_and', get('/search', {'query': f'tag:"big breasts" tag:"glasses" -tag:"netorare" {NEG}', 'sort': 'popular', 'page': 1}))
    show_list('search_page2', get('/search', {'query': f'language:english {NEG}', 'sort': 'date', 'page': 2}))
    show_list('search_artist', get('/search', {'query': f'artist:"shindol" {NEG}', 'page': 1}))
    show_list('search_group_parody_char', get('/search', {'query': f'parody:"touhou project" character:"reimu hakurei" {NEG}', 'page': 1}))
    show_list('search_numeric', get('/search', {'query': f'pages:>100 favorites:>=1000 uploaded:<30d {NEG}', 'page': 1}))


def step_lists():
    show_list('latest', get('/galleries', {'page': 1, 'per_page': 25}))
    show_list('popular_today', get('/galleries/popular'))
    h = hidden_ids()
    # tag list: names + ids (metadata only)
    r = get('/tags/tag', {'sort': 'popular', 'page': 1, 'per_page': 100})
    if r.ok:
        j = r.json()
        print('   tags meta', {k: v for k, v in j.items() if k != 'result'})
        print('   first tags', [(t['id'], t['name'], t['count']) for t in j['result'][:8]])
        save('tags_tag_p1', {'meta': {k: v for k, v in j.items() if k != 'result'}, 'sample': j['result'][:5],
                             'minors_in_top100': [(t['id'], t['name']) for t in j['result'] if t['id'] in h]})
    for typ in ('artist', 'group', 'parody', 'character', 'language', 'category'):
        r = get(f'/tags/{typ}', {'sort': 'popular', 'per_page': 3})
        if r.ok:
            j = r.json(); print(f'   tags/{typ}:', j.get('num_pages'), j.get('total'), [(t['id'], t['name'], t['count']) for t in j['result']])


def step_detail():
    j = json.loads((OUT / 'search_basic.json').read_text(encoding='utf-8'))
    g = j['kept_sample'][0]
    r = get(f'/galleries/{g["id"]}', {'include': 'related'})
    d = r.json()
    if set(t['id'] for t in d['tags']) & hidden_ids():
        print('   gallery has a minors tag - not saved'); return
    rel, dropped = clean(d.get('related') or [])
    d['related'] = rel[:2]
    print('   keys', list(d))
    print('   title', d['title'], 'cover', d['cover'], 'thumb', d['thumbnail'], 'scanlator', d.get('scanlator'), 'upload', d['upload_date'])
    print('   tags', [(t['type'], t['name'], t['id']) for t in d['tags']][:30])
    print('   pages', len(d['pages']), d['pages'][:2], 'related kept', len(rel), 'dropped', dropped)
    d['pages'] = d['pages'][:3]
    save('gallery_detail', d)
    r = get(f'/galleries/{g["id"]}/related')
    if r.ok:
        rel, dropped = clean(r.json()['result'])
        print('   /related:', len(rel), 'kept', dropped, 'dropped', [x['id'] for x in rel[:5]])


def step_images():
    d = json.loads((OUT / 'gallery_detail.json').read_text(encoding='utf-8'))
    cdn = json.loads((OUT / 'base_cdn.json').read_text(encoding='utf-8'))
    thumb = cdn['thumb_servers'][0].rstrip('/') + '/' + d['thumbnail']['path']
    page = cdn['image_servers'][0].rstrip('/') + '/' + d['pages'][0]['path']
    for label, u, h in (('thumb no referer', thumb, {}), ('page no referer', page, {})):
        r = get(u, headers={'Accept': 'image/avif,image/webp,image/*,*/*', **h})
        print(f'   {label}: {u} magic={r.content[:12]!r}')
        if r.ok:
            (OUT / f'img_{label.split()[0]}.bin').write_bytes(r.content)


def step_html():
    # Cloudflare check on the website itself (one page, plain client, browser UA)
    r = get('https://nhentai.net/', headers={'User-Agent': 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36', 'Accept': 'text/html'})
    print('   html head:', r.text[:300].replace('\n', ' '), '| cf-mitigated', r.headers.get('cf-mitigated'))
    r = get('/search', {'query': f'english {NEG}', 'page': 1}, headers={'User-Agent': 'okhttp/4.12.0'})
    print('   api with okhttp UA:', r.status_code)


def step_extras():
    show_list('popular_week_english_tagged', get('/galleries/tagged', {'tag_id': 12227, 'sort': 'popular-week', 'page': 2, 'per_page': 25}))
    show_list('search_neg_only', get('/search', {'query': NEG, 'sort': 'popular-today', 'page': 1}))
    show_list('search_tag_unquoted', get('/search', {'query': f'tag:glasses language:english {NEG}', 'sort': 'popular-month', 'page': 1}))
    show_list('search_group', get('/search', {'query': f'group:"crimson comics" {NEG}', 'page': 1}))
    r = get('/search', {'query': f'english {NEG}', 'sort': 'bogus'})
    print('   bad sort ->', r.status_code, r.text[:200])
    r = get('/galleries/1')
    print('   gallery 1 ->', r.status_code, r.text[:120] if not r.ok else 'ok')
    r = get('/galleries/999999999')
    print('   missing gallery ->', r.status_code, r.text[:200])
    r = get('/tags/ids', {'ids': '19440,32341,2937'})
    print('   tags/ids ->', r.status_code, r.text[:400])


steps = sys.argv[1:] or ['base', 'hide', 'search', 'lists', 'detail', 'images', 'html']
for s in steps:
    print('#####', s)
    globals()['step_' + s]()
