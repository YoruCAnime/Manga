"""hentai2read: a doujinshi + a brand-new title (groups, dates), Inertia JSON through the id-only redirect,
suggest kinds, artist / parody pages. Read-only, ~1 req/s."""
import json
from fetch import get
from inertia import page_json

B = 'https://hentai2read.com'
HIDE = {6, 23}


def ver():
    return page_json(get(B + '/search?without=6,23').text)['version']


def ij(path, v):
    r = get(B + path, headers={'X-Inertia': 'true', 'X-Inertia-Version': v, 'X-Requested-With': 'XMLHttpRequest'})
    print('    status', r.status_code, r.headers.get('content-type'), 'final', r.url, 'hist', [h.status_code for h in r.history])
    return r.json() if r.headers.get('content-type', '').startswith('application/json') else None


if __name__ == '__main__':
    v = ver()
    for bid in ('57127', '73690'):
        j = ij(f'/original/{bid}', v)
        p = j['props']
        b = p['book']
        tags = {t['id'] for t in b['tags']}
        print('  ', bid, j['component'], b['title'], '| alt', b.get('alt'), '| status', b['status'], '| type', b['type'], '| hidden' if tags & HIDE else '')
        print('     tag types', sorted({t['type'] for t in b['tags']}), '| groups', [(g['label'], g['type'], [t['name'] for t in g['tags']]) for g in b['groups']])
        print('     desc', (b.get('description') or '')[:150], '| stats', p['stats'])
        print('     chapters', [(c['id'], c['number'], c['title'][:30], c['date'], c['length'], c['status']) for c in p['chapters'][:4]])
        open(f'out/detail_{bid}.json', 'w', encoding='utf-8').write(json.dumps(j, indent=1, ensure_ascii=False))
    j = ij('/reader/57127/' + str(json.load(open('out/detail_57127.json', encoding='utf-8'))['props']['chapters'][0]['id']) + '/page-1', v)
    print('   reader json:', j['component'], len(j['props']['images']), j['props']['images'][:2], j['props']['avif'][:2], j['props']['sizes'][:2])
    for q in ('loli', 'shota', 'touhou', 'naruto'):
        r = get(B + '/search/suggest?q=' + q, headers={'Accept': 'application/json'})
        s = r.json()
        print('   suggest', q, [(t['name'], t['type']) for t in s['tags']], 'complete', s.get('complete'))
    # artist page = a listing too
    r = get(B + '/artist/2194-gotoh-juan')
    j = page_json(r.text)
    b = j['props'].get('books') or {}
    print('   artist page', j['component'], b.get('total'), (b.get('links') or {}).get('next'), [k for k in j['props'] if k not in ('ads', 'promos')][-12:])
    r = get(B + '/search?q=gotoh+juan&without=6,23')
    j = page_json(r.text)
    print('   search artist name', j['props']['books']['total'], j['props'].get('typed'), str(j['props'].get('matchingTags'))[:300])
