#!/usr/bin/env python3
# Аудит lang-файлов CraftNet: парность ru/en и покрытие ключей из кода.
# Проверяет:
#   1) ru_ru.json и en_us.json содержат одинаковый набор ключей;
#   2) все ключи Text.translatable("craftnet...") из java есть в lang;
#   3) число %s-плейсхолдеров значения совпадает между ru и en
#      (иначе String.format собьёт аргументы);
#   4) нет пустых значений.
# Запуск: python3 tools/audit_lang.py  (из корня репо). exit 0 — чисто, 1 — есть находки.

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LANG = ROOT / "src/main/resources/assets/craftnet/lang"
SRC = ROOT / "src/main/java"

fails = []


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def main():
    ru = load(LANG / "ru_ru.json")
    en = load(LANG / "en_us.json")

    # 1) парность наборов ключей
    only_ru = sorted(set(ru) - set(en))
    only_en = sorted(set(en) - set(ru))
    for k in only_ru:
        fails.append(f"ключ есть только в ru_ru.json: {k}")
    for k in only_en:
        fails.append(f"ключ есть только в en_us.json: {k}")
    print(f"ключей: ru={len(ru)} en={len(en)}")

    # 2) покрытие ключей из кода
    used = set()
    for java in SRC.rglob("*.java"):
        text = java.read_text(encoding="utf-8")
        used.update(re.findall(r'Text\.translatable\("(craftnet\.[a-z0-9_.]+)"', text))
    missing_ru = sorted(used - set(ru))
    missing_en = sorted(used - set(en))
    for k in missing_ru:
        fails.append(f"код шлёт translatable-ключ, которого нет в ru_ru.json: {k}")
    for k in missing_en:
        fails.append(f"код шлёт translatable-ключ, которого нет в en_us.json: {k}")
    print(f"translatable-ключей в коде: {len(used)}; покрыто: {len(used & set(ru))}")

    # 3) %s-плейсхолдеры ru/en совпадают
    for k in sorted(set(ru) & set(en)):
        ps_ru = len(re.findall(r"%[\d$]*s", ru[k]))
        ps_en = len(re.findall(r"%[\d$]*s", en[k]))
        if ps_ru != ps_en:
            fails.append(f"разное число %s у '{k}': ru={ps_ru} en={ps_en}")

    # 4) пустые значения
    for lang, d in (("ru", ru), ("en", en)):
        for k, v in d.items():
            if not str(v).strip():
                fails.append(f"пустое значение: {lang}:{k}")

    if fails:
        print("\nНАХОДКИ:")
        for f in fails:
            print(" -", f)
        return 1
    print("lang-аудит чист")
    return 0


if __name__ == "__main__":
    sys.exit(main())
