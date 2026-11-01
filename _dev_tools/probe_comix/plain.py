"""Plain HTTP (no browser, no cookies): which comix.to routes answer an OkHttp-like client?
python plain.py [UA=android|desktop|okhttp]  (1 request per ~1.2 s)"""
import sys, time, json, requests

UAS = {
    'android': 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36',
    'desktop': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36',
    'okhttp': 'okhttp/4.12.0',
}
ua = UAS[sys.argv[1] if len(sys.argv) > 1 else 'android']
S = requests.Session()
S.headers.update({'User-Agent': ua, 'Accept': 'application/json, text/plain, */*'})
B = 'https://comix.to'
URLS = [
    ('manga list latest', B + '/api/v1/manga?order[chapter_updated_at]=desc&page=1&limit=5', {}),
    ('manga list xrw', B + '/api/v1/manga?order[chapter_updated_at]=desc&page=1&limit=5', {'X-Requested-With': 'XMLHttpRequest', 'Referer': B + '/browse'}),
    ('manga list keyword', B + '/api/v1/manga?keyword=dragon&order[relevance]=desc&page=1&limit=3', {'X-Requested-With': 'XMLHttpRequest', 'Referer': B + '/browse'}),
    ('tags search', B + '/api/v1/tags/search?type=tag&q=vampire&limit=5', {'X-Requested-With': 'XMLHttpRequest', 'Referer': B + '/browse'}),
    ('single manga', B + '/api/v1/manga/50xvk', {'X-Requested-With': 'XMLHttpRequest', 'Referer': B + '/browse'}),
    ('chapters', B + '/api/v1/manga/50xvk/chapters?page=1&limit=20&order[number]=desc', {'X-Requested-With': 'XMLHttpRequest', 'Referer': B + '/title/50xvk-i-married-the-dragon-i-killed'}),
    ('chapter', B + '/api/v1/chapters/11576932', {'X-Requested-With': 'XMLHttpRequest'}),
    ('title html', B + '/title/50xvk-i-married-the-dragon-i-killed', {'Accept': 'text/html'}),
    ('home html', B + '/', {'Accept': 'text/html'}),
    ('browse html', B + '/browse', {'Accept': 'text/html'}),
]
for name, u, h in URLS:
    try:
        r = S.get(u, headers=h, timeout=25)
        t = r.text
        print(f'== {name}: {r.status_code} {r.headers.get("content-type")} {len(r.content)}B  cf-mitigated={r.headers.get("cf-mitigated")} server={r.headers.get("server")}')
        print('   ', t[:400].replace('\n', ' '))
        if 'initial-data' in t:
            print('    has script#initial-data')
    except Exception as e:
        print('== ERR', name, e)
    time.sleep(1.3)
