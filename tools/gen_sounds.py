"""Synthesizes every Gatebound sound effect as a mono Ogg Vorbis file and rewrites sounds.json to use them.

Run from the repo root:  python tools/gen_sounds.py
Needs numpy, scipy and ffmpeg (libvorbis). Output: common/src/main/resources/assets/gatebound/sounds/<path>/<name>_<n>.ogg
Each event gets two variants (different noise seed and pitch). To hand-replace a sound, drop your own .ogg over the file,
or point the sounds.json entry elsewhere and remove the path from RECIPES so the script leaves it alone.
"""
import json, os, re, subprocess, sys, tempfile, wave
import numpy as np
from scipy import signal

SR = 44100
ROOT = os.path.join('common', 'src', 'main')
ASSETS = os.path.join(ROOT, 'resources', 'assets', 'gatebound')
OUT = os.path.join(ASSETS, 'sounds')


# ---- primitives ------------------------------------------------------------------------------------------------
def T(d):
    return np.arange(int(SR * d)) / SR


def env(n, a=0.005, decay=0.15):
    t = np.arange(n) / SR
    e = np.exp(-t / max(decay, 1e-4))
    na = max(int(a * SR), 1)
    e[:na] *= np.linspace(0, 1, na)
    return e


def noise(rng, d):
    return rng.standard_normal(int(SR * d))


def filt(x, kind, f, order=3):
    f = np.atleast_1d(f).astype(float)
    f = np.clip(f, 20, SR / 2 - 200)
    sos = signal.butter(order, f if len(f) > 1 else f[0], btype=kind, fs=SR, output='sos')
    return signal.sosfilt(sos, x)


def sweep(f0, f1, d, shape='sine', expo=True):
    t = T(d)
    k = (f1 / f0) ** (t / d) if expo else 1
    f = f0 * k if expo else f0 + (f1 - f0) * t / d
    ph = 2 * np.pi * np.cumsum(f) / SR
    if shape == 'saw':
        return signal.sawtooth(ph)
    if shape == 'square':
        return signal.square(ph)
    return np.sin(ph)


def bell(f, d, decay=0.4, partials=((1, 1), (2.76, 0.5), (5.4, 0.25), (8.9, 0.1))):
    t = T(d)
    x = sum(a * np.sin(2 * np.pi * f * r * t) * np.exp(-t / (decay / (1 + 0.4 * i))) for i, (r, a) in enumerate(partials))
    return x * np.minimum(t / 0.003, 1)


def place(dst, src, at=0.0, gain=1.0):
    i = int(at * SR)
    if i >= len(dst):
        return
    n = min(len(src), len(dst) - i)
    dst[i:i + n] += src[:n] * gain


def canvas(d):
    return np.zeros(int(SR * d))


# ---- recipe families -------------------------------------------------------------------------------------------
def hit(r, p, d=0.45, f=110, grit=0.6, lp=2500, sub=1.0):
    out = canvas(d)
    n = len(out)
    out += sub * sweep(f * 2 * p, f * 0.6 * p, d) * env(n, 0.002, d / 4)
    out += grit * filt(noise(r, d), 'low', lp * p) * env(n, 0.001, d / 6)
    return out


def knock(r, p, d=0.3, f=300, lp=1800):  # wood / stone block sounds
    out = canvas(d)
    n = len(out)
    out += sweep(f * 1.4 * p, f * p, d) * env(n, 0.001, d / 5)
    out += 0.7 * filt(noise(r, d), 'band', [f * p * 0.6, lp * p]) * env(n, 0.001, d / 7)
    return out


def glass(r, p, d=0.9, hits=14, base=3000):
    out = canvas(d)
    for _ in range(hits):
        at = r.uniform(0, d * 0.55)
        place(out, bell(base * p * r.uniform(0.8, 2.2), 0.25, 0.07, ((1, 1), (2.3, 0.4))), at, r.uniform(0.2, 0.7))
    place(out, filt(noise(r, 0.12), 'high', 3500) * env(int(0.12 * SR), 0.0005, 0.03), 0, 0.8)
    return out


