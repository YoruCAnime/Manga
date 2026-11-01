"""Probe every public home2 row + adult switches. Prints only counts / isAdult mix / field names (no titles for adult rows).
GET only, 1.2 s apart, no cookies kept."""
import json, os, time, requests

UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36'
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out')
B = 'https://atsu.moe/api/home2/'
COMMON = 'types=Manga,Manwha,Manhua,OEL&mediums=Comic'
CASES = [
    ('popular_daily', f'popular?offset=0&limit=3&timeframe=daily&{COMMON}'),
    ('popular_weekly', f'popular?offset=0&limit=3&timeframe=weekly&{COMMON}'),
    ('popular_all', f'popular?offset=0&limit=3&timeframe=all&{COMMON}'),
    ('popular_off40', f'popular?offset=40&limit=3&timeframe=daily&{COMMON}'),
    ('popular_lim100', f'popular?offset=0&limit=100&timeframe=daily&{COMMON}'),
    ('recentlyUpdated', f'recentlyUpdated?offset=0&limit=3&{COMMON}'),
    ('recentlyAdded', f'recentlyAdded?offset=0&limit=3&{COMMON}'),
    ('topRated', f'topRated?offset=0&limit=3&{COMMON}'),
    ('hotUpdates', f'hotUpdates?offset=0&limit=3&{COMMON}'),
    ('rising', f'rising?offset=0&limit=3&{COMMON}'),
    ('hotArrivals', f'hotArrivals?offset=0&limit=3&{COMMON}'),
    ('mostBookmarked', f'mostBookmarked?offset=0&limit=3&timeframe=weekly&{COMMON}'),
    ('mostTalkedAbout', f'mostTalkedAbout?offset=0&limit=3&timeframe=weekly&{COMMON}'),
    ('bingeWorthy', f'bingeWorthy?offset=0&limit=3&{COMMON}'),
    ('hiddenGems', f'hiddenGems?offset=0&limit=3&{COMMON}'),
    ('mostPolarizing', f'mostPolarizing?offset=0&limit=3&{COMMON}'),
    ('genreSpotlight', f'genreSpotlight?genre=Action&offset=0&limit=3&{COMMON}'),
    ('recommended', f'recommended?offset=0&limit=3&{COMMON}'),
    ('forYou', f'forYou?{COMMON}'),
    ('popular_incl', f'popular?offset=0&limit=5&timeframe=weekly&{COMMON}&includedTags=39,36'),
    ('popular_excl', f'popular?offset=0&limit=5&timeframe=weekly&{COMMON}&excludedTags=39,28,35,1769,1770'),
    ('popular_adult1', f'popular?offset=0&limit=20&timeframe=weekly&{COMMON}&adult=1'),
    ('popular_safe_cr', f'popular?offset=0&limit=20&timeframe=weekly&{COMMON}&contentRatings=Safe,Suggestive,Erotica,Pornographic'),
    ('popular_safe', f'popular?offset=0&limit=20&timeframe=weekly&{COMMON}'),
]

s = requests.Session()
s.headers.update({'User-Agent': UA, 'Accept': 'application/json'})
for i, (name, path) in enumerate(CASES):
    if i:
        time.sleep(1.2)
    r = s.get(B + path, timeout=30)
    line = f'## {name}: {r.status_code} {len(r.content)} B'
    try:
        js = r.json()
    except Exception:
        print(line, 'not json:', r.text[:200]); continue
    json.dump(js, open(os.path.join(OUT, 'h2_' + name + '.json'), 'w', encoding='utf8'), indent=1, ensure_ascii=False)
    items = js.get('items') if isinstance(js, dict) else None
    if items is None:
        print(line, 'keys:', list(js)[:10] if isinstance(js, dict) else type(js), json.dumps(js)[:300]); continue
    adult = sum(1 for x in items if x.get('isAdult'))
    keys = list(items[0]) if items else []
    first = [x['id'] for x in items[:3]]
    print(line, f'items={len(items)} adult={adult} ids={first} keys={keys}')
print('cookies:', s.cookies.keys())
