r"""Installs the built app (build/compose/binaries/main/app/MangaCracker) WHILE the old one may still be running: each
build goes into its own folder "MangaCracker App\v<date-time>", "MangaCracker App\current.txt" names it, and the
Desktop shortcut points at it. A running copy notices current.txt and switches over by itself at a quiet moment
(platform/SelfUpdate.kt). Old folders are removed once nothing in them is in use (a folder with an open file can't be
renamed on Windows, so that's the test). Run after `gradlew createDistributable` (or use "Build and install.bat").
install.py [install folder] [--no-shortcut]   (tests install into their own folder without touching the shortcut)"""
import os, shutil, stat, subprocess, sys, time

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
src = os.path.join(root, 'build', 'compose', 'binaries', 'main', 'app', 'MangaCracker')
args = [a for a in sys.argv[1:] if not a.startswith('--')]
dst = args[0] if args else os.path.join(root, 'MangaCracker App')
NO_WINDOW = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
if not os.path.isfile(os.path.join(src, 'MangaCracker.exe')):
    raise SystemExit('build first: gradlew createDistributable')
# a build older than the code would be installed under a new name with the old code still in it
built = min(os.path.getmtime(os.path.join(src, 'app', n)) for n in os.listdir(os.path.join(src, 'app')) if n.startswith('MangaCracker Windows-'))
code = [os.path.join(root, 'build.gradle.kts')] + [os.path.join(d, n) for d, _, names in os.walk(os.path.join(root, 'src', 'main')) for n in names]
if max(os.path.getmtime(p) for p in code) > built:
    raise SystemExit('the build is older than the code: run gradlew createDistributable first')
os.makedirs(dst, exist_ok=True)

stamp = time.strftime('v%Y%m%d-%H%M%S')
new = os.path.join(dst, stamp)
rc = subprocess.call(['robocopy', src, new, '/E', '/NFL', '/NDL', '/NJH', '/NJS', '/NP'], creationflags=NO_WINDOW)
exe = os.path.join(new, 'MangaCracker.exe')
if rc >= 8 or not os.path.isfile(exe):
    raise SystemExit('copy failed (robocopy %d)' % rc)
# the switch: written last, in one step
tmp = os.path.join(dst, 'current.txt.tmp')
with open(tmp, 'w', encoding='utf-8') as f:
    f.write(stamp)
os.replace(tmp, os.path.join(dst, 'current.txt'))


def rmtree(path):
    """shutil.rmtree that also removes read-only files (some of Java's runtime files are)."""
    def again(fn, p, _):
        try:
            os.chmod(p, stat.S_IWRITE)
            fn(p)
        except OSError:
            pass
    shutil.rmtree(path, onerror=again)


def gone_if_free(path):
    """Removes a folder only when nothing in it is open (a running copy keeps it)."""
    trash = path + '.del'
    try:
        os.rename(path, trash)
    except OSError:
        return False
    rmtree(trash)
    return True


kept = []
for name in os.listdir(dst):
    p = os.path.join(dst, name)
    if name == stamp or not os.path.isdir(p):
        continue
    if name.endswith('.del'):
        rmtree(p)
    elif name.startswith('v') and not gone_if_free(p):
        kept.append(name)
# the old one-folder layout (before 2026-11-03): app\ + runtime\ + MangaCracker.exe straight in the install folder
if os.path.isdir(os.path.join(dst, 'app')) or os.path.isfile(os.path.join(dst, 'MangaCracker.exe')):
    if not os.path.isdir(os.path.join(dst, 'app')) or gone_if_free(os.path.join(dst, 'app')):
        rmtree(os.path.join(dst, 'runtime'))
        for n in ('MangaCracker.exe', 'MangaCracker.ico'):
            try:
                os.chmod(os.path.join(dst, n), stat.S_IWRITE)
                os.remove(os.path.join(dst, n))
            except OSError:
                pass
    else:
        kept.append('(the copy before versions)')

if '--no-shortcut' not in sys.argv:
    # the shortcut's icon sits next to the versions (a fresh path, so Windows' icon cache can't show an old picture)
    ico = os.path.join(dst, 'MangaCracker-%s.ico' % stamp)
    shutil.copyfile(os.path.join(root, 'appIcon', 'icon.ico'), ico)
    for n in os.listdir(dst):
        if n.startswith('MangaCracker-v') and n.endswith('.ico') and n != os.path.basename(ico):
            try:
                os.remove(os.path.join(dst, n))
            except OSError:
                pass
    lnk = os.path.join(os.path.expanduser('~'), 'Desktop', 'MangaCracker.lnk')
    ps = (f"$s=(New-Object -ComObject WScript.Shell).CreateShortcut('{lnk}');$s.TargetPath='{exe}';"
          f"$s.WorkingDirectory='{new}';$s.IconLocation='{ico},0';$s.Description='MangaCracker for Windows';$s.Save()")
    subprocess.call(['powershell', '-NoProfile', '-Command', ps], creationflags=NO_WINDOW)
    subprocess.call(['ie4uinit.exe', '-show'], creationflags=NO_WINDOW)
print('installed to', new + (' | still running (left for later): ' + ', '.join(kept) if kept else ''))
