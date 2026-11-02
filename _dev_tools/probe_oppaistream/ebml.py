"""Walk the EBML header of a saved WebM head (first 64 KB) and print track codecs + frame size + duration.
python ebml.py out/head.webm"""
import sys, struct

d = open(sys.argv[1], 'rb').read()
MASTER = {0x18538067, 0x1654AE6B, 0xAE, 0xE0, 0xE1, 0x1549A966}
NAMES = {0x86: 'CodecID', 0xB0: 'PixelWidth', 0xBA: 'PixelHeight', 0x83: 'TrackType', 0x4489: 'Duration',
         0x2AD7B1: 'TimecodeScale', 0x54B0: 'DisplayWidth', 0x54BA: 'DisplayHeight', 0x22B59C: 'Language'}


def vint(p, keep_marker):
    b = d[p]
    n = 1
    while n <= 8 and not (b & (0x80 >> (n - 1))):
        n += 1
    v = b if keep_marker else b & (0xFF >> n)
    for i in range(1, n):
        v = (v << 8) | d[p + i]
    return v, n


def walk(p, end, depth):
    while p < end and p < len(d) - 2:
        eid, n1 = vint(p, True)
        size, n2 = vint(p + n1, False)
        p2 = p + n1 + n2
        if eid in MASTER:
            print('  ' * depth + hex(eid))
            walk(p2, min(p2 + size, len(d)), depth + 1)
        elif eid in NAMES:
            raw = d[p2:p2 + size]
            if eid in (0x86, 0x22B59C):
                val = raw.decode('latin1')
            elif eid == 0x4489:
                val = struct.unpack('>d' if size == 8 else '>f', raw)[0]
            else:
                val = int.from_bytes(raw, 'big')
            print('  ' * depth + f'{NAMES[eid]} = {val}')
        if size > len(d):
            break
        p = p2 + size


walk(0, len(d), 0)
