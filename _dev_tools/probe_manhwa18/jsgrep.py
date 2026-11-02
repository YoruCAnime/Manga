"""Print context around each match of a plain string in a (minified) file.
Usage: jsgrep.py FILE NEEDLE [before] [after] [max]"""
import sys

f, needle = sys.argv[1], sys.argv[2]
b = int(sys.argv[3]) if len(sys.argv) > 3 else 150
a = int(sys.argv[4]) if len(sys.argv) > 4 else 300
mx = int(sys.argv[5]) if len(sys.argv) > 5 else 6
t = open(f, encoding="utf-8", errors="replace").read()
i, n = 0, 0
while n < mx:
    i = t.find(needle, i)
    if i < 0:
        break
    print(f"--- @{i}\n{t[max(0, i - b):i + a]}\n")
    i += len(needle)
    n += 1
print("matches shown:", n)
