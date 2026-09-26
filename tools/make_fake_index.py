#!/usr/bin/env python3
"""Generate a tiny but structurally valid pose-mirror portable index bundle.

Used only for CI screenshots: format.json / ids.json / vectors.f32le.bin /
manifest.json / thumbs/*.jpg with the exact shapes PoseIndex.load expects.
Thumbnails are solid-color PNG bytes (BitmapFactory sniffs content, not the
extension). All metadata is clearly labeled as a CI stub.
"""
import json
import os
import struct
import sys
import zlib

OUT = sys.argv[1]
IDS = ["shot-a", "shot-b", "shot-c"]
DIM = 66


def png_chunk(typ: bytes, data: bytes) -> bytes:
    c = struct.pack(">I", len(data)) + typ + data
    return c + struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF)


def solid_png(rgb, w: int = 64, h: int = 64) -> bytes:
    raw = b"".join(b"\x00" + bytes(rgb) * w for _ in range(h))
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", ihdr)
        + png_chunk(b"IDAT", zlib.compress(raw))
        + png_chunk(b"IEND", b"")
    )


os.makedirs(os.path.join(OUT, "thumbs"), exist_ok=True)
with open(os.path.join(OUT, "format.json"), "w") as f:
    json.dump({"index_version": 1, "vector_dim": DIM}, f)
with open(os.path.join(OUT, "ids.json"), "w") as f:
    json.dump(IDS, f)
with open(os.path.join(OUT, "vectors.f32le.bin"), "wb") as f:
    f.write(struct.pack("<%df" % (len(IDS) * DIM), *([0.0] * len(IDS) * DIM)))
manifest = {
    i: {
        "title": "CI screenshot stub %s" % i,
        "author": "CI stub",
        "license": "CC0",
        "source": "https://example.invalid/",
    }
    for i in IDS
}
with open(os.path.join(OUT, "manifest.json"), "w") as f:
    json.dump(manifest, f)
colors = [(217, 164, 65), (126, 176, 105), (168, 162, 154)]
for i, c in zip(IDS, colors):
    with open(os.path.join(OUT, "thumbs/%s.jpg" % i), "wb") as f:
        f.write(solid_png(c))
print("fake index written to %s" % OUT)
