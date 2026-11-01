"""Write specs/atsu_tags.tsv from out/filters.json: kind, id, name, group, adult flag, safeCount, adultCount, hide_default."""
import json, os
HERE = os.path.dirname(os.path.abspath(__file__))
HIDE = {'28', '35', '1769', '1770', '3255', '6205', '6711', '3992', '4006', '5178', '6121', '5516', '6779',
        '2190', '4731', '4075', '4455', '3636', '6608'}
d = json.load(open(os.path.join(HERE, 'out', 'filters.json'), encoding='utf8'))
rows = ['kind\tid\tname\tgroup\tadult\tsafeCount\tadultCount\thide_default']
for g in d['genres']:
    rows.append(f"genre\t{g['id']}\t{g['name']}\tGenres\t\t\t\t")
for t in sorted(d['tags'], key=lambda t: (t['group'], t['name'].lower())):
    rows.append(f"tag\t{t['id']}\t{t['name']}\t{t['group']}\t{int(t['adult'])}\t{t['safeCount']}\t{t['adultCount']}\t{int(t['id'] in HIDE)}")
p = os.path.join(HERE, '..', 'specs', 'atsu_tags.tsv')
open(p, 'w', encoding='utf8', newline='\n').write('\n'.join(rows) + '\n')
print(len(rows) - 1, 'rows ->', os.path.abspath(p), sum(1 for t in d['tags'] if t['id'] in HIDE), 'hidden')
