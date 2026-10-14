#!/usr/bin/env python3
"""Genera los GIFs de postura de la preconfiguración (app/src/main/assets/guides/*.gif).

Monito de palitos de perfil (mirando a la derecha), con el teléfono en verde. La línea punteada
marca la altura del teléfono de pie y el marcador de la derecha, su altura actual: así se ve que
los brazos pueden bajar el teléfono tanto como una sentadilla, pero sin inclinarlo como el torso.

Uso:  python3 tools/make_guides.py [--sheet carpeta]   (requiere Pillow)
"""
import math
import os
import sys

from PIL import Image, ImageDraw

W = H = 260          # tamaño final
SS = 3               # supermuestreo para antialias
FRAMES = 60
FRAME_MS = 40        # 60 × 40 ms = bucle de 2,4 s

BG = (29, 27, 32)
GROUND = (73, 69, 79)
BODY = (230, 225, 229)
FAR = (125, 120, 132)
PHONE = (102, 187, 106)
REF = (70, 120, 74)

GROUND_Y = 238
ANKLE = (118.0, GROUND_Y - 3.0)
SHIN, THIGH, TRUNK, NECK, HEAD_R = 52.0, 52.0, 66.0, 7.0, 12.0
UPPER, FORE, FOOT = 43.0, 27.0, 22.0  # brazo "acortado" en perfil: los codos salen hacia los lados
PHONE_LEN, PHONE_W = 26.0, 6.0


def add(p, q, k=1.0):
    return (p[0] + k * q[0], p[1] + k * q[1])


def unit_up(deg):
    """Dirección 'hacia arriba' inclinada deg grados hacia adelante (+x)."""
    r = math.radians(deg)
    return (math.sin(r), -math.cos(r))


def unit_fwd(deg):
    r = math.radians(deg)
    return (math.cos(r), math.sin(r))


def leg_anchored(s_deg, h_deg, ankle=ANKLE):
    """Pie apoyado: s = tibia hacia adelante, h = muslo hacia atrás (grados desde la vertical)."""
    s, h = math.radians(s_deg), math.radians(h_deg)
    knee = (ankle[0] + SHIN * math.sin(s), ankle[1] - SHIN * math.cos(s))
    hip = (knee[0] - THIGH * math.sin(h), knee[1] - THIGH * math.cos(h))
    return ankle, knee, hip


def leg_from_hip(hip, phi_deg):
    """Pierna levantada (marcha): muslo flexionado phi grados, tibia colgando vertical."""
    p = math.radians(phi_deg)
    knee = (hip[0] + THIGH * math.sin(p), hip[1] + THIGH * math.cos(p))
    ankle = (knee[0], knee[1] + SHIN)
    return ankle, knee


def chest_point(hip, k_deg):
    return add(add(hip, unit_up(k_deg), 0.72 * TRUNK), unit_fwd(k_deg), 9.0)


def elbow(shoulder, hand):
    dx, dy = hand[0] - shoulder[0], hand[1] - shoulder[1]
    dist = math.hypot(dx, dy)
    ux, uy = dx / dist, dy / dist
    d = min(max(dist, abs(UPPER - FORE) + 1e-3), UPPER + FORE - 1e-3)
    a = (UPPER ** 2 - FORE ** 2 + d ** 2) / (2 * d)
    h = math.sqrt(max(UPPER ** 2 - a ** 2, 0.0))
    base = (shoulder[0] + a * ux, shoulder[1] + a * uy)
    c1 = (base[0] - h * uy, base[1] + h * ux)
    c2 = (base[0] + h * uy, base[1] - h * ux)
    return max((c1, c2), key=lambda c: c[1] - c[0])  # codo abajo y atrás


def ease(x):
    return (1 - math.cos(2 * math.pi * x)) / 2


STAND_HIP = leg_anchored(0, 0)[2]
REF_Y = chest_point(STAND_HIP, 3)[1]


def pose(name, f):
    """Devuelve el diccionario de la postura del cuadro f."""
    x = f / FRAMES
    p = {"legs": "anchored", "s": 0.0, "h": 0.0, "k": 3.0, "hand": None, "phone_deg": None}
    if name == "sentadilla":
        e = ease(x)
        p.update(s=30 * e, h=78 * e, k=4 + 36 * e)
    elif name == "quieto":
        p.update(k=3 + 1.2 * math.sin(2 * math.pi * x))
    elif name == "caminar":
        ph = 2 * math.pi * 2 * x
        p.update(legs="march", near=62 * max(0.0, math.sin(ph)), far=62 * max(0.0, -math.sin(ph)))
    elif name == "sacudir":
        c = chest_point(STAND_HIP, 3)
        p.update(hand=(c[0] + 14, c[1] + 10 * math.sin(2 * math.pi * 8 * x)), phone_deg=6 * math.sin(2 * math.pi * 8 * x))
    elif name == "inclinarse":
        e = ease(x)
        p.update(s=-4 * e, h=4 * e, k=3 + 72 * e)
    elif name in ("brazos_lento", "brazos_rapido"):
        reps = 1 if name == "brazos_lento" else 3
        e = ease((x * reps) % 1.0)
        c = add(chest_point(STAND_HIP, 3), unit_fwd(3), 3.0)
        low = (STAND_HIP[0] + 22, STAND_HIP[1] - 4)
        tilt = 0.0 if name == "brazos_lento" else 55 * e
        p.update(hand=(c[0] + (low[0] - c[0]) * e, c[1] + (low[1] - c[1]) * e), phone_deg=tilt)
    else:
        raise ValueError(name)
    return p


