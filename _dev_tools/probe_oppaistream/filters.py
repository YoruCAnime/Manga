"""Parse the filter boxes of out/search.html (+ out/female_filters.html if present) -> out/filters.json + print."""
import re, json, pathlib, html

OUT = pathlib.Path(__file__).parent / 'out'
t = (OUT / 'search.html').read_text(encoding='utf-8')
res = {}
for m in re.finditer(r'<details id="([^"]+)"[^>]*>(.*?)</details>', t, re.S):
    res[m.group(1)] = [html.unescape(v) for v in re.findall(r"filter-option[^']*' data-value='([^']*)'", m.group(2))]
for box in ('studio', 'order'):
    m = re.search(r'class="drop-box box-%s .*?<div class=.box-actions' % box, t, re.S)
    res[box] = [html.unescape(v) for v in re.findall(r"filter-option[^']*' data-value='([^']*)'", m.group(0))]
    if box == 'order':
        res['order_labels'] = re.findall(r"data-value='([^']*)'><p class='s6 white'>([^<]*)<", m.group(0))
f = OUT / 'female_filters.html'
if f.exists():
    ft = f.read_text(encoding='utf-8')
    res['female'] = re.findall(r"data-key='([^']*)'[^>]*data-value='([^']*)'|data-value='([^']*)'[^>]*data-key='([^']*)'", ft)
for k, v in res.items():
    print(k, len(v), v if k != 'studio' else v[:10])
(OUT / 'filters.json').write_text(json.dumps(res, indent=1, ensure_ascii=False), encoding='utf-8')
