import json, sys
d = json.load(open(sys.argv[1], encoding='utf8'))
exec(sys.argv[2])
