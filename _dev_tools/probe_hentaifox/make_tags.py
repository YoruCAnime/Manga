"""Turn out/tags.tsv (from probe_tags.py) into the Kotlin tag table for HentaiFoxSource (tags used >= MIN times,
minors tags left out) -> out/tags_kotlin.txt, one "slug|count|label" per line."""
import pathlib

OUT = pathlib.Path(__file__).parent / 'out'
MIN = 150
SKIP = {'lolicon', 'shotacon', 'oppai-loli', 'low-lolicon', 'low-shotacon', 'toddlercon', 'kodomo-doushi', 'kodomo-only',
        'loli', 'shota', 'randoseru', 'age-regression', 'kindergarten-uniform', 'infantilism'}

rows = [l.split('\t') for l in (OUT / 'tags.tsv').read_text(encoding='utf-8').splitlines()[1:]]
lines = []
for slug, name, cnt in rows:
    if int(cnt) < MIN or slug in SKIP:
        continue
    if '|' in name:
        a, b = [x.strip() for x in name.split('|', 1)]
        name = f'{a} ({b})'
    lines.append(f'{slug}|{cnt}|{name}')
(OUT / 'tags_kotlin.txt').write_text('\n'.join(lines), encoding='utf-8')
print(len(lines), 'tags', sum(len(l) + 1 for l in lines), 'chars')
