"""Generates the demo scene assets in ../assets.

    python tools/generate_assets.py

teapot.obj / lid.obj  tessellated from Newell's Bezier patches (newell_teapot.json)
turntable.obj         a textured cylinder the teapot sits on
turntable.png         procedural texture for the turntable top
*.mtl                 materials
"""
import json
import math
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUT = ROOT.parent / "assets"
SEGMENTS = 12
TEAPOT_SCALE = 0.42


def bernstein(t):
    it = 1 - t
    return [it * it * it, 3 * t * it * it, 3 * t * t * it, t * t * t]


def bernstein_d(t):
    it = 1 - t
    return [-3 * it * it, 3 * it * it - 6 * t * it, 6 * t * it - 3 * t * t, 3 * t * t]


def sub(a, b):
    return [a[0] - b[0], a[1] - b[1], a[2] - b[2]]


def cross(a, b):
    return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]


def dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def normalize(a):
    length = math.sqrt(dot(a, a))
    return [a[0] / length, a[1] / length, a[2] / length] if length > 1e-9 else [0.0, 0.0, 0.0]


def to_y_up(p):
    return [p[0], p[2], -p[1]]


class ObjWriter:
    def __init__(self, mtllib):
        self.lines = [f"mtllib {mtllib}"]
        self.count = 0

    def mesh(self, name, material, positions, normals, uvs, triangles):
        self.lines.append(f"o {name}")
        base = self.count
        for p in positions:
            self.lines.append("v {:.5f} {:.5f} {:.5f}".format(*p))
        for uv in uvs:
            self.lines.append("vt {:.5f} {:.5f}".format(*uv))
        for n in normals:
            self.lines.append("vn {:.5f} {:.5f} {:.5f}".format(*n))
        self.lines.append(f"usemtl {material}")
        for tri in triangles:
            self.lines.append("f " + " ".join(f"{base + i + 1}/{base + i + 1}/{base + i + 1}" for i in tri))
        self.count += len(positions)

    def save(self, path):
        path.write_text("\n".join(self.lines) + "\n", newline="\n")


def add_triangle(triangles, positions, normals, a, b, c):
    face = cross(sub(positions[b], positions[a]), sub(positions[c], positions[a]))
    if dot(face, face) < 1e-14:
        return
    average = [normals[a][i] + normals[b][i] + normals[c][i] for i in range(3)]
    triangles.append((a, b, c) if dot(face, average) >= 0 else (a, c, b))


def tessellate(data, patch_range, widen=1.0):
    positions, normals, uvs, triangles = [], [], [], []
    control = [[x * widen, y * widen, z] for x, y, z in data["vertices"]]
    row = SEGMENTS + 1
    for patch_index in range(*patch_range):
        patch = [control[i] for i in data["patches"][patch_index]]
        start = len(positions)
        for si in range(row):
            s = si / SEGMENTS
            bs, dbs = bernstein(s), bernstein_d(s)
            for ti in range(row):
                t = ti / SEGMENTS
                bt, dbt = bernstein(t), bernstein_d(t)
                point, ds, dt = [0.0] * 3, [0.0] * 3, [0.0] * 3
                for r in range(4):
                    for c in range(4):
                        cp = patch[r * 4 + c]
                        for k in range(3):
                            point[k] += bs[r] * bt[c] * cp[k]
                            ds[k] += dbs[r] * bt[c] * cp[k]
                            dt[k] += bs[r] * dbt[c] * cp[k]
                normal = normalize(cross(dt, ds))
                if normal == [0.0, 0.0, 0.0] or (abs(point[0]) < 1e-6 and abs(point[1]) < 1e-6):
                    # Cusp at the lid knob or the bottom centre: the tangents degenerate.
                    normal = [0.0, 0.0, 1.0 if point[2] > 1.5 else -1.0]
                positions.append([v * TEAPOT_SCALE for v in to_y_up(point)])
                normals.append(to_y_up(normal))
                uvs.append([1 - t, 1 - s])
        for si in range(SEGMENTS):
            for ti in range(SEGMENTS):
                v1 = start + si * row + ti
                v2, v4 = v1 + 1, v1 + row
                v3 = v4 + 1
                add_triangle(triangles, positions, normals, v1, v2, v3)
                add_triangle(triangles, positions, normals, v1, v3, v4)
    return positions, normals, uvs, triangles


def check_body_faces_outward(positions, normals):
    checked = 0
    for p, n in zip(positions, normals):
        radial = [p[0], 0.0, p[2]]
        if dot(radial, radial) > (0.5 * TEAPOT_SCALE) ** 2:
            assert dot(radial, n) > 0, f"body normal points inwards at {p}"
            checked += 1
    assert checked > 100, checked


