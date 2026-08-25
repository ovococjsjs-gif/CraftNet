#!/usr/bin/env python3
"""Симуляция оплат работ CraftNet (модель: ECONOMY.md, код: JobManager.java).

Перебирает все возможные составы офферов (равномерно, как делает rng)
и отвечает на вопросы баланса:
  1. оплата крафт-заказов следует за ценностью материалов (медианный заказ
     платит середину окна лестницы, а не упирается в кламп);
  2. окна лестницы строго упорядочены (курьер < повар < грузчик < завод);
  3. докладывает потолки дохода при jobWindowCooldown=1 (1 смена типа / окно).

Все цифры берутся из исходников: цены — PriceManager.java, множители и
окна — дефолты CraftNetConfig.java, рецепты и параметры вызова — JobManager.java.

Запуск:  python3 tools/simulate_jobs.py
Код возврата 0 = чисто, 1 = нарушение лестницы/центрирования.
"""
import itertools
import sys

sys.path.insert(0, str(__file__.rsplit('/', 1)[0]))
from priceparse import config_defaults, craft_offer_call, job_recipes, price_table

T = price_table()
cfg = config_defaults()
fail = []


def check(ok, msg):
    print(('  ok  ' if ok else ' FAIL ') + msg)
    if not ok:
        fail.append(msg)


def jround(x):
    return int(x + 0.5)  # Java Math.round


def mats_of_recipe(row):
    """Стоимость материалов одного крафта: row = [цель[:per], id:cnt, ...]."""
    total = 0
    unknown = []
    for tok in row[1:]:
        rid, cnt = tok.rsplit(':', 1)
        rid = rid.split(':', 1)[1] if ':' in rid else rid  # снять namespace (minecraft:)
        if rid not in T:
            unknown.append(rid)
        else:
            total += T[rid] * int(cnt)
    return total, unknown


def unit_goal(row):
    """(id цели, сколько штук даёт один крафт)."""
    ref = row[0]
    parts = ref.split(':')
    if len(parts) == 3:
        return ':'.join(parts[:2]), int(parts[2])
    return ref, 1


def enumerate_orders(array_name):
    """Все равновероятные составы заказа → список matsValue."""
    pct_k, bonus_k, lo_k, hi_k, kmin, kmax, cmin, cmax = craft_offer_call(array_name)
    recipes = job_recipes(array_name)
    mats_per, goals, unknown_all = [], [], []
    for row in recipes:
        m, unk = mats_of_recipe(row)
        unknown_all += unk
        mats_per.append(m)
        goals.append(unit_goal(row))
    if unknown_all:
        print(f'  !! материалы вне прайс-таблицы (считаются за 0): {sorted(set(unknown_all))}')
    out = []
    for kinds in range(kmin, kmax + 1):
        for subset in itertools.combinations(range(len(recipes)), kinds):
            for units in itertools.product(range(cmin, cmax + 1), repeat=kinds):
                mats = sum(mats_per[i] * u for i, u in zip(subset, units))
                out.append(mats)
    return out, (cfg[pct_k], cfg[bonus_k], cfg[lo_k], cfg[hi_k]), list(zip(goals, mats_per))


