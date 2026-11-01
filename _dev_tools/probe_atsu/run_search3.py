import sys, subprocess, os
sys.path.insert(0, os.path.dirname(__file__))
from tsurl import url
u1 = url('', 'isAdult:=false && genreIds:=`39` && genreIds:=`36` && genreIds:!=[`9`,`6`] && tagIds:!=[`28`,`35`,`1769`,`1770`] && type:=[`Manwha`,`Manhua`] && status:=[`Completed`]', 'views:desc', per=10, include='id,genreIds,tagIds,type,status')
u2 = url('dragon', 'isAdult:=false', None, per=5, include='id,title,views') + '&sort_by=views%3Adesc'
subprocess.run([sys.executable, '-X', 'utf8', os.path.join(os.path.dirname(__file__), 'probe.py'), 'verify_multi', u1, 'text_sorted', u2])
