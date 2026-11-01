import sys, subprocess, os
sys.path.insert(0, os.path.dirname(__file__))
from tsurl import url
SAFE = 'isAdult:=false'
I = 'id,title,type,status,year,genreIds,tagIds,releaseDate,dateAdded,mbRating,trending,views,authors'
cases = [
 ('s_sort_release', url('', SAFE, 'releaseDate:desc', per=2, include='id,title,releaseDate,released,year')),
 ('s_sort_released', url('', SAFE, 'released:desc', per=2, include='id,title,releaseDate,released,year')),
 ('s_sort_added', url('', SAFE, 'dateAdded:desc', per=2, include='id,title,dateAdded')),
 ('s_sort_rating', url('', SAFE + ' && mbRating:>0', 'mbRating:desc', per=2, include='id,title,mbRating')),
 ('s_sort_title', url('', SAFE, 'title:asc', per=2, include='id,title')),
 ('s_sort_chapters', url('', SAFE, 'chapterCount:desc', per=2, include='id,title,chapterCount')),
 ('s_tags', url('', SAFE + ' && tagIds:=`248` && tagIds:=`337` && tagIds:!=[`28`,`35`,`1769`,`1770`]', 'views:desc', per=2, include='id,title,tagIds')),
 ('s_author_filter', url('', SAFE + ' && authors:=[`ODA Eiichiro`]', 'views:desc', per=5, include='id,title,authors')),
 ('s_perpage250', url('', SAFE, 'views:desc', per=250, include='id')),
 ('s_badfilter', url('', 'nosuchfield:=1', 'views:desc', per=2)),
]
args = []
for n, u in cases: args += [n, u]
subprocess.run([sys.executable, '-X', 'utf8', os.path.join(os.path.dirname(__file__), 'probe.py')] + args)
