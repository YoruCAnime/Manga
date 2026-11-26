"""Run a gradle task quietly (below-normal priority, no window) and print only errors / the result.
python gradle.py :app:compileDebugKotlin [more args]"""
import os, re, subprocess, sys

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
env = dict(os.environ, JAVA_HOME=r"C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot")
log = os.path.join(root, "_dev_tools", "out", "gradle.log")
os.makedirs(os.path.dirname(log), exist_ok=True)
with open(log, "w", encoding="utf-8", errors="replace") as f:
    rc = subprocess.call([os.path.join(root, "gradlew.bat"), *sys.argv[1:], "--console=plain"], cwd=root, env=env,
                         stdout=f, stderr=subprocess.STDOUT,
                         creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0) | getattr(subprocess, "BELOW_NORMAL_PRIORITY_CLASS", 0))
for l in open(log, encoding="utf-8", errors="replace"):
    if re.search(r"^e: |^w: .*(deprecat)?|error:|went wrong|BUILD (SUCCESSFUL|FAILED)|^> ", l) and not l.startswith("w: "):
        print(l.rstrip()[:400])
print("exit", rc)
