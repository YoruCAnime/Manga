import re, sys
t = open(sys.argv[1], encoding='utf8').read()
w = int(sys.argv[3]) if len(sys.argv) > 3 else 800
after = int(sys.argv[4]) if len(sys.argv) > 4 else w
n = 0
for m in re.finditer(sys.argv[2], t):
    print('@@', m.start()); print(t[max(0, m.start()-w): m.end()+after]); print()
    n += 1
    if n >= int(sys.argv[5]) if len(sys.argv) > 5 else n >= 3: break
