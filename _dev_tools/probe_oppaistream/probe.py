"""oppai.stream probe (read-only GETs, anonymous, >= 1.5 s between requests).
Saves raw responses to out/ (git-ignored). Never downloads whole videos: stream checks use Range 0-65535.
python probe.py <step> [args]
steps: base | get URL [name] | range URL [referer]"""
import sys, time, pathlib, requests

OUT = pathlib.Path(__file__).parent / 'out'
OUT.mkdir(exist_ok=True)
UA = ('Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) '
      'Chrome/130.0.0.0 Mobile Safari/537.36')
S = requests.Session()
S.headers.update({'User-Agent': UA, 'Accept-Language': 'en-GB,en;q=0.9'})
GAP = 1.5
_last = [0.0]
COUNT = OUT / '_count.txt'


def get(url, save=None, **kw):
    wait = _last[0] + GAP - time.time()
    if wait > 0:
        time.sleep(wait)
    kw.setdefault('timeout', 30)
    r = S.get(url, **kw)
    _last[0] = time.time()
    n = int(COUNT.read_text()) + 1 if COUNT.exists() else 1
    COUNT.write_text(str(n))
    hd = {k: v for k, v in r.headers.items() if k.lower() in (
        'server', 'cf-ray', 'cf-mitigated', 'retry-after', 'content-range', 'cache-control', 'set-cookie', 'location',
        'access-control-allow-origin', 'expires', 'x-ratelimit-limit', 'x-ratelimit-remaining')}
    print(f'#{n} GET {r.url[:180]} -> {r.status_code} {r.headers.get("content-type")} {len(r.content)}B {hd}')
    if save:
        (OUT / save).write_bytes(r.content)
    return r


if __name__ == '__main__':
    step = sys.argv[1]
    if step == 'base':
        r = get('https://oppai.stream/', 'home.html', allow_redirects=False)
        print(r.text[:1500])
    elif step == 'get':
        url = sys.argv[2]
        name = sys.argv[3] if len(sys.argv) > 3 else None
        hdr = {}
        if len(sys.argv) > 4:
            hdr['Referer'] = sys.argv[4]
        r = get(url, name, headers=hdr)
        print(r.text[:800] if 'text' in (r.headers.get('content-type') or '') or 'json' in (r.headers.get('content-type') or '') else '')
    elif step == 'search':
        # search <name> key=value ...   (blacklist always gets Loli,Shota added)
        name = sys.argv[2]
        data = {'text': '', 'tags': '', 'blacklist': '', 'studios': '', 'order': 'recent', 'limit': '16', 'page': '1',
                'classes': 'smaller-episode-gap', 'character_filters': '{}'}
        for kv in sys.argv[3:]:
            k, v = kv.split('=', 1)
            data[k] = v
        bl = [b for b in data['blacklist'].split(',') if b]
        for b in ('Loli', 'Shota'):
            if b not in bl:
                bl.append(b)
        data['blacklist'] = ','.join(bl)
        wait = _last[0] + GAP - time.time()
        r = S.post('https://oppai.stream/api/search.php', data=data, timeout=30,
                   headers={'Referer': 'https://oppai.stream/search', 'X-Requested-With': 'XMLHttpRequest',
                            'Origin': 'https://oppai.stream'})
        n = int(COUNT.read_text()) + 1 if COUNT.exists() else 1
        COUNT.write_text(str(n))
        print(f'#{n} POST search {data} -> {r.status_code} {r.headers.get("content-type")} {len(r.content)}B')
        (OUT / name).write_bytes(r.content)
        import re
        print('results-num:', re.findall(r'results-num[^>]*>([^<]*)<', r.text))
        print('episodes:', len(re.findall(r'class="episode', r.text)), 'epids:', re.findall(r'epid="(\d+)"', r.text)[:20])
    elif step == 'count':
        # count key=value ...  -> prints ONLY the total and the leaked WHERE clause; nothing is saved or listed
        import re
        data = {'text': '', 'tags': '', 'blacklist': '', 'studios': '', 'order': 'recent', 'limit': '15', 'page': '1',
                'classes': 'smaller-episode-gap', 'character_filters': '{}'}
        for kv in sys.argv[2:]:
            k, v = kv.split('=', 1)
            data[k] = v
        wait = _last[0] + GAP - time.time()
        if wait > 0:
            time.sleep(wait)
        r = S.post('https://oppai.stream/api/search.php', data=data, timeout=30,
                   headers={'Referer': 'https://oppai.stream/search', 'X-Requested-With': 'XMLHttpRequest',
                            'Origin': 'https://oppai.stream'})
        _last[0] = time.time()
        n = int(COUNT.read_text()) + 1 if COUNT.exists() else 1
        COUNT.write_text(str(n))
        shown = {k: v for k, v in data.items() if v and k not in ('classes', 'limit', 'page') and v != '{}'}
        print(f'#{n} COUNT {shown} -> {r.status_code} total={re.findall(r"results-num[^>]*>([^<]*)<", r.text)} '
              f'rows={len(re.findall(r"epid=", r.text))}')
        print('   sql:', re.findall(r"<div style='display: none;'>([^<]*)</div>", r.text)[:1])
    elif step == 'post':
        # post URL name referer key=value ...
        url, name, ref = sys.argv[2:5]
        data = dict(kv.split('=', 1) for kv in sys.argv[5:])
        wait = _last[0] + GAP - time.time()
        r = S.post(url, data=data, timeout=30, headers={'Referer': ref, 'X-Requested-With': 'XMLHttpRequest',
                                                        'Origin': 'https://oppai.stream'})
        n = int(COUNT.read_text()) + 1 if COUNT.exists() else 1
        COUNT.write_text(str(n))
        print(f'#{n} POST {url} {data} -> {r.status_code} {r.headers.get("content-type")} {len(r.content)}B')
        (OUT / name).write_bytes(r.content)
        print(r.text[:1500])
    elif step == 'range':
        url = sys.argv[2]
        hdr = {'Range': 'bytes=0-65535'}
        if len(sys.argv) > 3:
            hdr['Referer'] = sys.argv[3]
        r = get(url, headers=hdr)
        data = r.content[:65536]
        (OUT / 'head.bin').write_bytes(data)
        print('first bytes:', data[:32].hex(' '))
        import re
        print('codec ids:', sorted(set(re.findall(rb'[VAS]_[A-Z0-9/]{2,20}', data)))[:10])
        print('webm/mp4 markers:', [m for m in (b'webm', b'ftyp', b'moov', b'avc1', b'av01', b'hvc1') if m in data])
        m = re.search(rb'\xb0\x82(..)\xba\x82(..)', data, re.S)  # EBML PixelWidth/PixelHeight (2-byte)
        if m:
            print('pixels:', int.from_bytes(m.group(1), 'big'), 'x', int.from_bytes(m.group(2), 'big'))
        try:
            t = data.decode('utf-8')
            if t.startswith('#EXTM3U'):
                print(t[:2000])
        except UnicodeDecodeError:
            pass
