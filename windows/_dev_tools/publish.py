r"""Copies the Windows app's files (the ones in git, minus the local-only notes) into the phone app's repo, folder
windows\, so they go to GitHub with it (YoruCAnime/Manga). Files there that are gone here are removed.
Run: python _dev_tools\publish.py [path of the phone repo, default ..\MangaCracker]"""
import os, shutil, subprocess, sys

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
phone = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(root), 'MangaCracker')
dst = os.path.join(phone, 'windows')
# site research notes and old parked phone screens stay local
LOCAL_ONLY = ('_dev_tools/specs/', '_parked/')

if not os.path.isdir(os.path.join(phone, '.git')):
    raise SystemExit('not the phone repo: ' + phone)
files = subprocess.run(['git', 'ls-files', '-z'], cwd=root, capture_output=True, check=True).stdout.decode('utf-8').split('\0')
files = [f for f in files if f and not f.startswith(LOCAL_ONLY)]
keep = set()
for f in files:
    src = os.path.join(root, f)
    out = os.path.join(dst, f)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    shutil.copyfile(src, out)
    keep.add(os.path.normcase(os.path.normpath(out)))
removed = 0
# git-ignored places (builds, the not-in-git player / RIFE files) are left alone
SKIP = {'build', '.gradle', '.kotlin', 'MangaCracker App', 'out'}
for d, dirs, names in os.walk(dst):
    dirs[:] = [x for x in dirs if x not in SKIP and not (x == 'windows' and os.path.basename(d) == 'appResources')]
    for n in names:
        p = os.path.normcase(os.path.normpath(os.path.join(d, n)))
        if p not in keep:
            os.remove(p); removed += 1
print('copied %d files to %s, removed %d' % (len(files), dst, removed))
