"""Print trimmed copies of saved probe responses (lists cut to N items, long strings cut) for the spec."""
import json, os, sys

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out')


def trim(v, n):
    if isinstance(v, dict):
        return {k: trim(x, n) for k, x in v.items()}
    if isinstance(v, list):
        t = [trim(x, n) for x in v[:n]]
        if len(v) > n:
            t.append(f'... {len(v) - n} more')
        return t
    if isinstance(v, str) and len(v) > 90:
        return v[:90] + '...'
    return v


for spec in sys.argv[1:]:
    name, _, n = spec.partition(':')
    d = json.load(open(os.path.join(OUT, name + '.json'), encoding='utf8'))
    print('=====', name)
    print(json.dumps(trim(d, int(n or 2)), indent=1, ensure_ascii=False))
