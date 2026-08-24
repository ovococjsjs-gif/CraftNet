#!/usr/bin/env bash
# Публикует выжимку лога сборки (заголовок, ключевые строки ошибок, хвост)
# комментарием к коммиту. Требует GH_TOKEN (передаётся env) и наличие
# build-output.log. Не падает сама, чтобы не зашумлять статус джобы.
set -u

CI_JOB_STATUS="${CI_JOB_STATUS:-unknown}"

{
  echo "### CraftNet CI: **${CI_JOB_STATUS}**"
  echo
  echo "\`${GITHUB_SHA}\`"
  echo
  echo '```'
  echo "== LOG HEAD =="
  head -n 25 build-output.log || true
  echo
  echo "== KEY LINES =="
  grep -n -B3 -A8 -E "FAILURE:|What went wrong|Caused by:|Could not|error:|> Task .*FAILED|Compilation failed|Execution failed|requires|incompatible" build-output.log | head -n 220 || true
  echo
  echo "== LOG TAIL =="
  tail -n 20 build-output.log || true
  echo '```'
} > report.md

python3 - <<'PYEOF'
data = open('report.md', encoding='utf-8').read()
if len(data) > 60000:
    data = data[:60000] + "\n```\n(truncated)\n"
open('report.md', 'w', encoding='utf-8').write(data)
PYEOF

gh api "repos/${GITHUB_REPOSITORY}/commits/${GITHUB_SHA}/comments" -F body=@report.md || true
exit 0
