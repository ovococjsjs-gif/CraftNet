#!/usr/bin/env python3
"""Общие парсеры исходников CraftNet для аудит-скриптов (tools/).

Скрипты-аудиты читают ПРАВДУ из исходников (java), а не дублируют цифры —
иначе проверка разъедется с кодом при ближайшем ребалансе.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ECON = ROOT / 'src/main/java/net/craftnet/econ'
JOBS = ROOT / 'src/main/java/net/craftnet/jobs/JobManager.java'
CONFIG = ROOT / 'src/main/java/net/craftnet/config/CraftNetConfig.java'


def price_table():
    """Таблица buy-цен из PriceManager.java → {minecraft_id: CR}."""
    src = (ECON / 'PriceManager.java').read_text(encoding='utf-8')
    return {k.lower(): int(v) for k, v in
            re.findall(r'put\(Items\.([A-Z_]+),\s*(\d+)\)', src)}


def sell_price(buy: int) -> int:
    """Зеркало PriceManager.sellPrice при дефолтных коэффициентах (floor)."""
    r = 0.55 if buy <= 9 else (0.80 if buy >= 500 else 0.68)
    return int(buy * r)


def config_defaults():
    """Дефолты из инициализаторов полей CraftNetConfig.java → {имя: число}."""
    src = CONFIG.read_text(encoding='utf-8')
    out = {}
    for name, val in re.findall(r'public (?:int|long|double) (\w+) = ([\d_.]+);', src):
        v = float(val.replace('_', ''))
        out[name] = int(v) if v == int(v) else v
    return out


def stocks_companies():
    """Компании биржи из StocksManager.java → [{id, lo, hi, vol, revert, div}]."""
    src = (ECON / 'StocksManager.java').read_text(encoding='utf-8')
    rows = re.findall(
        r'\w+\("(\w+)",\s*"[^"]*",\s*([\d.]+),\s*([\d.]+),\s*([\d.]+),\s*([\d.]+),\s*([\d.]+)\)',
        src)
    return [dict(id=a, lo=float(b), hi=float(c), vol=float(d), revert=float(e), div=float(f))
            for a, b, c, d, e, f in rows]


def job_recipes(array_name: str):
    """Рецепты String[][] из JobManager.java → список списков токенов
    (первый токен — цель вида minecraft:bread:1, остальные — материалы id:count)."""
    src = JOBS.read_text(encoding='utf-8')
    m = re.search(array_name + r'\s*=\s*\{(.*?)\n\t\};', src, re.S)
    if not m:
        raise ValueError(f'массив {array_name} не найден в JobManager.java')
    rows = []
    for row in re.findall(r'\{([^}]*)\}', m.group(1)):
        toks = [t.strip().strip('"') for t in row.split(',') if t.strip()]
        if toks:
            rows.append(toks)
    return rows


def craft_offer_call(array_name: str):
    """Аргументы buildCraftOffer(offer, rng, <ARRAY>, cfg.A, cfg.B, cfg.C, cfg.D, k1, k2, c1, c2)
    → (cfg-имена ×4, minKinds, maxKinds, minCount, maxCount)."""
    src = JOBS.read_text(encoding='utf-8')
    m = re.search(
        r'buildCraftOffer\(offer, rng, ' + array_name + r',\s*cfg\.(\w+), cfg\.(\w+),\s*'
        r'cfg\.(\w+), cfg\.(\w+), (\d+), (\d+), (\d+), (\d+)\)\)', src)
    if not m:
        raise ValueError(f'вызов buildCraftOffer для {array_name} не найден')
    return (m.group(1), m.group(2), m.group(3), m.group(4),
            int(m.group(5)), int(m.group(6)), int(m.group(7)), int(m.group(8)))
