"""Write app/.../sources/HentaiFoxSource.kt from HentaiFoxSource.kt.in + out/tags_kotlin.txt (the embedded tag table)."""
import pathlib

HERE = pathlib.Path(__file__).parent
ROOT = HERE.parent.parent
src = (HERE / 'HentaiFoxSource.kt.in').read_text(encoding='utf-8')
table = (HERE / 'out' / 'tags_kotlin.txt').read_text(encoding='utf-8').strip()
assert '"""' not in table and '$' not in table
out = ROOT / 'app' / 'src' / 'main' / 'java' / 'com' / 'yoruc' / 'mangacracker' / 'sources' / 'HentaiFoxSource.kt'
out.write_text(src.replace('@@TAGS@@', table), encoding='utf-8', newline='\n')
print('wrote', out, len(table.splitlines()), 'tags')
