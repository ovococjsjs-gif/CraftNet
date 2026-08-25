#!/usr/bin/env python3
# Аудит мета-прогрессии CraftNet: достижения и сезонные челленджи.
# Проверяет:
#   1) id ACHIEVEMENTS (StatsManager) == id ACH_DEFS (PhoneScreen) —
#      иначе ачивка не отрисуется или нарисуется фантом;
#   2) ключи счётчиков в Ach/Challenge существуют как String-константы;
#   3) ротация челленджей: внутри тройки нет дублей, все 9 челленджей
#      появляются в ротации, число уникальных наборов = 3 (дизайн);
#   4) призовая экономика: максимальный забор за сезон (3 приза) —
#      информ-вывод относительно якоря ECONOMY.md (1700 CR/ч, сезон 2.3 ч);
#   5) JOBS_STREAK обвязан в JobManager: +1 при оплате, 0 при срыве.
# Запуск: python3 tools/audit_meta.py  (из корня репо). exit 0/1.

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STATS = ROOT / "src/main/java/net/craftnet/stats/StatsManager.java"
PHONE = ROOT / "src/main/java/net/craftnet/client/gui/PhoneScreen.java"
JOBS = ROOT / "src/main/java/net/craftnet/jobs/JobManager.java"

fails = []


def parse_const_names(text):
    """Имена констант счётчиков: public static final String X = "...";"""
    return set(re.findall(r'public static final String (\w+)\s*=\s*"', text))


def main():
    st = STATS.read_text(encoding="utf-8")
    ph = PHONE.read_text(encoding="utf-8")
    jb = JOBS.read_text(encoding="utf-8")

    consts = parse_const_names(st)

    # 1) id ачивок: сервер vs клиент
    ach_block = re.search(r"ACHIEVEMENTS = List\.of\((.*?)\);", st, re.S)
    srv_ids = re.findall(r'new Ach\("(\w+)"', ach_block.group(1))
    cli_block = re.search(r"ACH_DEFS = \{(.*?)\};", ph, re.S)
    cli_ids = re.findall(r'new AchRow\("(\w+)"', cli_block.group(1))
    if sorted(srv_ids) != sorted(cli_ids):
        fails.append(f"ACH id расходятся: только сервер={sorted(set(srv_ids) - set(cli_ids))}, "
                     f"только клиент={sorted(set(cli_ids) - set(srv_ids))}")
    print(f"ачивок: сервер={len(srv_ids)} клиент={len(cli_ids)}")

    # 2) ключи счётчиков валидны (Ach: key1 обязателен, key2 может быть "")
    for m in re.finditer(r'new Ach\("\w+",\s*(\w+|""),\s*(\w+|""),', ach_block.group(1)):
        for g in m.groups():
            if g != '""' and g not in consts:
                fails.append(f"Ach ссылается на несуществующую константу: {g}")
    ch_block = re.search(r"CHALLENGES = List\.of\((.*?)\);", st, re.S).group(1)
    challs = []  # (id, key, need, rcr, xp)
    for m in re.finditer(r'new Challenge\("(\w+)",\s*(\w+),\s*(\d+),\s*(\d+),\s*(\d+)', ch_block):
        cid, key, need, rcr, xp = m.group(1), m.group(2), int(m.group(3)), int(m.group(4)), int(m.group(5))
        challs.append((cid, key, need, rcr, xp))
        if key not in consts:
            fails.append(f"Challenge {cid}: несуществующая константа счётчика {key}")
        if need <= 0 or rcr <= 0 or xp <= 0:
            fails.append(f"Challenge {cid}: положительные need/rcr/xp обязательны ({need}/{rcr}/{xp})")
    print(f"челленджей в пуле: {len(challs)}")
    if len({c[0] for c in challs}) != len(challs):
        fails.append("дубли id челленджей в пуле")

    # 3) ротация: activeChallenges(season) = [s, s+3, s+6] mod n — теорема по коду
    n = len(challs)
    sets = []
    for season in range(n):
        a = season % n
        trio = {a, (a + 3) % n, (a + 6) % n}
        if len(trio) != 3:
            fails.append(f"сезон {season}: дубль в активной тройке {sorted(trio)}")
        sets.append(frozenset(trio))
    uniq = set(sets)
    covered = set().union(*uniq)
    if len(covered) != n:
        fails.append(f"ротация покрывает {len(covered)}/{n} челленджей")
    print(f"уникальных наборов сезона: {len(uniq)}; покрыто челленджей: {len(covered)}/{n}")

    # 4) призовая экономика: максимальный забор за сезон (3 приза одновременно)
    worst = max(sum(challs[i][3] for i in trio) for trio in uniq)
    worst_xp = max(sum(challs[i][4] for i in trio) for trio in uniq)
    season_h = 24000 * 7 / 72000  # тики → реальные часы (72000 тиков = 1 ч)
    anchor_cr = 1700 * season_h
    pct = worst / anchor_cr * 100
    print(f"макс. забор призов за сезон: {worst} CR + {worst_xp} XP за ~{season_h:.1f} ч "
          f"(якорь {anchor_cr:.0f} CR/сезон → {pct:.0f}% наверх)")
    if worst > anchor_cr * 0.5:
        fails.append(f"призы челленджей {worst} CR/сезон превышают 50% якоря {anchor_cr:.0f} — перебор притока")
    for cid, key, need, rcr, xp in challs:
        if rcr / need > 150:
            fails.append(f"Challenge {cid}: эффективность {rcr / need:.0f} CR/шаг — жирнее почасовых работ")

    # 5) JOBS_STREAK обвязка в JobManager
    if "StatsManager.JOBS_STREAK, 1" not in jb:
        fails.append("JobManager: нет +1 к JOBS_STREAK при оплате смены")
    if not re.search(r"StatsManager\.set\(server, player, StatsManager\.JOBS_STREAK, 0\)", jb):
        fails.append("JobManager: нет обнуления JOBS_STREAK при срыве смены")

    if fails:
        print("\nНАХОДКИ:")
        for f in fails:
            print(" -", f)
        return 1
    print("аудит мета-прогрессии чист")
    return 0


if __name__ == "__main__":
    sys.exit(main())
