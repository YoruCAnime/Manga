"""Run gradle unit tests quietly and print only results / errors.
python run_tests.py TestPattern [TestPattern ...]   (e.g. AppUpdateTest GifAndTagsTest)"""
import os, re, subprocess, sys

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
env = dict(os.environ, JAVA_HOME=r"C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot")
args = [os.path.join(root, "gradlew.bat"), ":app:testDebugUnitTest", "--console=plain", "-i"]
for p in sys.argv[1:]:
    args += ["--tests", f"*{p}*"]
log = os.path.join(root, "_dev_tools", "out", "test.log")
os.makedirs(os.path.dirname(log), exist_ok=True)
with open(log, "w", encoding="utf-8", errors="replace") as f:
    rc = subprocess.call(args, cwd=root, env=env, stdout=f, stderr=subprocess.STDOUT,
                         creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0) | getattr(subprocess, "BELOW_NORMAL_PRIORITY_CLASS", 0))
keep = re.compile(r"^e: |^\s+\[\w+\]|FAILED|AssertionError|Exception:|went wrong|BUILD (SUCCESSFUL|FAILED)|tests completed|^\s+at com\.yoruc")
lines = open(log, encoding="utf-8", errors="replace").read().splitlines()
shown = 0
for i, l in enumerate(lines):
    if keep.search(l) and shown < 120:
        print(l[:300]); shown += 1
        if l.startswith("e: ") and i + 1 < len(lines):
            print("   ", lines[i + 1][:300])
print("exit", rc)
