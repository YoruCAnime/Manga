"""Check headers of one image URL without saving the image: python -X utf8 imghead.py URL [referer]"""
import sys, requests
h = {'User-Agent': 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36'}
if len(sys.argv) > 2:
    h['Referer'] = sys.argv[2]
r = requests.get(sys.argv[1], headers=h, timeout=25, stream=True)
print(r.status_code, {k: v for k, v in r.headers.items() if k.lower() in ('content-type', 'content-length', 'server', 'cache-control', 'access-control-allow-origin', 'cf-cache-status')})
r.close()