def draw_frame(name, f):
    img = Image.new("RGB", (W * SS, H * SS), BG)
    d = ImageDraw.Draw(img)

    def P(pt):
        return (pt[0] * SS, pt[1] * SS)

    def seg(a, b, col, w):
        d.line([P(a), P(b)], fill=col, width=int(w * SS))
        for q in (a, b):
            r = w * SS / 2
            d.ellipse([q[0] * SS - r, q[1] * SS - r, q[0] * SS + r, q[1] * SS + r], fill=col)

    def phone(center, deg):
        u, n = unit_up(deg), unit_fwd(deg)
        hl, hw = PHONE_LEN / 2, PHONE_W / 2
        pts = [add(add(center, u, s1 * hl), n, s2 * hw) for s1, s2 in ((1, 1), (1, -1), (-1, -1), (-1, 1))]
        d.polygon([P(q) for q in pts], fill=PHONE)

    p = pose(name, f)
    # suelo
    d.line([P((28, GROUND_Y)), P((232, GROUND_Y))], fill=GROUND, width=3 * SS)

    # piernas
    if p["legs"] == "anchored":
        ankle, knee, hip = leg_anchored(p["s"], p["h"])
        off = (-4.0, -2.0)
        seg(add(knee, off), add(hip, off), FAR, 6)
        seg(add(ankle, off), add(knee, off), FAR, 6)
        seg(ankle, knee, BODY, 7)
        seg(knee, hip, BODY, 7)
        seg((ankle[0] - 4, GROUND_Y - 1), (ankle[0] + FOOT, GROUND_Y - 1), BODY, 5)
    else:
        hip = STAND_HIP
        for phi, col, w in ((p["far"], FAR, 6), (p["near"], BODY, 7)):
            ankle, knee = leg_from_hip(add(hip, (-2, 0)) if col == FAR else hip, phi)
            seg(knee, ankle, col, w)
            seg(hip, knee, col, w)
            foot_y = min(ankle[1], GROUND_Y - 1)
            seg((ankle[0] - 4, foot_y), (ankle[0] + FOOT, foot_y), col, 5)

    # tronco y cabeza
    k = p["k"]
    shoulder = add(hip, unit_up(k), TRUNK)
    head = add(shoulder, unit_up(k), NECK + HEAD_R)

    # teléfono y manos
    if p["hand"] is None:
        center = add(chest_point(hip, k), unit_fwd(k), 3.0)
        deg = k
    else:
        center, deg = p["hand"], p["phone_deg"]

    far_sh = add(shoulder, (-3, -1))
    seg(far_sh, elbow(far_sh, center), FAR, 6)
    seg(elbow(far_sh, center), center, FAR, 5)

    seg(hip, shoulder, BODY, 9)
    r = HEAD_R * SS
    d.ellipse([head[0] * SS - r, head[1] * SS - r, head[0] * SS + r, head[1] * SS + r], fill=BODY)
    phone(center, deg)
    el = elbow(shoulder, center)
    seg(shoulder, el, BODY, 6)
    seg(el, center, BODY, 5)

    # referencia de altura del teléfono
    for x0 in range(150, 228, 10):
        d.line([P((x0, REF_Y)), P((x0 + 5, REF_Y))], fill=REF, width=2 * SS)
    d.line([P((240, REF_Y)), P((240, center[1]))], fill=REF, width=2 * SS)
    rr = 5 * SS
    d.ellipse([240 * SS - rr, center[1] * SS - rr, 240 * SS + rr, center[1] * SS + rr], fill=PHONE)

    return img.resize((W, H), Image.LANCZOS)


NAMES = ["sentadilla", "quieto", "caminar", "sacudir", "inclinarse", "brazos_lento", "brazos_rapido"]


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = os.path.join(root, "app", "src", "main", "assets", "guides")
    os.makedirs(out, exist_ok=True)
    sheet_dir = sys.argv[sys.argv.index("--sheet") + 1] if "--sheet" in sys.argv else None
    for name in NAMES:
        frames = [draw_frame(name, f) for f in range(FRAMES)]
        strip = Image.new("RGB", (W * 6, H))
        for i, f in enumerate((0, 8, 15, 23, 30, 45)):
            strip.paste(frames[f], (i * W, 0))
        pal = strip.quantize(colors=48, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
        q = [fr.quantize(palette=pal, dither=Image.Dither.NONE) for fr in frames]
        path = os.path.join(out, name + ".gif")
        q[0].save(path, save_all=True, append_images=q[1:], duration=FRAME_MS, loop=0, optimize=False)
        print(f"{path}  {os.path.getsize(path) // 1024} KB")
        if sheet_dir:
            os.makedirs(sheet_dir, exist_ok=True)
            strip.save(os.path.join(sheet_dir, name + "_sheet.png"))


if __name__ == "__main__":
    main()
