"""Third probe: whole-file nozomi downloads, plain path vs the gzip 'n/' path (what search.js uses), sizes on the wire,
and that both decode to the same ids. Also the character namespace naming. ~1 request/s."""
import struct, time, requests

UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
LTN = "https://ltn.gold-usergeneratedcontent.net"
H = {"User-Agent": UA, "Referer": "https://hitomi.la/", "Accept-Encoding": "gzip"}

for p in ["tag/female:oppai%20loli-all", "index-english"]:
    for pre in ["", "n/"]:
        r = requests.get(LTN + "/" + pre + p + ".nozomi", headers=H, timeout=30, stream=True)
        raw = r.raw.read(decode_content=False)
        enc = r.headers.get("content-encoding")
        import gzip
        data = gzip.decompress(raw) if enc == "gzip" else raw
        ids = struct.unpack(">%di" % (len(data) // 4), data)
        print(r.status_code, "wire=%d decoded=%d enc=%s ids=%d first=%s" % (len(raw), len(data), enc, len(ids), ids[:3]), pre + p)
        time.sleep(1)

for t in ["reimu", "hakurei"]:
    r = requests.get("https://tagindex.hitomi.la/character/%s.json" % "/".join(t), headers=H, timeout=20)
    print(t, r.status_code, r.text[:200]); time.sleep(1)
r = requests.get(LTN + "/character/hakurei%20reimu-all.nozomi", headers={**H, "Range": "bytes=0-7"}, timeout=20)
print(r.status_code, r.headers.get("content-range"))