def chime(r, p, notes=(659, 880, 1319), d=0.9, gap=0.08, decay=0.45):
    out = canvas(d + gap * len(notes))
    for i, f in enumerate(notes):
        place(out, bell(f * p, d, decay), i * gap, 0.7)
    return out


def whoosh(r, p, f0=400, f1=2500, d=0.4, q=0.6, shape=None):
    n = noise(r, d)
    t = T(d)
    cf = f0 * (f1 / f0) ** (t / d) * p
    out = np.zeros_like(n)
    blocks = 24  # moving band-pass in blocks
    edges = np.linspace(0, len(n), blocks + 1).astype(int)
    for a, b in zip(edges[:-1], edges[1:]):
        c = cf[(a + b) // 2]
        seg = filt(n[a:b + 512][:b - a + 512], 'band', [c * (1 - q * 0.5), c * (1 + q)], 2)[:b - a]
        out[a:b] = seg
    e = np.sin(np.pi * t / d) ** 1.5
    return out * e * 2.5


def explode(r, p, d=1.0, lp=900, sub=1.0, crackle=0.4):
    n = int(SR * d)
    out = filt(noise(r, d), 'low', lp * p, 2) * env(n, 0.002, d / 3.5) * 2.2
    out += sub * sweep(90 * p, 28, d) * env(n, 0.002, d / 3)
    out += crackle * filt(noise(r, d), 'high', 2500) * env(n, 0.001, d / 12)
    return out


def thunder(r, p, d=2.6):
    n = int(SR * d)
    rumble = filt(noise(r, d), 'low', 180 * p, 2)
    mod = np.abs(filt(noise(r, d), 'low', 6, 1))
    mod /= mod.max()
    out = rumble * (0.3 + mod) * env(n, 0.05, d / 2.2) * 5
    place(out, filt(noise(r, 0.3), 'band', [800, 7000]) * env(int(0.3 * SR), 0.0005, 0.05), 0, 1.4)
    return out


def zap(r, p, d=0.45, f=1800):
    n = int(SR * d)
    out = filt(noise(r, d), 'high', 1500) * env(n, 0.0005, d / 8)
    out += 0.6 * sweep(f * p, 120 * p, d, 'saw') * env(n, 0.0005, d / 5)
    out += 0.5 * np.sign(np.sin(2 * np.pi * 60 * T(d))) * env(n, 0.001, d / 4)
    return out


def roar(r, p, d=1.4, f=90, vib=6, grit=0.8, lp=1200):
    t = T(d)
    n = len(t)
    fm = f * p * (1 + 0.12 * np.sin(2 * np.pi * vib * t)) * (1 + 0.2 * np.exp(-t * 3))
    x = signal.sawtooth(2 * np.pi * np.cumsum(fm) / SR)
    x = filt(x, 'low', lp * p, 2) + grit * filt(noise(r, d), 'band', [200, 1500], 2)
    e = np.minimum(t / 0.12, 1) * np.exp(-np.maximum(t - d * 0.4, 0) / (d / 3))
    return x * e


def beam(r, p, f0=300, f1=1800, d=0.8, charge=True):
    t = T(d)
    s = sweep(f0 * p, f1 * p, d, 'saw')
    s = filt(s, 'low', 3500, 2) + 0.6 * sweep(f0 * 2 * p, f1 * 2 * p, d)
    e = (t / d) ** 1.6 if charge else np.exp(-t / (d / 3))
    return s * e * np.minimum((d - t) / 0.03, 1)


def magic(r, p, f=520, d=0.8, up=1.8):
    t = T(d)
    fc = f * p * (up ** (t / d))
    x = np.sin(2 * np.pi * np.cumsum(fc) / SR + 3 * np.sin(2 * np.pi * np.cumsum(fc * 2.01) / SR) * np.exp(-t * 3))
    sp = filt(noise(r, d), 'high', 5000) * 0.15 * (0.5 + 0.5 * np.sin(2 * np.pi * 14 * t))
    return (x * 0.8 + sp) * np.sin(np.pi * np.minimum(t / d, 1)) ** 0.6


def fire(r, p, d=0.6, lp=3000):
    n = int(SR * d)
    out = filt(noise(r, d), 'band', [300 * p, lp * p], 2) * env(n, 0.01, d / 2.5) * 2
    pops = (r.random(n) > 0.9985).astype(float)
    out += filt(pops * r.standard_normal(n) * 6, 'high', 1500) * env(n, 0.001, d / 2)
    return out


def liquid(r, p, d=0.7):
    out = canvas(d)
    for i in range(r.integers(4, 8)):
        at = i * d / 7
        place(out, sweep(300 * p, 900 * p, 0.09) * env(int(0.09 * SR), 0.001, 0.03), at, 0.7)
    place(out, filt(noise(r, d), 'band', [500, 3000]) * env(len(out), 0.05, d / 3), 0, 0.25)
    return out


def page(r, p, d=0.5):
    out = canvas(d)
    for at in (0.0, 0.14, 0.27):
        place(out, filt(noise(r, 0.14), 'band', [1500 * p, 6500 * p], 2) * env(int(0.14 * SR), 0.01, 0.05), at, 0.8)
    return out


def clink(r, p, d=0.6, n=4):
    out = canvas(d)
    for i in range(n):
        place(out, bell(r.uniform(2200, 4200) * p, 0.22, 0.05, ((1, 1), (2.6, 0.5), (4.1, 0.3))), i * r.uniform(0.04, 0.09), 0.5)
    return out


def hum(r, p, f=110, d=2.2, lfo=0.8, shimmer=0.3):
    t = T(d)
    x = np.sin(2 * np.pi * f * p * t) + 0.5 * np.sin(2 * np.pi * f * p * 1.5 * t) + 0.3 * np.sin(2 * np.pi * f * p * 2.01 * t)
    x *= 0.6 + 0.4 * np.sin(2 * np.pi * lfo * t)
    x += shimmer * filt(noise(r, d), 'band', [600, 2400], 2)
    return x * np.sin(np.pi * t / d) ** 0.5


def teleport(r, p, d=0.5, rising=False):
    a, b = (200, 1800) if rising else (1800, 160)
    s = sweep(a * p, b * p, d) * 0.6 + whoosh(r, p, a, b, d, 0.8) * 0.8
    return s * np.minimum(T(d) / 0.01, 1) * np.minimum((d - T(d)) / 0.05, 1)


def swing(r, p, d=0.28, f0=900, f1=3500):
    return whoosh(r, p, f0, f1, d, 0.9)


def arrow(r, p, d=0.35):
    out = whoosh(r, p, 600, 3200, d, 0.5) * 0.7
    place(out, filt(noise(r, 0.05), 'band', [1000, 5000]) * env(int(0.05 * SR), 0.0005, 0.012), 0, 1.0)
    place(out, sweep(180 * p, 90 * p, 0.12) * env(int(0.12 * SR), 0.0005, 0.04), 0, 0.7)
    return out


def thud_soft(r, p, d=0.35):
    return hit(r, p, d, 150, 0.3, 900)


def levelup(r, p):
    return chime(r, p, (523, 659, 784, 1047, 1319), 1.1, 0.09, 0.6)


def totem(r, p):
    out = chime(r, p, (392, 523, 659, 784, 1047, 1568), 1.4, 0.07, 0.7)
    place(out, whoosh(r, p, 300, 3500, 1.2, 0.8), 0, 0.5)
    return out


def combo(*parts):
    """parts: (fn_name_lambda, at, gain) combined on one canvas."""
    def run(r, p):
        pieces = [(fn(r, p), at, g) for fn, at, g in parts]
        out = canvas(max(at + len(x) / SR for x, at, g in pieces) + 0.01)
        for x, at, g in pieces:
            place(out, x, at, g)
        return out
    return run


# ---- event -> recipe ---------------------------------------------------------------------------------------------
H = lambda **k: (lambda r, p: hit(r, p, **k))
K = lambda **k: (lambda r, p: knock(r, p, **k))
W = lambda **k: (lambda r, p: whoosh(r, p, **k))
X = lambda **k: (lambda r, p: explode(r, p, **k))
R = lambda **k: (lambda r, p: roar(r, p, **k))
B = lambda **k: (lambda r, p: beam(r, p, **k))
M = lambda **k: (lambda r, p: magic(r, p, **k))
F = lambda **k: (lambda r, p: fire(r, p, **k))
C = lambda **k: (lambda r, p: chime(r, p, **k))
Hm = lambda **k: (lambda r, p: hum(r, p, **k))
G = lambda **k: (lambda r, p: glass(r, p, **k))
Z = lambda **k: (lambda r, p: zap(r, p, **k))

RECIPES = {
    'block.amethyst_block.break': combo((G(hits=8, base=2400, d=0.7), 0, 1.0)),
    'block.amethyst_block.chime': C(notes=(1047, 1568), d=1.2, gap=0.12, decay=0.9),
    'block.amethyst_block.place': C(notes=(1175,), d=0.5, decay=0.35),
    'block.amethyst_block.resonate': C(notes=(784, 1175, 1568), d=1.6, gap=0.2, decay=1.2),
    'block.anvil.land': combo((H(d=0.7, f=140, grit=0.5, lp=3500), 0, 1.0), (lambda r, p: bell(1200 * p, 0.7, 0.35), 0, 0.5)),
    'block.anvil.place': combo((H(d=0.4, f=180, grit=0.5, lp=3000), 0, 0.9), (lambda r, p: bell(1500 * p, 0.4, 0.2), 0, 0.4)),
    'block.beacon.activate': combo((B(f0=200, f1=900, d=1.0), 0, 0.6), (C(notes=(523, 784, 1047, 1568), d=1.5, gap=0.14, decay=0.9), 0.5, 0.8)),
    'block.beacon.ambient': Hm(f=140, d=2.4, lfo=0.6),
    'block.beacon.deactivate': B(f0=1200, f1=120, d=1.2, charge=False),
    'block.beacon.power_select': C(notes=(784, 1047), d=0.9, gap=0.1, decay=0.7),
    'block.brewing_stand.brew': lambda r, p: liquid(r, p, 1.1),
    'block.chain.place': lambda r, p: clink(r, p, 0.6, 5),
    'block.enchantment_table.use': combo((M(f=400, d=1.1, up=2.2), 0, 0.9), (page, 0, 0.5)),
    'block.end_portal.spawn': combo((Hm(f=70, d=2.8, lfo=1.5, shimmer=0.5), 0, 0.9), (B(f0=100, f1=1400, d=2.2), 0.2, 0.6)),
    'block.glass.break': G(hits=18, base=2800, d=1.0),
    'block.glass.place': G(hits=4, base=3200, d=0.4),
    'block.portal.ambient': Hm(f=85, d=3.0, lfo=0.45, shimmer=0.5),
    'block.portal.travel': combo((W(f0=150, f1=2600, d=2.0, q=0.8), 0, 0.9), (Hm(f=65, d=2.2, lfo=2.0, shimmer=0.3), 0, 0.6)),
    'block.stone.break': combo((K(d=0.5, f=160, lp=2500), 0, 1.0), (X(d=0.4, lp=1500, sub=0.2, crackle=0.2), 0.02, 0.5)),
    'block.stone.place': K(d=0.25, f=180, lp=1800),
    'block.wood.break': combo((K(d=0.35, f=260, lp=2600), 0, 1.0), (K(d=0.25, f=360, lp=3200), 0.05, 0.6)),
    'entity.arrow.hit_player': combo((H(d=0.25, f=220, grit=0.8, lp=2600), 0, 0.9)),
    'entity.arrow.shoot': arrow,
    'entity.blaze.ambient': combo((F(d=0.9, lp=2500), 0, 0.8), (R(f=130, d=0.9, grit=0.3, lp=600), 0.05, 0.4)),
    'entity.blaze.shoot': combo((F(d=0.5, lp=4000), 0, 1.0), (W(f0=500, f1=2800, d=0.45), 0, 0.5)),
    'entity.breeze.wind_burst': combo((W(f0=300, f1=1800, d=0.6, q=1.0), 0, 1.0), (H(d=0.3, f=120, grit=0.2), 0, 0.5)),
    'entity.ender_dragon.growl': R(f=60, d=2.0, vib=4, grit=0.9, lp=900),
    'entity.enderman.teleport': lambda r, p: teleport(r, p, 0.45),
    'entity.evoker.cast_spell': M(f=300, d=0.9, up=1.6),
    'entity.evoker.prepare_summon': combo((M(f=220, d=1.3, up=2.0), 0, 1.0), (Hm(f=100, d=1.3, shimmer=0.2), 0, 0.5)),
    'entity.firework_rocket.blast': combo((X(d=0.5, lp=3000, sub=0.3, crackle=1.0), 0, 0.8), (G(hits=10, base=4000, d=0.8), 0.1, 0.4)),
    'entity.generic.drink': liquid,
    'entity.generic.explode': X(d=1.3, lp=700),
    'entity.lightning_bolt.impact': combo((Z(d=0.5, f=2400), 0, 1.0), (X(d=0.8, lp=1200, sub=0.8), 0, 0.7)),
    'entity.lightning_bolt.thunder': thunder,
    'entity.player.attack.crit': combo((swing, 0, 0.7), (H(d=0.3, f=200, grit=0.8, lp=4000), 0.1, 0.9), (lambda r, p: bell(2200 * p, 0.3, 0.1), 0.1, 0.4)),
    'entity.player.attack.knockback': combo((swing, 0, 0.5), (H(d=0.4, f=120, grit=0.6, lp=2000), 0.08, 1.0)),
    'entity.player.attack.strong': combo((swing, 0, 0.6), (H(d=0.3, f=170, grit=0.7, lp=3000), 0.08, 0.9)),
    'entity.player.attack.sweep': lambda r, p: swing(r, p, 0.32, 700, 4500),
    'entity.player.hurt_freeze': combo((G(hits=6, base=3800, d=0.5), 0, 0.7), (W(f0=2500, f1=700, d=0.4), 0, 0.5)),
    'entity.player.levelup': levelup,
    'entity.ravager.roar': R(f=75, d=1.3, vib=5, grit=0.9, lp=1000),
    'entity.ravager.step': H(d=0.3, f=70, grit=0.5, lp=900),
    'entity.splash_potion.break': combo((G(hits=6, base=2600, d=0.5), 0, 0.7), (lambda r, p: liquid(r, p, 0.6), 0.05, 0.7)),
    'entity.warden.emerge': combo((R(f=45, d=2.8, vib=2, grit=0.7, lp=500), 0, 1.0), (K(d=0.6, f=90, lp=900), 0.3, 0.7)),
    'entity.warden.roar': R(f=50, d=2.2, vib=3, grit=1.0, lp=700),
    'entity.warden.sonic_boom': combo((B(f0=300, f1=60, d=0.9, charge=False), 0, 1.0), (X(d=0.9, lp=1500, sub=1.0), 0.05, 0.8)),
    'entity.warden.sonic_charge': B(f0=150, f1=1100, d=1.4),
    'entity.witch.throw': W(f0=600, f1=1900, d=0.3, q=0.7),
    'entity.wither.ambient': R(f=65, d=1.5, vib=3, grit=0.5, lp=700),
    'entity.wither.shoot': combo((W(f0=300, f1=1400, d=0.45), 0, 0.8), (R(f=100, d=0.5, grit=0.5, lp=700), 0, 0.6)),
    'entity.wither.spawn': combo((R(f=48, d=3.0, vib=3, grit=0.9, lp=800), 0, 1.0), (B(f0=80, f1=900, d=2.4), 0.2, 0.5), (X(d=1.0, lp=600), 2.2, 0.8)),
    'item.book.page_turn': page,
    'item.crossbow.shoot': combo((H(d=0.25, f=240, grit=0.7, lp=3500), 0, 0.7), (arrow, 0.02, 0.7)),
    'item.firecharge.use': combo((F(d=0.5, lp=3500), 0, 1.0), (W(f0=400, f1=1500, d=0.4), 0, 0.4)),
    'item.mace.smash_air': combo((W(f0=300, f1=1500, d=0.45), 0, 0.7), (H(d=0.6, f=100, grit=0.8, lp=2500), 0.35, 1.0)),
    'item.mace.smash_ground': combo((H(d=0.8, f=75, grit=0.9, lp=2000), 0, 1.0), (K(d=0.5, f=140, lp=2000), 0.02, 0.6)),
    'item.shield.block': combo((H(d=0.35, f=260, grit=0.5, lp=3000), 0, 0.9), (lambda r, p: bell(900 * p, 0.35, 0.15), 0, 0.5)),
    'item.totem.use': totem,
    'item.trident.thunder': combo((Z(d=0.5, f=2000), 0, 1.0), (thunder, 0.05, 0.6)),
    'particle.soul_escape': M(f=250, d=0.7, up=0.5),
    'ui.toast.challenge_complete': C(notes=(523, 659, 784, 1047, 784, 1047, 1319), d=1.2, gap=0.12, decay=0.8),
    'item.mana_potion': lambda r, p: liquid(r, p * 1.25, 0.8),
    'item.scroll': lambda r, p: page(r, p * 0.85, 0.5),
    'item.tome': combo((M(f=330, d=1.0, up=2.0), 0, 0.8), (lambda r, p: page(r, p * 0.7), 0, 0.6)),
    'item.echo_shard': C(notes=(1397, 2093), d=1.5, gap=0.18, decay=1.1),
    'item.respec_token': combo((M(f=500, d=1.0, up=0.4), 0, 0.9), (C(notes=(1568, 1175, 784), d=0.9, gap=0.1, decay=0.6), 0.1, 0.6)),
    'item.trail_marker': C(notes=(1760,), d=0.4, decay=0.25),
    'grave.open': combo((K(d=0.6, f=100, lp=1200), 0, 1.0), (M(f=250, d=1.2, up=0.6), 0.1, 0.7)),
    'chest.smash': combo((K(d=0.4, f=220, lp=2800), 0, 1.0), (K(d=0.3, f=320, lp=3400), 0.04, 0.7), (K(d=0.3, f=180, lp=2200), 0.1, 0.6)),
    'boss.spawn': combo((R(f=55, d=2.4, vib=3, grit=0.9, lp=900), 0, 1.0), (X(d=1.0, lp=700, sub=1.0), 0, 0.7), (B(f0=90, f1=800, d=1.8), 0, 0.4)),
    'boss.death': combo((R(f=70, d=2.4, vib=4, grit=0.7, lp=700), 0, 0.9), (B(f0=900, f1=60, d=2.2, charge=False), 0.2, 0.6), (X(d=1.2, lp=500), 1.6, 0.8)),
    'gate.ambient': Hm(f=95, d=3.0, lfo=0.5, shimmer=0.4),
    'skill.shockwave': combo((X(d=0.9, lp=800), 0, 1.0), (W(f0=2500, f1=250, d=0.5, q=0.9), 0, 0.5)),
    'skill.war_cry': R(f=95, d=1.2, vib=7, grit=0.9, lp=1500),
    'skill.leap_slam': combo((W(f0=300, f1=1800, d=0.35), 0, 0.6), (H(d=0.7, f=85, grit=0.8, lp=1800), 0.3, 1.0)),
    'skill.earthbreaker': combo((H(d=1.0, f=60, grit=1.0, lp=1500), 0, 1.0), (K(d=0.6, f=110, lp=1500), 0.05, 0.8), (X(d=1.1, lp=500), 0.1, 0.6)),
    'skill.shadow_step': lambda r, p: teleport(r, p * 1.2, 0.3),
    'skill.vanish': combo((lambda r, p: teleport(r, p * 0.8, 0.6, True), 0, 1.0), (Hm(f=180, d=0.7, shimmer=0.4), 0, 0.3)),
    'skill.thousand_cuts': combo(*[(lambda r, p, i=i: swing(r, p * (1 + 0.04 * (i % 5)), 0.12, 1500, 4800), 0.07 * i, 0.5) for i in range(9)]),
    'skill.rain_of_arrows': combo(*[(arrow, 0.09 * i, 0.5) for i in range(7)]),
    'skill.apex_predator': R(f=110, d=1.0, vib=8, grit=0.7, lp=1800),
    'skill.fireball': combo((F(d=0.7, lp=4000), 0, 1.0), (W(f0=300, f1=1400, d=0.6), 0, 0.5)),
    'skill.meteor': combo((W(f0=1800, f1=150, d=0.8, q=0.9), 0, 0.8), (X(d=1.6, lp=600, sub=1.0), 0.8, 1.0), (F(d=0.8), 0.8, 0.5)),
    'skill.inferno': combo((F(d=1.4, lp=2600), 0, 1.0), (R(f=80, d=1.4, grit=0.4, lp=500), 0, 0.5)),
    'skill.absolute_zero': combo((G(hits=22, base=3400, d=1.4), 0.15, 0.8), (B(f0=3000, f1=120, d=1.0, charge=False), 0, 0.6)),
    'skill.thunderstrike': combo((Z(d=0.6, f=2600), 0, 1.0), (thunder, 0.05, 0.9)),
    'skill.tornado': combo((W(f0=200, f1=1300, d=1.6, q=1.0), 0, 1.0), (Hm(f=130, d=1.6, lfo=3, shimmer=0.3), 0, 0.4)),
    'skill.earthquake': combo((H(d=1.8, f=45, grit=1.0, lp=600), 0, 1.0), (X(d=1.8, lp=300, sub=1.0, crackle=0.1), 0, 0.7)),
    'skill.holy_smite': combo((B(f0=500, f1=2500, d=0.5), 0, 0.7), (C(notes=(1047, 1568, 2093), d=1.2, gap=0.05, decay=0.8), 0.45, 0.9)),
    'skill.resurrection': combo((totem, 0, 1.0), (Hm(f=220, d=1.6, shimmer=0.3), 0, 0.4)),
    'skill.black_hole': combo((B(f0=700, f1=40, d=1.6, charge=False), 0, 1.0), (Hm(f=40, d=1.8, lfo=4, shimmer=0.1), 0, 0.7)),
    'skill.soul_storm': combo((M(f=200, d=1.4, up=0.5), 0, 0.9), (W(f0=300, f1=2000, d=1.4, q=1.0), 0, 0.5), (R(f=100, d=1.2, grit=0.4, lp=500), 0, 0.4)),
    'skill.legion_call': combo((M(f=180, d=1.3, up=2.0), 0, 0.9), (R(f=85, d=1.3, vib=3, grit=0.4, lp=600), 0, 0.6), (C(notes=(392, 523, 659), d=0.9, gap=0.15, decay=0.6), 0.5, 0.6)),
}


def finish(x):
    n = len(x)
    f = min(int(0.04 * SR), n // 2)
    x = x.copy()
    x[-f:] *= np.linspace(1, 0, f)
    x[:64] *= np.linspace(0, 1, 64)
    x = filt(x, 'high', 30, 1)
    peak = np.max(np.abs(x)) or 1
    return (x / peak * 0.85).astype(np.float32)


def encode(x, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as tmp:
        name = tmp.name
    with wave.open(name, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype('<i2').tobytes())
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', name, '-c:a', 'libvorbis', '-q:a', '4', path], check=True)
    os.remove(name)


def main():
    sj_path = os.path.join(ASSETS, 'sounds.json')
    sj = json.load(open(sj_path, encoding='utf-8'))
    missing = [k for k in sj if k not in RECIPES]
    if missing:
        sys.exit('no recipe for: ' + ', '.join(missing))
    total = 0
    for path, recipe in RECIPES.items():
        if path not in sj:
            continue
        rel = path.replace('.', '/')
        entries = []
        for v, pitch in enumerate((0.96, 1.05), 1):
            rng = np.random.default_rng(sum(map(ord, path)) * 7 + v)
            x = finish(recipe(rng, pitch))
            encode(x, os.path.join(OUT, f'{rel}_{v}.ogg'))
            entries.append({'name': f'gatebound:{rel}_{v}', 'volume': 1.0})
            total += os.path.getsize(os.path.join(OUT, f'{rel}_{v}.ogg'))
        sj[path]['sounds'] = entries
    json.dump(sj, open(sj_path, 'w', encoding='utf-8'), indent=2)
    print(f'{len(RECIPES)} events, {total / 1024:.0f} KiB of ogg')


if __name__ == '__main__':
    main()
