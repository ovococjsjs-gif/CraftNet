#!/usr/bin/env python3
"""Генератор пиксельных текстур CraftNet (16x16 PNG, без PIL).

Запуск:  python3 tools/gen_textures.py
Вывод:    src/main/resources/assets/craftnet/textures/{item,block}/*.png
"""
import os
import struct
import zlib

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "craftnet", "textures")
S = 16


def pack_chunk(tag, data):
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)


def write_png(path, pixels):
    """pixels: S*S список (r, g, b, a)."""
    raw = bytearray()
    for y in range(S):
        raw.append(0)
        for x in range(S):
            r, g, b, a = pixels[y * S + x]
            raw += bytes((r, g, b, a))
    ihdr = struct.pack(">IIBBBBB", S, S, 8, 6, 0, 0, 0)
    data = b"\x89PNG\r\n\x1a\n" + pack_chunk(b"IHDR", ihdr) + \
        pack_chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + pack_chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)


class Canvas:
    def __init__(self, base=(0, 0, 0, 0)):
        self.p = [base] * (S * S)

    def fill(self, c):
        self.p = [c] * (S * S)

    def set(self, x, y, c):
        if 0 <= x < S and 0 <= y < S:
            self.p[y * S + x] = c

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1):
            for x in range(x0, x1):
                self.set(x, y, c)

    def border(self, x0, y0, x1, y1, c):
        for x in range(x0, x1):
            self.set(x, y0, c)
            self.set(x, y1 - 1, c)
        for y in range(y0, y1):
            self.set(x0, y, c)
            self.set(x1 - 1, y, c)

    def hline(self, x0, x1, y, c):
        for x in range(x0, x1):
            self.set(x, y, c)

    def vline(self, x, y0, y1, c):
        for y in range(y0, y1):
            self.set(x, y, c)

    def shade(self, x0, y0, x1, y1, f):
        for y in range(y0, y1):
            for x in range(x0, x1):
                r, g, b, a = self.p[y * S + x]
                self.p[y * S + x] = (min(255, int(r * f)), min(255, int(g * f)), min(255, int(b * f)), a)

    def save(self, rel):
        write_png(os.path.join(ROOT, rel), self.p)


# ------------------------------ предметы ------------------------------

def phone():
    c = Canvas()
    body = (28, 30, 36, 255)
    body_l = (48, 52, 62, 255)
    screen = (35, 90, 120, 255)
    screen_l = (70, 160, 200, 255)
    c.rect(4, 1, 12, 15, body)
    c.border(4, 1, 12, 15, body_l)
    c.rect(5, 3, 11, 12, screen)
    # «приложения» на экране
    c.set(6, 5, (90, 200, 120, 255))
    c.set(8, 5, (240, 190, 60, 255))
    c.set(10, 5, (240, 90, 90, 255))
    c.set(6, 7, (200, 200, 220, 255))
    c.set(8, 7, (120, 200, 240, 255))
    c.set(10, 7, (200, 140, 240, 255))
    c.hline(6, 11, 10, screen_l)
    c.set(7, 13, body_l)  # кнопка home
    c.save(os.path.join("item", "phone.png"))


def banknote():
    c = Canvas()
    paper = (60, 140, 70, 255)
    paper_l = (120, 200, 120, 255)
    seal = (30, 90, 40, 255)
    c.rect(1, 4, 15, 12, paper)
    c.border(1, 4, 15, 12, paper_l)
    c.rect(3, 6, 6, 10, seal)
    c.rect(10, 6, 13, 10, seal)
    c.hline(7, 9, 7, paper_l)
    c.hline(7, 9, 9, paper_l)
    c.save(os.path.join("item", "banknote.png"))


def food_box():
    c = Canvas()
    bag = (210, 180, 140, 255)
    bag_d = (170, 135, 95, 255)
    stripe = (200, 60, 50, 255)
    c.rect(4, 4, 12, 15, bag)
    c.rect(3, 2, 13, 5, bag_d)      # загнутый верх
    c.rect(4, 7, 12, 9, stripe)
    c.set(7, 8, (255, 255, 255, 255))
    c.set(8, 8, (255, 255, 255, 255))
    c.save(os.path.join("item", "food_box.png"))


# ------------------------------ блоки ------------------------------

def wood_frame(c, inside):
    frame = (95, 65, 35, 255)
    c.rect(0, 0, S, S, inside)
    c.border(0, 0, S, S, frame)
    return c


def pvz_counter_side():
    c = Canvas()
    wood_frame(c, (200, 120, 40, 255))
    # панель посылок-ячеек
    for i in range(3):
        for j in range(3):
            c.rect(3 + i * 4, 3 + j * 4, 5 + i * 4, 5 + j * 4, (170, 95, 30, 255))
            c.set(4 + i * 4, 4 + j * 4, (240, 200, 120, 255))
    c.border(2, 2, 14, 14, (120, 70, 20, 255))
    c.set(7, 7, (255, 255, 255, 255))
    c.save(os.path.join("block", "pvz_counter_side.png"))