def cylinder(radius, height, segments):
    positions, normals, uvs, triangles = [], [], [], []
    top, bottom = 0.0, -height
    for i in range(segments + 1):
        a = 2 * math.pi * i / segments
        x, z = math.cos(a), math.sin(a)
        for y in (top, bottom):
            positions.append([radius * x, y, radius * z])
            normals.append([x, 0.0, z])
            uvs.append([i / segments, 0.0 if y == top else 0.02])
    for i in range(segments):
        a, b, c, d = 2 * i, 2 * i + 1, 2 * i + 3, 2 * i + 2
        add_triangle(triangles, positions, normals, a, b, c)
        add_triangle(triangles, positions, normals, a, c, d)
    for y, ny in ((top, 1.0), (bottom, -1.0)):
        centre = len(positions)
        positions.append([0.0, y, 0.0])
        normals.append([0.0, ny, 0.0])
        uvs.append([0.5, 0.5])
        for i in range(segments + 1):
            a = 2 * math.pi * i / segments
            positions.append([radius * math.cos(a), y, radius * math.sin(a)])
            normals.append([0.0, ny, 0.0])
            uvs.append([0.5 + 0.5 * math.cos(a), 0.5 + 0.5 * math.sin(a)])
        for i in range(segments):
            add_triangle(triangles, positions, normals, centre, centre + 1 + i, centre + 2 + i)
    return positions, normals, uvs, triangles


def write_png(path, width, height, pixel):
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            raw.extend(pixel(x, y))

    def chunk(kind, payload):
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


def turntable_pixel(size, samples=4):
    def colour(u, v):
        r = math.hypot(u, v)
        if r > 0.94:
            return (38, 41, 48)
        if abs(r * 6 - round(r * 6)) < 0.03:
            return (24, 26, 30)
        ring = int(r * 6)
        sector = int((math.atan2(v, u) + math.pi) / (2 * math.pi) * 24)
        return (214, 206, 190) if (ring + sector) % 2 == 0 else (150, 140, 124)

    def pixel(x, y):
        # Supersampled, so the sector and groove edges are anti-aliased.
        total = [0, 0, 0]
        for sy in range(samples):
            for sx in range(samples):
                u = (x + (sx + 0.5) / samples) / size * 2 - 1
                v = (y + (sy + 0.5) / samples) / size * 2 - 1
                c = colour(u, v)
                for k in range(3):
                    total[k] += c[k]
        return tuple(round(t / samples ** 2) for t in total)
    return pixel


def main():
    OUT.mkdir(exist_ok=True)
    data = json.loads((ROOT / "newell_teapot.json").read_text())
    groups = data["groups"]

    body = ([], [], [], [])
    for name in ("rim", "body", "handle", "spout", "bottom"):
        p, n, uv, tri = tessellate(data, groups[name])
        if name == "body":
            check_body_faces_outward(p, n)
        offset = len(body[0])
        body[0].extend(p)
        body[1].extend(n)
        body[2].extend(uv)
        body[3].extend(tuple(i + offset for i in t) for t in tri)

    teapot = ObjWriter("teapot.mtl")
    teapot.mesh("Teapot", "glaze", *body)
    teapot.save(OUT / "teapot.obj")

    lid = ObjWriter("teapot.mtl")
    # The original lid is a little narrower than the rim, leaving a see-through gap.
    lid.mesh("Lid", "glaze", *tessellate(data, groups["lid"], widen=1.077))
    lid.save(OUT / "lid.obj")

    (OUT / "teapot.mtl").write_text(
        "newmtl glaze\nKa 0.20 0.06 0.05\nKd 0.72 0.16 0.12\nKs 0.90 0.90 0.90\nNs 180\nd 1.0\n", newline="\n"
    )

    table = ObjWriter("turntable.mtl")
    table.mesh("Turntable", "turntable", *cylinder(1.7, 0.12, 64))
    table.save(OUT / "turntable.obj")
    (OUT / "turntable.mtl").write_text(
        "newmtl turntable\nKa 0.25 0.25 0.25\nKd 1.0 1.0 1.0\nKs 0.15 0.15 0.15\nNs 24\nd 1.0\nmap_Kd turntable.png\n",
        newline="\n",
    )
    write_png(OUT / "turntable.png", 512, 512, turntable_pixel(512))

    print("written to", OUT)


if __name__ == "__main__":
    main()
