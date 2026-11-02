"""Pull the Inertia page JSON (<script data-page="app" type="application/json">) out of a saved page and
print its shape. python inertia.py NAME [jsonpath.dotted] -> out/NAME.page.json"""
import sys, json, pathlib, re, html

OUT = pathlib.Path(__file__).parent / 'out'


def page_json(text):
    m = re.search(r'<script data-page="app" type="application/json">(.*?)</script>', text, re.S)
    if not m:
        return None
    return json.loads(m.group(1))


def shape(o, depth=0, maxd=4, key=''):
    pad = '  ' * depth
    if isinstance(o, dict):
        print(f'{pad}{key}{{}} {len(o)} keys')
        if depth < maxd:
            for k, v in o.items():
                shape(v, depth + 1, maxd, k + ': ')
    elif isinstance(o, list):
        print(f'{pad}{key}[] {len(o)}')
        if o and depth < maxd:
            shape(o[0], depth + 1, maxd, '[0]: ')
    else:
        s = repr(o)
        print(f'{pad}{key}{s[:120]}')


if __name__ == '__main__':
    name = sys.argv[1]
    j = page_json((OUT / f'{name}.html').read_text(encoding='utf-8'))
    (OUT / f'{name}.page.json').write_text(json.dumps(j, indent=1, ensure_ascii=False), encoding='utf-8')
    node = j
    if len(sys.argv) > 2 and sys.argv[2]:
        for p in sys.argv[2].split('.'):
            node = node[int(p)] if isinstance(node, list) else node[p]
    shape(node, maxd=int(sys.argv[3]) if len(sys.argv) > 3 else 4)