def pvz_counter_top():
    c = Canvas()
    wood_frame(c, (210, 130, 50, 255))
    c.rect(3, 6, 13, 9, (60, 40, 25, 255))  # щель выдачи
    c.hline(4, 12, 7, (240, 220, 160, 255))
    c.save(os.path.join("block", "pvz_counter_top.png"))


def bank_terminal_side():
    c = Canvas()
    wood_frame(c, (50, 52, 60, 255))
    c.rect(2, 3, 14, 8, (20, 80, 60, 255))   # экран
    c.rect(3, 4, 13, 7, (70, 200, 150, 255))
    c.hline(4, 12, 10, (240, 200, 80, 255))  # золотая полоса
    c.hline(4, 12, 11, (200, 160, 50, 255))
    c.save(os.path.join("block", "bank_terminal_side.png"))


def bank_terminal_top():
    c = Canvas()
    wood_frame(c, (45, 48, 56, 255))
    c.border(3, 3, 13, 13, (240, 200, 80, 255))
    c.rect(6, 6, 10, 10, (240, 200, 80, 255))
    c.save(os.path.join("block", "bank_terminal_top.png"))


def cafe_counter_side():
    c = Canvas()
    wood_frame(c, (120, 80, 50, 255))
    c.rect(0, 0, S, 3, (235, 225, 210, 255))  # крышка
    # чашка
    c.rect(6, 6, 10, 11, (245, 245, 245, 255))
    c.set(10, 7, (245, 245, 245, 255))
    c.set(10, 8, (245, 245, 245, 255))
    c.hline(6, 9, 5, (150, 100, 70, 255))
    c.save(os.path.join("block", "cafe_counter_side.png"))


def cafe_counter_top():
    c = Canvas()
    for y in range(S):
        for x in range(S):
            c.set(x, y, (235, 225, 210, 255) if (x // 4 + y // 4) % 2 == 0 else (195, 180, 160, 255))
    c.border(0, 0, S, S, (95, 65, 35, 255))
    c.save(os.path.join("block", "cafe_counter_top.png"))


def factory_station():
    c = Canvas((70, 72, 80, 255))
    c.border(0, 0, S, S, (45, 46, 52, 255))
    # шевронная аварийная полоса снизу
    for x in range(S):
        for k in range(3):
            y = 12 + k
            if ((x + k) // 4) % 2 == 0:
                c.set(x, y, (240, 200, 60, 255))
    # заклёпки
    for px in (2, 13):
        for py in (2, 9):
            c.set(px, py, (150, 155, 165, 255))
    c.rect(4, 3, 12, 9, (90, 95, 105, 255))
    c.rect(5, 4, 11, 8, (30, 32, 38, 255))
    c.set(6, 5, (240, 80, 80, 255))
    c.set(8, 5, (90, 220, 120, 255))
    c.set(10, 5, (240, 200, 60, 255))
    c.save(os.path.join("block", "factory_station.png"))


def tower_core():
    c = Canvas((35, 30, 45, 255))
    c.border(0, 0, S, S, (90, 40, 40, 255))
    # антенна
    c.vline(8, 2, 8, (120, 220, 230, 255))
    c.set(6, 4, (120, 220, 230, 255))
    c.set(10, 4, (120, 220, 230, 255))
    c.set(5, 6, (90, 170, 190, 255))
    c.set(11, 6, (90, 170, 190, 255))
    c.set(8, 2, (255, 90, 90, 255))  # огонёк
    c.rect(5, 9, 12, 14, (200, 60, 60, 255))
    c.rect(6, 10, 11, 13, (150, 40, 40, 255))
    c.save(os.path.join("block", "tower_core.png"))


def cargo_crate():
    c = Canvas((150, 105, 60, 255))
    frame = (110, 75, 40, 255)
    c.border(0, 0, S, S, frame)
    c.hline(0, S, 5, (170, 120, 70, 255))
    c.hline(0, S, 10, (170, 120, 70, 255))
    c.vline(7, 0, S, frame)
    c.set(3, 3, frame)
    c.set(12, 12, frame)
    c.set(12, 3, frame)
    c.set(3, 12, frame)
    c.save(os.path.join("block", "cargo_crate.png"))


if __name__ == "__main__":
    phone(); banknote(); food_box()
    pvz_counter_side(); pvz_counter_top()
    bank_terminal_side(); bank_terminal_top()
    cafe_counter_side(); cafe_counter_top()
    factory_station(); tower_core(); cargo_crate()
    print("textures written to", ROOT)
