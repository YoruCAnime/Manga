"""Offline check: parse.embed_sources on every saved embed page in out/ (no network)."""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from parse import embed_sources

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out')
for f in sorted(os.listdir(OUT)):
    if f.startswith('embed_') and f.endswith('.html'):
        srcs = embed_sources(open(os.path.join(OUT, f), encoding='utf-8').read())
        print(f, len(srcs), srcs[:1])
