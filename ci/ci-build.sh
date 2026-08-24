#!/usr/bin/env bash
# Сборка CraftNet в CI. Вызывается из .github/workflows/build.yml —
# вынесена сюда, чтобы пайплайн можно было править обычным пушем кода,
# без изменений файла workflow (на которые у ботов нет прав GitHub).
set -o pipefail
gradle build --no-daemon --stacktrace 2>&1 | tee build-output.log
