"""Read the top-level MP4 boxes in a <=64 KB head file; report whether moov comes first (fast start) and the
video track size from tkhd if it is inside the head.  python mp4head.py out/file.bin"""
import sys, struct

b = open(sys.argv[1], 'rb').read()
i = 0
while i + 8 <= len(b):
    size, typ = struct.unpack('>I4s', b[i:i + 8])
    if size == 1:
        size = struct.unpack('>Q', b[i + 8:i + 16])[0]
    print(f'box {typ.decode("latin1")} at {i} size {size}')
    if size < 8:
        break
    i += size
j = 0
while True:
    j = b.find(b'tkhd', j + 1)
    if j < 0:
        break
    w, h = struct.unpack('>II', b[j + 4 + 76:j + 4 + 84]) if b[j + 4] == 0 else struct.unpack('>II', b[j + 4 + 88:j + 4 + 96])
    print(f'tkhd at {j}: {w / 65536:.0f} x {h / 65536:.0f}')
j = b.find(b'mvhd')
if j > 0:
    ver = b[j + 4]
    if ver == 0:
        ts, dur = struct.unpack('>II', b[j + 16:j + 24])
    else:
        ts, dur = struct.unpack('>IQ', b[j + 24:j + 36])
    print(f'duration {dur / ts:.0f} s')
