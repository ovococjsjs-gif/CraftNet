#!/usr/bin/env python3
"""Регрессионный аудит прайса CraftNet (модель: ECONOMY.md).

Парсит таблицу PriceManager.java и проверяет антиарбитражные инварианты:
  I1 — «крафт на перепродажу» убыточен:  sell(продукт)×выход < buy(материалы)
  I2 — обратимые сжатия = ровно 9× (возможна скидка ликвидности ±3)
  I3 — эндгейм-якоря на месте

Запуск:  python3 tools/audit_prices.py
Код возврата 0 = всё чисто, 1 = найден арбитраж (печатает список).
"""
import re
import sys
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent / 'src/main/java/net/craftnet/econ/PriceManager.java'
src = SRC.read_text(encoding='utf-8')
puts = re.findall(r'put\(Items\.([A-Z_]+),\s*(\d+)\)', src)
T = {k.lower(): int(v) for k, v in puts}

FUEL = 0.5  # средняя доля угля (4 CR) на одну плавку

def sell(b: int) -> int:
    # зеркало PriceManager.sellPrice при дефолтных коэффициентах
    r = 0.55 if b <= 9 else (0.80 if b >= 500 else 0.68)
    return int(b * r)

# (продукт, [(материал, шт)], выход шт) — все крафты, где и материалы, и продукт в таблице
R = [
 ('stick', [('oak_planks', 2)], 4), ('torch', [('coal', 1), ('stick', 1)], 4),
 ('bread', [('wheat', 3)], 1), ('paper', [('sugar_cane', 3)], 3), ('sugar', [('sugar_cane', 1)], 1),
 ('bone_meal', [('bone', 1)], 3), ('book', [('paper', 3), ('leather', 1)], 1),
 ('bow', [('stick', 3), ('string', 3)], 1),
 ('crossbow', [('iron_ingot', 3), ('string', 2), ('stick', 1), ('tripwire_hook', 1)], 1),
 ('shield', [('oak_planks', 6), ('iron_ingot', 1)], 1), ('bucket', [('iron_ingot', 3)], 1),
 ('shears', [('iron_ingot', 2)], 1), ('fishing_rod', [('stick', 3), ('string', 2)], 1),
 ('flint_and_steel', [('iron_ingot', 1), ('flint', 1)], 1),
 ('brush', [('feather', 1), ('copper_ingot', 1), ('stick', 1)], 1),
 ('bundle', [('leather', 1), ('string', 1)], 1), ('mace', [('heavy_core', 1), ('breeze_rod', 1)], 1),
 ('beacon', [('nether_star', 1), ('glass', 5), ('obsidian', 3)], 1),
 ('end_crystal', [('ghast_tear', 1), ('ender_eye', 1), ('glass', 7)], 1),
 ('conduit', [('heart_of_the_sea', 1), ('nautilus_shell', 8)], 1),
 ('recovery_compass', [('echo_shard', 8), ('compass', 1)], 1),
 ('ender_chest', [('obsidian', 8), ('ender_eye', 1)], 1),
 ('ender_eye', [('ender_pearl', 1), ('blaze_powder', 1)], 1),
 ('blaze_powder', [('blaze_rod', 1)], 2), ('magma_cream', [('slime_ball', 1), ('blaze_powder', 1)], 1),
 ('fermented_spider_eye', [('spider_eye', 1), ('sugar', 1), ('brown_mushroom', 1)], 1),
 ('enchanting_table', [('obsidian', 4), ('diamond', 2), ('book', 1)], 1),
 ('anvil', [('iron_block', 3), ('iron_ingot', 4)], 1),
 ('brewing_stand', [('blaze_rod', 1), ('cobblestone', 3)], 1),
 ('cauldron', [('iron_ingot', 7)], 1), ('hopper', [('iron_ingot', 5), ('chest', 1)], 1),
 ('chest', [('oak_planks', 8)], 1), ('trapped_chest', [('chest', 1), ('tripwire_hook', 1)], 1),
 ('tripwire_hook', [('iron_ingot', 1), ('stick', 1), ('oak_planks', 1)], 2),
 ('furnace', [('cobblestone', 8)], 1),
 ('blast_furnace', [('iron_ingot', 5), ('furnace', 1), ('stone', 3)], 1),
 ('smoker', [('oak_log', 4), ('furnace', 1)], 1), ('campfire', [('oak_log', 3), ('stick', 3), ('coal', 1)], 1),
 ('beehive', [('oak_planks', 6), ('honeycomb', 3)], 1), ('crafting_table', [('oak_planks', 4)], 1),
 ('loom', [('string', 2), ('oak_planks', 2)], 1), ('cartography_table', [('paper', 2), ('oak_planks', 4)], 1),
 ('smithing_table', [('iron_ingot', 2), ('oak_planks', 4)], 1), ('stonecutter', [('iron_ingot', 1), ('stone', 3)], 1),
 ('grindstone', [('stick', 2), ('oak_planks', 2)], 1),
 ('scaffolding', [('bamboo', 6), ('string', 1)], 6), ('ladder', [('stick', 7)], 3),
 ('oak_sign', [('oak_planks', 6), ('stick', 1)], 3),
 ('item_frame', [('stick', 8), ('leather', 1)], 1), ('glow_item_frame', [('item_frame', 1), ('glow_ink_sac', 1)], 1),
 ('painting', [('stick', 8), ('white_wool', 1)], 1), ('armor_stand', [('stick', 6)], 1),
 ('flower_pot', [('brick', 3)], 1), ('candle', [('string', 1), ('honeycomb', 1)], 1),
 ('lantern', [('iron_nugget', 8), ('torch', 1)], 1),
 ('bookshelf', [('book', 3), ('oak_planks', 6)], 1), ('writable_book', [('book', 1), ('feather', 1), ('ink_sac', 1)], 1),
 ('lead', [('string', 4), ('slime_ball', 1)], 1), ('compass', [('iron_ingot', 4), ('redstone', 1)], 1),
 ('clock', [('gold_ingot', 4), ('redstone', 1)], 1), ('spyglass', [('copper_ingot', 2), ('amethyst_shard', 1)], 1),
 ('white_bed', [('white_wool', 3), ('oak_planks', 3)], 1), ('oak_boat', [('oak_planks', 5)], 1),
 ('minecart', [('iron_ingot', 5)], 1), ('rail', [('iron_ingot', 6), ('stick', 1)], 16),
 ('powered_rail', [('gold_ingot', 6), ('stick', 1), ('redstone', 1)], 6),
 ('detector_rail', [('iron_ingot', 6), ('redstone', 1)], 6),
 ('activator_rail', [('iron_ingot', 6), ('stick', 2), ('redstone_torch', 1)], 6),
 ('piston', [('oak_planks', 3), ('cobblestone', 4), ('iron_ingot', 1), ('redstone', 1)], 1),
 ('sticky_piston', [('piston', 1), ('slime_ball', 1)], 1),
 ('observer', [('cobblestone', 6), ('redstone', 2), ('quartz', 1)], 1),
 ('dispenser', [('cobblestone', 7), ('bow', 1), ('redstone', 1)], 1),
 ('dropper', [('cobblestone', 7), ('redstone', 1)], 1),
 ('repeater', [('redstone_torch', 2), ('redstone', 1), ('stone', 3)], 1),
 ('comparator', [('redstone_torch', 3), ('quartz', 3), ('stone', 3)], 1),
 ('redstone_torch', [('redstone', 1), ('stick', 1)], 1),
 ('daylight_detector', [('quartz', 3), ('glass', 3)], 1),
 ('note_block', [('oak_planks', 8), ('redstone', 1)], 1),
 ('redstone_lamp', [('glowstone', 1), ('redstone', 4)], 1),
 ('target', [('hay_block', 1), ('redstone', 4)], 1), ('tnt', [('gunpowder', 5), ('sand', 4)], 1),
 ('lever', [('cobblestone', 1), ('stick', 1)], 1), ('oak_pressure_plate', [('oak_planks', 2)], 1),
 ('heavy_weighted_pressure_plate', [('iron_ingot', 2)], 1),
 ('iron_sword', [('iron_ingot', 2), ('stick', 1)], 1), ('iron_pickaxe', [('iron_ingot', 3), ('stick', 2)], 1),
 ('iron_axe', [('iron_ingot', 3), ('stick', 2)], 1), ('iron_shovel', [('iron_ingot', 1), ('stick', 2)], 1),
 ('iron_hoe', [('iron_ingot', 2), ('stick', 2)], 1),
 ('stone_sword', [('cobblestone', 2), ('stick', 1)], 1), ('stone_pickaxe', [('cobblestone', 3), ('stick', 2)], 1),
 ('golden_sword', [('gold_ingot', 2), ('stick', 1)], 1), ('golden_pickaxe', [('gold_ingot', 3), ('stick', 2)], 1),
 ('diamond_sword', [('diamond', 2), ('stick', 1)], 1), ('diamond_pickaxe', [('diamond', 3), ('stick', 2)], 1),
 ('diamond_axe', [('diamond', 3), ('stick', 2)], 1), ('diamond_shovel', [('diamond', 1), ('stick', 2)], 1),
 ('diamond_hoe', [('diamond', 2), ('stick', 2)], 1),
 ('iron_helmet', [('iron_ingot', 5)], 1), ('iron_chestplate', [('iron_ingot', 8)], 1),
 ('iron_leggings', [('iron_ingot', 7)], 1), ('iron_boots', [('iron_ingot', 4)], 1),
 ('golden_helmet', [('gold_ingot', 5)], 1), ('golden_chestplate', [('gold_ingot', 8)], 1),
 ('golden_leggings', [('gold_ingot', 7)], 1), ('golden_boots', [('gold_ingot', 4)], 1),
 ('diamond_helmet', [('diamond', 5)], 1), ('diamond_chestplate', [('diamond', 8)], 1),
 ('diamond_leggings', [('diamond', 7)], 1), ('diamond_boots', [('diamond', 4)], 1),
 ('golden_carrot', [('carrot', 1), ('gold_nugget', 8)], 1), ('golden_apple', [('gold_ingot', 8), ('apple', 1)], 1),
 ('pumpkin_pie', [('pumpkin', 1), ('sugar', 1), ('egg', 1)], 1),
 ('cake', [('milk_bucket', 3), ('sugar', 2), ('wheat', 3), ('egg', 1)], 1),
 ('rabbit_stew', [('rabbit', 1), ('carrot', 1), ('potato', 1), ('brown_mushroom', 1), ('bowl', 1)], 1),
 ('mushroom_stew', [('brown_mushroom', 1), ('red_mushroom', 1), ('bowl', 1)], 1),
 ('beetroot_soup', [('beetroot', 6), ('bowl', 1)], 1), ('cookie', [('wheat', 2), ('cocoa_beans', 1)], 8),
 ('jack_o_lantern', [('carved_pumpkin', 1), ('torch', 1)], 1), ('honey_bottle', [('honey_block', 1)], 4),
 ('wind_charge', [('breeze_rod', 1)], 4), ('fire_charge', [('coal', 1), ('blaze_powder', 1), ('gunpowder', 1)], 3),
 ('firework_rocket', [('paper', 1), ('gunpowder', 1)], 3),
 ('iron_block', [('iron_ingot', 9)], 1), ('iron_ingot', [('iron_block', 1)], 9),
 ('iron_ingot', [('iron_nugget', 9)], 1), ('gold_ingot', [('gold_nugget', 9)], 1),
 ('gold_block', [('gold_ingot', 9)], 1), ('diamond_block', [('diamond', 9)], 1),
 ('coal_block', [('coal', 9)], 1), ('packed_ice', [('ice', 9)], 1), ('blue_ice', [('packed_ice', 9)], 1),
 ('bricks', [('brick', 4)], 1), ('glass', [('sand', 1), ('__FUEL__', 1)], 1),
 ('nether_bricks', [('nether_brick', 4)], 1), ('clay', [('clay_ball', 4)], 1),
 ('stone_bricks', [('stone', 4)], 4), ('glowstone', [('glowstone_dust', 4)], 1),
 ('stone', [('cobblestone', 1), ('__FUEL__', 1)], 1),
 ('brick', [('clay_ball', 1), ('__FUEL__', 1)], 1),
 ('nether_brick', [('stone', 1), ('__FUEL__', 1)], 1),
 ('shulker_box', [('shulker_shell', 2), ('chest', 1)], 1), ('white_wool', [('string', 4)], 1),
 ('netherite_helmet', [('diamond_helmet', 1), ('netherite_ingot', 1)], 1),
 ('netherite_chestplate', [('diamond_chestplate', 1), ('netherite_ingot', 1)], 1),
 ('netherite_leggings', [('diamond_leggings', 1), ('netherite_ingot', 1)], 1),
 ('netherite_boots', [('diamond_boots', 1), ('netherite_ingot', 1)], 1),
 ('netherite_sword', [('diamond_sword', 1), ('netherite_ingot', 1)], 1),
 ('netherite_pickaxe', [('diamond_pickaxe', 1), ('netherite_ingot', 1)], 1),
 ('raw_iron_block', [('raw_iron', 9)], 1), ('raw_gold_block', [('raw_gold', 9)], 1),
 ('raw_copper_block', [('raw_copper', 9)], 1),
]

