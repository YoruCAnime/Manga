"""Reads duration + video size from the moov box of a saved MP4 head (the 64 KB Range read). Usage: mp4head.py FILE"""
import struct, sys


def boxes(b, off, end):
    while off + 8 <= end:
        size, typ = struct.unpack('>I4s', b[off:off + 8])
        hdr = 8
        if size == 1:
            size = struct.unpack('>Q', b[off + 8:off + 16])[0]
            hdr = 16
        if size < 8:
            break
        yield typ.decode('latin1'), off + hdr, min(off + size, end)
        off += size


def main():
    b = open(sys.argv[1], 'rb').read()
    for t, s, e in boxes(b, 0, len(b)):
        if t != 'moov':
            continue
        for t2, s2, e2 in boxes(b, s, e):
            if t2 == 'mvhd':
                v = b[s2]
                if v == 0:
                    ts, dur = struct.unpack('>II', b[s2 + 12:s2 + 20])
                else:
                    ts, dur = struct.unpack('>IQ', b[s2 + 20:s2 + 32])
                print('duration %.1f s' % (dur / ts))
            if t2 == 'trak':
                for t3, s3, e3 in boxes(b, s2, e2):
                    if t3 == 'tkhd':
                        w, h = struct.unpack('>II', b[e3 - 8:e3])
                        if w:
                            print('track %dx%d' % (w >> 16, h >> 16))
        return
    print('no moov in the head (moov at the end of the file)')


if __name__ == '__main__':
    main()
