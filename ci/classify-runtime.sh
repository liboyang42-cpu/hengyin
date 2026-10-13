#!/usr/bin/env bash
# 「这次改动会不会影响生产运行时(= 需不需要部署)」的**唯一真源**。
#
# ★ 生产有**两个**独立制品,各有各的部署链路,各有一条判据,互不重叠:
#     · 后端 jar        ← RUNTIME_PATH_RE(runtime_changed_between)→ deploy.yml
#     · 后台前端静态站   ← UI_PATH_RE     (ui_changed_between)     → deploy-ui.yml
#   drift-audit.yml 两条轴都要对账 —— 一个制品漏一条轴,兵底就在那个维度上失明。
#
# 为什么要有这个文件:同一条判据有多个消费者 ——
#   · .github/workflows/deploy.yml      的 classify:决定这次 push 要不要部署 jar;
#   · .github/workflows/deploy-ui.yml   的 classify:决定这次 push 要不要部署后台前端;
#   · .github/workflows/drift-audit.yml 的对账:决定生产落后 master 算不算漂移。
# 同一个制品的「部署触发」与「漂移判定」必须给出**同一个答案**。各写一份正则的话:
#   · 只改 deploy.yml ⇒ 对账在真漂移时报绿(漏报,兵底失效);
#   · 只改 drift-audit ⇒ 对账每小时报红、反复触发 deploy,而 deploy 每次都判不用部署
#     ⇒ 无限告警噪音,红成噪音之后就没人看了,兵底同样失效。
# 所以判据只能有一处。
#
# 用法:
#   source ci/classify-runtime.sh
#   if runtime_changed_between "$base" "$head"; then ... fi
#   if ui_changed_between "$base" "$head"; then ... fi
#
# ⚠️ 改这里等于同时改部署触发与漂移判定,想清楚再动。

set -uo pipefail

# 会被打进 jar / 影响生产运行时的路径。
# chengyinhub-admin/sql/** 例外:它是要在库上跑的 SQL,不在 src 下、不进 jar。
# 只改它就部署 = 构建出与上一版逐字节等价的 jar,再无谓重启一次生产;
# 迁移的落库是合并前的人工步骤,不靠流水线。
RUNTIME_PATH_RE='^(pom\.xml$|chengyinhub-(admin|common|framework|generator|quartz|system)/)'
SQL_ONLY_PATH_RE='^chengyinhub-admin/sql/'

# 后台前端(chengyinhub-ui)的静态产物,走 deploy-ui.yml 那条独立链路。
# ★ 故意**不并进** RUNTIME_PATH_RE:并进去 = 改前端也重启后端,构建出与上一版逐字节等价的
#   jar 再白停一次生产;两个制品、两条链路,判据也就该是两条。
# ⚠️ 2026-07-17 之前这条判据只活在 deploy-ui.yml 的一行内联 grep 里,drift-audit 不认识它:
#   纯前端改动落后生产时,对账走的是 RUNTIME_PATH_RE,输出「✅ 差异全是非运行时,符合预期」
#   ⇒ deploy-ui 连红 4 次(生产机没装 rsync),而对账一路报绿、零告警。
#   这就是「判据只能有一处」的代价示范 —— 漏掉一个消费者,兵底在那一维上直接瞎掉。
UI_PATH_RE='^chengyinhub-ui/'

# runtime_changed_between <base> <head>
#   0 = 含运行时改动(该部署) / 1 = 不含(②小程序、④CI、⑤文档、纯 sql)
runtime_changed_between() {
  local base=$1 head=$2 changed runtime
  changed=$(git diff --name-only "$base" "$head")
  runtime=$(printf '%s\n' "$changed" | grep -Ev "$SQL_ONLY_PATH_RE" || true)
  grep -Eq "$RUNTIME_PATH_RE" <<< "$runtime"
}

# runtime_files_between <base> <head> —— 只列出命中的运行时文件,给告警正文用。
runtime_files_between() {
  local base=$1 head=$2
  git diff --name-only "$base" "$head" \
    | grep -Ev "$SQL_ONLY_PATH_RE" \
    | grep -E "$RUNTIME_PATH_RE" || true
}

# ui_changed_between <base> <head>
#   0 = 含后台前端改动(该部署 chengyinhub-ui) / 1 = 不含
ui_changed_between() {
  local base=$1 head=$2 changed
  changed=$(git diff --name-only "$base" "$head")
  grep -Eq "$UI_PATH_RE" <<< "$changed"
}

# ui_files_between <base> <head> —— 只列出命中的前端文件,给告警正文用。
ui_files_between() {
  local base=$1 head=$2
  git diff --name-only "$base" "$head" | grep -E "$UI_PATH_RE" || true
}