# I2: обратимые сжатия
PAIRS = [('coal', 'coal_block'), ('iron_ingot', 'iron_block'), ('gold_ingot', 'gold_block'),
         ('copper_ingot', 'copper_block'), ('redstone', 'redstone_block'),
         ('lapis_lazuli', 'lapis_block'), ('emerald', 'emerald_block'), ('diamond', 'diamond_block'),
         ('netherite_ingot', 'netherite_block'), ('wheat', 'hay_block'), ('slime_ball', 'slime_block'),
         ('dried_kelp', 'dried_kelp_block'), ('ice', 'packed_ice'), ('packed_ice', 'blue_ice'),
         ('raw_iron', 'raw_iron_block'), ('raw_gold', 'raw_gold_block'), ('raw_copper', 'raw_copper_block')]

viol = []
checked = 0
for name, mats, out in R:
    if name not in T:
        continue
    total, skip = 0.0, False
    for m, n in mats:
        if m == '__FUEL__':
            total += FUEL
            continue
        if m not in T:
            skip = True
            break
        total += T[m] * n
    if skip or total == 0:
        continue
    checked += 1
    got = sell(T[name]) * out
    if got >= total:
        viol.append(f'I1 {name}: продажа {got} >= материалы {total}')

for u, bl in PAIRS:
    u9, got = T[u] * 9, T[bl]
    if abs(got - u9) > 3:
        viol.append(f'I2 {bl}: {got} != 9*{u} ({u9})')

anchors = {'elytra': 8000, 'nether_star': 2000, 'beacon': 2500}
for k, want in anchors.items():
    if T.get(k) != want:
        viol.append(f'I3 якорь {k}: {T.get(k)} != {want}')

print(f'проверено: {checked} крафтов, {len(PAIRS)} сжатий, {len(anchors)} якоря таблицы ({len(T)} позиций)')
if viol:
    print('НАРУШЕНИЯ:')
    for v in viol:
        print(' -', v)
    sys.exit(1)
print('арбитражных петель не найдено ✓')
