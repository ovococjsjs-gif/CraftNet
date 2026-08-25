#!/usr/bin/env python3
"""Аудит денежных потоков казино и биржи CraftNet (модель: ECONOMY.md).

Казино (инвариант I3, «нет петли денег»):
  шанс = stake(sell) × rtp / target(buy), кап 95%.
  Возврат перепродажей приза = шанс × sell(target) / stake(sell)
                             ≤ rtp × max(sell-ступень) = 0.90 × 0.80 = 0.72 < 1 ✓
  Скрипт перебирает ВСЮ таблицу цен как цели и проверяет границу на каждой,
  плюс печатает EV-таблицу типичных сценариев.

Биржа (роль «добавки, не замены работы»):
  пассив портфеля = exposure × divYield × 3 (3 игровых суток/реальный час),
  усечённый дневным капом на компанию. Проверка: лучший портфель даёт
  ≤ STOCK_MAX_SHARE от якоря активного дохода (~1700 CR/ч), иначе
  «купил акции и спишь» обгоняет честную работу.

Запуск:  python3 tools/audit_econ_flows.py
Код возврата 0 = чисто, 1 = нарушение (печатает разбор).
"""
import sys

sys.path.insert(0, str(__file__.rsplit('/', 1)[0]))
from priceparse import config_defaults, price_table, sell_price, stocks_companies

JOB_ANCHOR_CR_H = 1700.0   # якорь активного дохода (ECONOMY.md)
STOCK_MAX_SHARE = 0.60     # пассив не должен превышать 60% активного якоря
GAME_DAYS_PER_HOUR = 3.0   # игровые сутки = 20 минут

fail = []


def check(ok, msg):
    print(('  ok  ' if ok else ' FAIL ') + msg)
    if not ok:
        fail.append(msg)


# ------------------------------ казино ------------------------------
T = price_table()
cfg = config_defaults()
rtp = cfg['casinoRtpPct'] / 100.0
cap_bp = cfg['casinoMaxChanceBp'] / 10000.0

print(f'— казино: RTP={rtp:.0%}, кап шанса={cap_bp:.0%} —')

# граница возврата перепродажей для каждой цели в таблице
worst = []
for item, buy in T.items():
    if buy <= 0:
        continue
    sell = sell_price(buy)
    if sell <= 0:
        continue
    ratio = rtp * sell / buy  # доля возврата при перепродаже приза (при шансе < капа)
    worst.append((ratio, item, buy, sell))
worst.sort(reverse=True)
print('  худшие цели по «возврату перепродажей» (доля затрат на ставку):')
for ratio, item, buy, sell in worst[:8]:
    print(f'    {ratio:6.1%}  {item:<22} buy={buy:<5} sell={sell}')
check(worst[0][0] < 1.0, f'макс. возврат перепродажей {worst[0][0]:.1%} < 100% (петли нет)')

# EV приза в buy-ценностях против ценности ставки (всегда убыточно игроку на дистанции)
ev_rows = []
for target in ('elytra', 'beacon', 'netherite_ingot', 'diamond', 'bread'):
    b = T.get(target)
    if not b:
        continue
    ev_rows.append((target, b, rtp))  # E[приз buy] = rtp × stake_sell  при любой ставке
print('  EV-таблица (шанс ниже капа): приз(buy) = %.0f%% от ставки(sell),' % (rtp * 100))
print('  т.е. дистанционно ставить в казино = переплачивать %.0f%% к магазину' % ((1 / rtp - 1) * 100))
for target, b, _ in ev_rows:
    s = sell_price(b)
    print(f'    цель {target:<18} buy={b:<6} sell={s:<6} возврат-sell при выигрыше {s / b:.0%}')

# кап шанса не открывает петлю: при шансе=кап ставка(sell) = кап×buy/rtp → возврат = кап×sell/ставка
ratio_cap = rtp * max(sell_price(b) / b for b in T.values() if b > 500)
check(ratio_cap <= cap_bp, f'возврат при кап-шансе {ratio_cap:.1%} ≤ кап {cap_bp:.0%} '
      '(иначе дешёвый приз при почти-гарантии окупался бы)')

# ------------------------------ биржа ------------------------------
print('\n— биржа: пассив против активного якоря —')
exposure = cfg['stocksMaxExposure']
cap_day = cfg['stocksDividendCapPerCompany']
companies = stocks_companies()
best_hourly = 0.0
for c in companies:
    daily_full = exposure * c['div']                    # дивиденды при полном вложении
    daily = min(daily_full, cap_day)                    # усечение дневным капом
    hourly = daily * GAME_DAYS_PER_HOUR
    optimal = cap_day / c['div'] if c['div'] > 0 else 0  # пакет, выше которого дивы горят
    best_hourly = max(best_hourly, hourly)
    print(f"  {c['id']:<5} div={c['div']:.3%}/сутки  портфель {exposure} CR → {daily:6.0f} CR/сутки"
          f" → {hourly:5.0f} CR/ч  (пакет выше {optimal:7.0f} CR дохнет о кап);"
          f" коридор {c['lo']:.0f}..{c['hi']:.0f}")
    check(c['lo'] > 0 and c['lo'] <= c['hi'], f"{c['id']}: коридор цен здравый")
check(best_hourly <= JOB_ANCHOR_CR_H * STOCK_MAX_SHARE,
      f'лучший пассив {best_hourly:.0f} CR/ч ≤ {STOCK_MAX_SHARE:.0%} якоря работ '
      f'({JOB_ANCHOR_CR_H * STOCK_MAX_SHARE:.0f} CR/ч)')
# суммарно все компании в одном портфеле лимитом exposure — пассив не компаундируется выше лимита
total_full = exposure * max(c['div'] for c in companies) * GAME_DAYS_PER_HOUR
print(f'  весь лимит в лучшую компанию: {total_full:.0f} CR/ч = {total_full / JOB_ANCHOR_CR_H:.0%} якоря')

print('\n' + ('АУДИТ ПРОЙДЕН' if not fail else f'НАРУШЕНИЙ: {len(fail)}'))
sys.exit(0 if not fail else 1)
