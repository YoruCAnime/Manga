r"""Copies the built app (build/compose/binaries/main/app/MangaCracker) to "MangaCracker App" next to the project and
makes the Desktop shortcut. Run after `gradlew createDistributable` (or use "Build and install.bat")."""
import os, shutil, subprocess

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
src = os.path.join(root, 'build', 'compose', 'binaries', 'main', 'app', 'MangaCracker')
dst = os.path.join(root, 'MangaCracker App')
if not os.path.isfile(os.path.join(src, 'MangaCracker.exe')):
    raise SystemExit('build first: gradlew createDistributable')
subprocess.call(['robocopy', src, dst, '/MIR', '/NFL', '/NDL', '/NJH', '/NJS', '/NP'], creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
exe = os.path.join(dst, 'MangaCracker.exe')
# the shortcut points at its own .ico (a fresh path, so Windows' icon cache can't show an old picture)
ico = os.path.join(dst, 'MangaCracker.ico')
shutil.copyfile(os.path.join(root, 'appIcon', 'icon.ico'), ico)
lnk = os.path.join(os.path.expanduser('~'), 'Desktop', 'MangaCracker.lnk')
ps = (f"$s=(New-Object -ComObject WScript.Shell).CreateShortcut('{lnk}');$s.TargetPath='{exe}';"
      f"$s.WorkingDirectory='{dst}';$s.IconLocation='{ico},0';$s.Description='MangaCracker for Windows';$s.Save()")
subprocess.call(['powershell', '-NoProfile', '-Command', ps], creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
# ask Explorer to redraw icons
subprocess.call(['ie4uinit.exe', '-show'], creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
print('installed to', dst, '| shortcut', lnk)
