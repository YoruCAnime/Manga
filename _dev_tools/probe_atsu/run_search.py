import sys, subprocess, os
sys.path.insert(0, os.path.dirname(__file__))
from tsurl import url
SAFE = 'isAdult:=false'
cases = [
 ('s_text', url('summit of the gods', SAFE, per=3)),
 ('s_text_noinc', url('one piece', SAFE, per=2, include='')),
 ('s_pop', url('', SAFE + ' && views:>0', 'views:desc', per=3)),
 ('s_trend', url('', SAFE, 'trending:desc', per=3)),
 ('s_multi', url('', SAFE + ' && genreIds:=`39` && genreIds:=`36` && genreIds:!=[`9`,`6`] && type:=[`Manwha`,`Manhua`] && status:=[`Completed`]', 'views:desc', per=3)),
 ('s_multi_p2', url('', SAFE + ' && genreIds:=`39` && genreIds:=`36` && genreIds:!=[`9`,`6`] && type:=[`Manwha`,`Manhua`] && status:=[`Completed`]', 'views:desc', page=2, per=3)),
]
args = []
for n, u in cases: args += [n, u]
subprocess.run([sys.executable, '-X', 'utf8', os.path.join(os.path.dirname(__file__), 'probe.py')] + args)
