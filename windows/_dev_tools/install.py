r"""Installs the built app (build/compose/binaries/main/app/MangaCracker) into ONE fixed place,
"MangaCracker App\app\MangaCracker.exe" (a VPN's app list, a firewall rule or the Desktop shortcut keep working after
updates), also WHILE the old one is running:
- nothing runs from "app": the new build goes straight into "app";
- the app is running: the new build waits in "MangaCracker App\next" and current.txt says "next". The running copy
  notices and, at a quiet moment, closes; a small helper swaps "next" in as "app" and starts it
  (platform/SelfUpdate.kt).
Folders of the older layout ("v<date-time>", one per build) are removed once nothing in them is in use (a folder with an
open file can't be renamed on Windows, so that's the test); a copy still running from one moves to "app" by itself.
Run after `gradlew createDistributable` (or use "Build and install.bat").
install.py [install folder] [--no-shortcut]   (tests install into their own folder without touching the shortcut)"""
import os, shutil, stat, subprocess, sys, time

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
src = os.path.join(root, 'build', 'compose', 'binaries', 'main', 'app', 'MangaCracker')
args = [a for a in sys.argv[1:] if not a.startswith('--')]
dst = args[0] if args else os.path.join(root, 'MangaCracker App')
NO_WINDOW = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
if not os.path.isfile(os.path.join(src, 'MangaCracker.exe')):
    raise SystemExit('build first: gradlew createDistributable')
# a build older than the code would be installed with the old code still in it
built = min(os.path.getmtime(os.path.join(src, 'app', n)) for n in os.listdir(os.path.join(src, 'app')) if n.startswith('MangaCracker Windows-'))
code = [os.path.join(root, 'build.gradle.kts')] + [os.path.join(d, n) for d, _, names in os.walk(os.path.join(root, 'src', 'main')) for n in names]
if max(os.path.getmtime(p) for p in code) > built:
    raise SystemExit('the build is older than the code: run gradlew createDistributable first')
os.makedirs(dst, exist_ok=True)


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
    if os.path.isdir(trash):
        rmtree(trash)
    try:
        os.rename(path, trash)
    except OSError:
        return False
    rmtree(trash)
    return True


def copy_build(to):
    rc = subprocess.call(['robocopy', src, to, '/E', '/NFL', '/NDL', '/NJH', '/NJS', '/NP'], creationflags=NO_WINDOW)
    if rc >= 8 or not os.path.isfile(os.path.join(to, 'MangaCracker.exe')):
        raise SystemExit('copy failed (robocopy %d)' % rc)


def say(which):
    """current.txt names the newest copy ("app" or "next"): written last, in one step."""
    tmp = os.path.join(dst, 'current.txt.tmp')
    with open(tmp, 'w', encoding='utf-8') as f:
        f.write(which)
    os.replace(tmp, os.path.join(dst, 'current.txt'))


app, nxt, old, new = (os.path.join(dst, n) for n in ('app', 'next', 'app.old', 'app.new'))
for leftover in (old, new):   # from a swap that stopped half way
    if os.path.isdir(leftover):
        gone_if_free(leftover)
# an earlier build still waiting in "next" (not taken yet): this one replaces it, unless a copy runs from it
next_free = not os.path.isdir(nxt) or gone_if_free(nxt)
app_free = not os.path.isdir(app) or gone_if_free(app)
if app_free:
    copy_build(app)
    say('app')
    where = app + ' (ready to start)'
elif next_free:
    copy_build(nxt)
    say('next')
    where = nxt + ' (the running app moves it to ' + app + ' at a quiet moment)'
else:
    raise SystemExit('both "app" and "next" are in use: close MangaCracker and install again')

# the older layout: a folder per build ("v<date-time>"), and before that app\ + runtime\ straight in the install folder
kept = []
for name in os.listdir(dst):
    p = os.path.join(dst, name)
    if not os.path.isdir(p) or name in ('app', 'next', 'app.old', 'app.new'):
        continue
    if name.endswith('.del'):
        rmtree(p)
    elif name.startswith('v') and not gone_if_free(p):
        kept.append(name)
for n in ('runtime', 'MangaCracker.exe', 'MangaCracker.ico'):
    p = os.path.join(dst, n)
    if os.path.isdir(p):
        if not gone_if_free(p):
            kept.append(n)
    elif os.path.isfile(p):
        try:
            os.chmod(p, stat.S_IWRITE)
            os.remove(p)
        except OSError:
            kept.append(n)

if '--no-shortcut' not in sys.argv:
    # the shortcut's icon sits next to the app folders (a fresh name, so Windows' icon cache can't show an old picture)
    ico = os.path.join(dst, 'MangaCracker-%s.ico' % time.strftime('v%Y%m%d-%H%M%S'))
    shutil.copyfile(os.path.join(root, 'appIcon', 'icon.ico'), ico)
    for n in os.listdir(dst):
        if n.startswith('MangaCracker-v') and n.endswith('.ico') and n != os.path.basename(ico):
            try:
                os.remove(os.path.join(dst, n))
            except OSError:
                pass
    lnk = os.path.join(os.path.expanduser('~'), 'Desktop', 'MangaCracker.lnk')
    exe = os.path.join(app, 'MangaCracker.exe')
    ps = (f"$s=(New-Object -ComObject WScript.Shell).CreateShortcut('{lnk}');$s.TargetPath='{exe}';"
          f"$s.WorkingDirectory='{app}';$s.IconLocation='{ico},0';$s.Description='MangaCracker for Windows';$s.Save()")
    subprocess.call(['powershell', '-NoProfile', '-Command', ps], creationflags=NO_WINDOW)
    subprocess.call(['ie4uinit.exe', '-show'], creationflags=NO_WINDOW)
print('installed to', where + (' | still running (left for later): ' + ', '.join(kept) if kept else ''))
