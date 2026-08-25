#!/usr/bin/env python3
"""Генератор NBT-структур CraftNet (здания для деревень).

ASCII-слои: каждый слой = список строк по z, символ = x. Слои заданы от y=0 вверх.
Легенда блоков — BLOCK_CHARS. Цифры 1..4 — блоки мода (ПВЗ/банк/завод/кафе).
Персонал — сущности внутри файла (как жители в шаблонах домов ваниллы),
со статусом NoAI/Invulnerable и тегами craftnet:* — на них срабатывают
обработчики взаимодействия на сервере.

Запуск:  python3 tools/gen_structures.py
Вывод:   src/main/resources/data/craftnet/structure/village/*.nbt  (gzip NBT)
"""
import gzip
import os
import struct

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "data", "craftnet", "structure", "village")
DATA_VERSION = 4671  # 1.21.11

BYTE, INT, LONG, FLOAT, DOUBLE, STRING, LIST, COMPOUND, INTARRAY = 1, 3, 4, 5, 6, 8, 9, 10, 11


def _str(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def payload(tagid, val):
    if isinstance(val, tuple) and len(val) == 2 and val[0] == tagid:
        # позволяет вкладывать C(...)/L(...) прямо как элементы списков/корней
        # (для LIST значение всегда (child_id, items) и child_id != LIST)
        tagid, val = val
    if tagid == BYTE:
        return struct.pack(">b", val)
    if tagid == INT:
        return struct.pack(">i", val)
    if tagid == LONG:
        return struct.pack(">q", val)
    if tagid == FLOAT:
        return struct.pack(">f", val)
    if tagid == DOUBLE:
        return struct.pack(">d", val)
    if tagid == STRING:
        return _str(val)
    if tagid == INTARRAY:
        return struct.pack(">i", len(val)) + b"".join(struct.pack(">i", x) for x in val)
    if tagid == LIST:
        child_id, items = val
        body = b"".join(payload(child_id, x) for x in items)
        return bytes([child_id]) + struct.pack(">i", len(items)) + body
    if tagid == COMPOUND:
        body = b"".join(named(t, n, v) for n, (t, v) in val.items())
        return body + b"\x00"
    raise ValueError(tagid)


def named(tagid, name, val):
    return bytes([tagid]) + _str(name) + payload(tagid, val)


# удобные конструкторы
def C(i):
    return (COMPOUND, i)


def L(child, items):
    return (LIST, (child, items))


def S(v):
    return (STRING, v)


def I(v):
    return (INT, v)


def B(v):
    return (BYTE, v)


BLOCK_CHARS = {
    ".": None,
    "S": "minecraft:stone_bricks",
    "P": "minecraft:oak_planks",
    "L": "minecraft:oak_log",
    "G": "minecraft:glass",
    "R": "minecraft:spruce_planks",
    "F": "minecraft:oak_fence",
    "N": "minecraft:lantern",
    "C": "minecraft:cobblestone",
    "H": "minecraft:bookshelf",
    "T": "minecraft:crafting_table",
    "U": "minecraft:furnace",
    "K": "minecraft:smoker",
    "M": "minecraft:smooth_stone_slab",
    "1": "craftnet:pvz_counter",
    "2": "craftnet:bank_terminal",
    "3": "craftnet:factory_station",
    "4": "craftnet:cafe_counter",
    # Entrance connector for vanilla village/*/houses pools. The building
    # entrance is on the south edge, so the connector faces south.
    "J": "minecraft:jigsaw",
}


def build_nbt(layers, entities):
    """layers: список слоёв (y от низа к верху), слой = список строк по z."""
    palette = []
    palette_idx = {}
    blocks = []
    for y, layer in enumerate(layers):
        for z, row in enumerate(layer):
            for x, ch in enumerate(row):
                name = BLOCK_CHARS.get(ch)
                # Above the foundation, dots are explicit air: templates must
                # clear grass, leaves and terrain from rooms. At y=0 they stay
                # structure-void-like so roof padding does not carve the ground.
                if ch == "." and y > 0:
                    name = "minecraft:air"
                if name is None:
                    continue
                props = {"orientation": "south_up"} if ch == "J" else {}
                pkey = (name, tuple(sorted(props.items())))
                if pkey not in palette_idx:
                    palette_idx[pkey] = len(palette)
                    palette.append((name, props))
                block_nbt = None
                if ch == "J":
                    block_nbt = {
                        "name": S("minecraft:building_entrance"),
                        "target": S("minecraft:street"),
                        "pool": S("minecraft:empty"),
                        "final_state": S("minecraft:air"),
                        "joint": S("rollable"),
                    }
                blocks.append((x, y, z, palette_idx[pkey], block_nbt))
    sx = max(b[0] for b in blocks) + 1
    sy = max(b[1] for b in blocks) + 1
    sz = max(b[2] for b in blocks) + 1

    palette_nbt = []
    for name, props in palette:
        row = {"Name": S(name)}
        if props:
            row["Properties"] = C({k: S(v) for k, v in props.items()})
        palette_nbt.append(C(row))
    block_rows = []
    for x, y, z, st, block_nbt in blocks:
        row = {"state": I(st), "pos": L(INT, [x, y, z])}
        if block_nbt is not None:
            row["nbt"] = C(block_nbt)
        block_rows.append(C(row))

    root = {
        "size": L(INT, [sx, sy, sz]),
        "palette": L(COMPOUND, palette_nbt),
        "blocks": L(COMPOUND, block_rows),
        "entities": L(COMPOUND, entities),
        "DataVersion": I(DATA_VERSION),
    }
    # .nbt файл структуры: GZIP(TAG_Compound + пустое имя + payload)
    return gzip.compress(named(COMPOUND, "", root), mtime=0)


def text_component(text):
    """Text-компонент в NBT-форме (1.21.5+): компаунд {text: ...}."""
    return C({"text": S(text)})


def entity(x, y, z, nbt_extra):
    """nbt_extra: dict с полями сущности (включая 'id'). Координаты — уже в СЕТКЕ файла."""
    nbt = dict(nbt_extra)
    nbt.setdefault("Pos", L(DOUBLE, [x + 0.5, float(y), z + 0.5]))
    nbt.setdefault("Rotation", L(FLOAT, [0.0, 0.0]))
    ent = {
        "pos": L(DOUBLE, [x + 0.5, float(y), z + 0.5]),
        "blockPos": L(INT, [x, y, z]),
        "nbt": C(nbt),
    }
    return C(ent)


def npc_common(display_name, tags):
    return {
        "NoAI": B(1),
        "PersistenceRequired": B(1),
        "Invulnerable": B(1),
        "Silent": B(1),
        "Tags": L(STRING, list(tags)),
        "CustomName": text_component(display_name),
        "CustomNameVisible": B(1),
    }


def write(name, layers, entities):
    data = build_nbt(layers, entities)
    os.makedirs(ROOT, exist_ok=True)
    with open(os.path.join(ROOT, name + ".nbt"), "wb") as f:
        f.write(data)
    print("structure:", name, f"({len(data)} bytes)")


def pad(layers, width, offset=1):
    """Расширить слои воздухом до width x width: сдвиг по x на offset, хвост по z."""
    out = []
    for layer in layers:
        if layer is None:
            continue
        new = []
        for row in layer:
            new.append("." * offset + row + "." * (width - len(row) - offset))
        for _ in range(width - len(layer)):
            new.append("." * width)
        out.append(new)
    return out


# ============================== ПВЗ (7x5x7, крыша 9x9) ==============================

def pvz():
    base = [
        # y0: пол
        ["CCCCCCC"] * 7,
        # y1: стены + стойка; вход с юга (z=6)
        [
            "LLLLLLL",
            "L.....L",
            "L..1..L",
            "L.....L",
            "L.....L",
            "L..N..L",
            "LL.J.LL",
        ],
        # y2: окна
        [
            "LGLGLGL",
            "G.....G",
            "L.....L",
            "G.....G",
            "L.....L",
            "L.....L",
            "LL...LL",
        ],
        # y3: сплошной пояс
        ["LLLLLLL"] + ["L.....L"] * 5 + ["LL...LL"],
    ]
    layers = pad(base, 9, offset=1) + [["RRRRRRRRR"] * 9]  # y4: крыша-навес
    # после сдвига pad(offset=1) координаты x +1
    ent = [entity(5, 1, 3, {
        # A WanderingTrader has its own despawn timer even when it looks ideal
        # for a clerk. A persistent villager is safe across long-running worlds.
        "id": S("minecraft:villager"),
        "VillagerData": C({"profession": S("minecraft:cartographer"), "level": I(5), "type": S("minecraft:plains")}),
        **npc_common("ПВЗ", ["craftnet:pvz"]),
    })]
    write("pvz", layers, ent)


# ============================== Банк (8x4x8, крыша 10x10) ==============================

def bank():
    base = [
        ["SSSSSSSS"] * 8,  # y0 пол
        [  # y1
            "SSSSSSSS",
            "S......S",
            "S..HH..S",
            "S..22..S",
            "S......S",
            "S......S",
            "S......S",
            "SS.J..SS",
        ],
        [  # y2 окна
            "SGSGSGSS",
            "SG....GS",
            "S......S",
            "S......S",
            "G......G",
            "S......S",
            "S......S",
            "SS....SS",
        ],
        [  # y3 верх + фонарь
            "SSSSSSSS",
            "S......S",
            "S......S",
            "S...N..S",
            "S......S",
            "S......S",
            "S......S",
            "SS....SS",
        ],
    ]
    layers = pad(base, 10, offset=1) + [["RRRRRRRRRR"] * 10]  # y4 крыша
    ent = [entity(6, 1, 2, {
        "id": S("minecraft:villager"),
        "VillagerData": C({"profession": S("minecraft:librarian"), "level": I(5), "type": S("minecraft:plains")}),
        **npc_common("Банкир", ["craftnet:bank"]),
    })]
    write("bank", layers, ent)


# ============================== Завод (9x5x9, крыша 11x11) ==============================

def factory():
    base = [
        ["SSSSSSSSS"] * 9,  # y0 пол
        [  # y1
            "LSSSSSSSL",
            "S.......S",
            "S.U...T.S",
            "S...3...S",
            "S.......S",
            "S..K....S",
            "S.......S",
            "S.......S",
            "LSS.J.SSL",
        ],
        [  # y2
            "LSSSSSSSL",
            "S.......S",
            "S.......S",
            "S.......S",
            "S.......S",
            "S.......S",
            "S.......S",
            "S.......S",
            "LSS...SSL",
        ],
        [  # y3 высокие окна
            "LGLGLGLGL",
            "G.......G",
            "L.......L",
            "G.......G",
            "L.......L",
            "G.......G",
            "L.......L",
            "G.......G",
            "LSS...SSL",
        ],
        [  # y4 верх + фонарь
            "LSSSSSSSL",
            "S.......S",
            "S....N..S",
            "S.......S",
            "S.......S",
            "S.......S",
            "S.......S",
            "S.......S",
            "LSS...SSL",
        ],
    ]
    layers = pad(base, 11, offset=1) + [["RRRRRRRRRRR"] * 11]  # y5 крыша
    ent = [entity(5, 1, 4, {
        "id": S("minecraft:villager"),
        "VillagerData": C({"profession": S("minecraft:toolsmith"), "level": I(5), "type": S("minecraft:plains")}),
        **npc_common("Бригадир", ["craftnet:foreman"]),
    })]
    write("factory", layers, ent)


# ============================== Кафе (9x4x9, крыша 11x11) ==============================

def cafe():
    base = [
        ["PPPPPPPPP"] * 9,  # y0 пол (дуб)
        [  # y1: стойка, столики (забор+полуплита), фонарь
            "LLLLLLLLL",
            "L.......L",
            "L..444..L",
            "L.F...F.L",
            "L.......L",
            "L..M.M..L",
            "L.......L",
            "L..N....L",
            "LLL.J.LLL",
        ],
        [  # y2: коптильни за стойкой
            "LGLGLGLGL",
            "L.......L",
            "L..KK...L",
            "L.......L",
            "G.......G",
            "L.......L",
            "G.......G",
            "L.......L",
            "LLL...LLL",
        ],
        [  # y3 верх + фонарь
            "LLLLLLLLL",
            "L.......L",
            "L.......L",
            "L...N...L",
            "L.......L",
            "L.......L",
            "L.......L",
            "L.......L",
            "LLL...LLL",
        ],
    ]
    layers = pad(base, 11, offset=1) + [["RRRRRRRRRRR"] * 11]  # y4 крыша
    # бариста стоит ЗА стойкой (стойка z=2, x=4..6 после сдвига)
    ent = [entity(5, 1, 1, {
        "id": S("minecraft:villager"),
        "VillagerData": C({"profession": S("minecraft:butcher"), "level": I(5), "type": S("minecraft:plains")}),
        **npc_common("Бариста", ["craftnet:barista"]),
    })]
    write("cafe", layers, ent)


def selfcheck():
    """Мини-проверка: распарсить gzip-NBT обратно и сверить размеры/сущностей."""
    import io

    def rd(fmt, f):
        return struct.unpack(fmt, f.read(struct.calcsize(fmt)))[0]

    def read_named(f):
        t = rd(">b", f)
        if t == 0:
            return None, None
        ln = rd(">H", f)
        name = f.read(ln).decode("utf-8")
        return t, read_payload(t, f)

    def read_payload(t, f):
        if t == BYTE:
            return rd(">b", f)
        if t == INT:
            return rd(">i", f)
        if t == LONG:
            return rd(">q", f)
        if t == FLOAT:
            return rd(">f", f)
        if t == DOUBLE:
            return rd(">d", f)
        if t == STRING:
            ln = rd(">H", f)
            return f.read(ln).decode("utf-8")
        if t == INTARRAY:
            n = rd(">i", f)
            return [rd(">i", f) for _ in range(n)]
        if t == LIST:
            c = rd(">b", f)
            n = rd(">i", f)
            return [read_payload(c, f) for _ in range(n)]
        if t == COMPOUND:
            return read_compound(f)
        raise ValueError(t)

    def read_compound(f):
        d = {}
        while True:
            t = rd(">b", f)
            if t == 0:
                return d
            ln = rd(">H", f)
            name = f.read(ln).decode("utf-8")
            d[name] = read_payload(t, f)

    for fn in sorted(os.listdir(ROOT)):
        if not fn.endswith(".nbt"):
            continue
        raw = open(os.path.join(ROOT, fn), "rb").read()
        assert raw[:2] == b"\x1f\x8b", fn + ": нет gzip-заголовка!"
        bio = io.BytesIO(gzip.decompress(raw))
        t = rd(">b", bio)
        assert t == COMPOUND, fn + ": корень не compound"
        ln = rd(">H", bio)
        name = bio.read(ln).decode("utf-8")
        assert name == "", fn + ": имя корня не пустое"
        root = read_compound(bio)
        size = root["size"]
        nblocks = len(root["blocks"])
        nents = len(root["entities"])
        palette_names = [p["Name"] for p in root["palette"]]
        assert "minecraft:jigsaw" in palette_names, fn + ": нет jigsaw-коннектора входа"
        assert nents == 1, fn + ": персонал отсутствует или задублирован"
        print(f"  {fn}: size={size} blocks={nblocks} entities={nents} jigsaw=1 DataVersion={root['DataVersion']} OK")
        for e in root["entities"]:
            eid = e["nbt"]["id"]
            tag = e["nbt"]["Tags"]
            print(f"    entity {eid} tags={tag}")


if __name__ == "__main__":
    pvz()
    bank()
    factory()
    cafe()
    print("structures written to", os.path.abspath(ROOT))
    print("selfcheck:")
    selfcheck()