def stats(name, pays):
    pays = sorted(pays)
    n = len(pays)
    med = pays[n // 2]
    mean = sum(pays) / n
    return med, mean, pays[0], pays[-1]


# ------------------------------ крафт-заказы ------------------------------
portfolio_medians = {}

for label, arr, cls in (('цеховой заказ (завод)', 'FACTORY_RECIPES', 'factory_order'),
                        ('повар (кафе)', 'CAFE_RECIPES', 'cook')):
    mats_list, (pct, bonus, lo, hi), goals = enumerate_orders(arr)
    pays = [max(lo, min(hi, jround(m * pct / 100) + bonus)) for m in mats_list]
    raw = [jround(m * pct / 100) + bonus for m in mats_list]
    med, mean, pmin, pmax = stats(label, pays)
    floor_hits = sum(1 for p, r in zip(pays, raw) if r < lo) / len(pays)
    cap_hits = sum(1 for p, r in zip(pays, raw) if r > hi) / len(pays)
    mid = (lo + hi) / 2
    portfolio_medians[cls] = med
    print(f'\n== {label}: заказов {len(pays)}, формула mats×{pct}% + {bonus}, окно [{lo}..{hi}]')
    print('   цена материалов за крафт: ' + ', '.join(
        f"{g[0].split(':')[1]}={m}" for g, m in goals))
    print(f'   pay: min {pmin} / медиана {med} / среднее {mean:.0f} / max {pmax}')
    print(f'   кламп: пол {floor_hits:.0%}, потолок {cap_hits:.0%}')
    check(floor_hits <= 0.25, f'{label}: пол срабатывает на {floor_hits:.0%} заказов (≤25%)')
    check(cap_hits <= 0.25, f'{label}: потолок срабатывает на {cap_hits:.0%} заказов (≤25%)')
    check(abs(med - mid) <= (hi - lo) * 0.5,
          f'{label}: медиана {med} в пределах ±50% полуширины от центра окна {mid:.0f}')

# ------------------------------ завод (мини-игра) ------------------------------
b, per, jit = cfg['jobFactoryBase'], cfg['jobFactoryPerPart'], cfg['jobFactoryJitter']
p0, p1 = cfg['jobFactoryMinParts'], cfg['jobFactoryMaxParts']
pays_f = [b + parts * per + j for parts in range(p0, p1 + 1) for j in range(max(1, jit))]
med, mean, pmin, pmax = stats('factory', pays_f)
portfolio_medians['factory'] = med
print(f'\n== завод (мини-игра): {b} + {per}×детали[{p0}..{p1}] + джиттер[0..{jit - 1}]')
print(f'   pay: min {pmin} / медиана {med} / max {pmax}')

# ------------------------------ курьер ------------------------------
cb, cpp = cfg['jobCourierBase'], cfg['jobCourierPerPortion']
q0, q1 = cfg['jobCourierMinPortions'], cfg['jobCourierMaxPortions']
pay_c_min, pay_c_max = cb + q0 * cpp, cb + q1 * cpp
portfolio_medians['courier'] = cb + ((q0 + q1) // 2) * cpp
print(f'\n== курьер: {cb} + {cpp}×пакеты[{q0}..{q1}] → pay [{pay_c_min}..{pay_c_max}]')

# ------------------------------ грузчик ------------------------------
lpb, llo, lhi = cfg['jobLoaderPerBlock'], cfg['jobLoaderMinPay'], cfg['jobLoaderMaxPay']
sat, floor_d = lhi // lpb, llo // lpb
portfolio_medians['loader'] = (llo + lhi) // 2
print(f'\n== грузчик: {lpb} CR/блок, окно [{llo}..{lhi}]')
print(f'   насыщение окна: дистанция ≤{floor_d} б → пол, ≥{sat} б → потолок')

# ------------------------------ лестница непересекаема ------------------------------
print('\n— лестница —')
w = {
    'курьер': (pay_c_min, pay_c_max),
    'повар': (cfg['jobOrderCookMinPay'], cfg['jobOrderCookMaxPay']),
    'грузчик': (llo, lhi),
    'цеховой заказ': (cfg['jobOrderFactoryMinPay'], cfg['jobOrderFactoryMaxPay']),
    'завод мини-игра': (pmin, pmax),
}
for a, b_ in (('курьер', 'повар'), ('повар', 'грузчик'), ('грузчик', 'цеховой заказ'),
              ('грузчик', 'завод мини-игра')):
    check(w[a][1] < w[b_][0], f'{a} {w[a]} строго ниже {b_} {w[b_]}')

# ------------------------------ потолки дохода ------------------------------
print('\n— потолки дохода при jobWindowCooldown =', cfg['jobWindowCooldown'], '—')
k = cfg['jobWindowCooldown']
port = sum(portfolio_medians.values())
win_min = 10 * k
print(f'   портфель одного окна (5 медианных смен): ~{port} CR за {win_min} мин')
print(f'   теоретический максимум 6 окон: ~{port * 6 // k} CR/ч (аллотмент-лимит)')
phys = {'factory': 3.0, 'factory_order': 4.0, 'cook': 4.0, 'loader': 4.0, 'courier': 2.5}
round_min = sum(phys.values())
print(f'   физический проход всех 5 смен ≈ {round_min:.0f} мин →'
      f' практический потолок ~{int(port * 60 / round_min)} CR/ч')
print(f'   только завод (мини-игра): {portfolio_medians["factory"]} CR / {win_min} мин'
      f' = {portfolio_medians["factory"] * 60 // win_min} CR/ч')

print('\n' + ('СИМУЛЯЦИЯ ЧИСТА' if not fail else f'НАРУШЕНИЙ: {len(fail)}'))
sys.exit(0 if not fail else 1)
