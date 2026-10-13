#!/usr/bin/env bash
# 云效流水线运行的「真跑过」回读断言 —— 补 GitHub 侧 timeout-minutes 那条契约的等价物。
#
# ★ 为什么需要它(2026-08-27 实测):
#   GitHub 侧 ci/funds_it_gate.py 断言「每个 job 必须有 timeout-minutes」,注释写明防的是
#   「runner 离线时 job 一直排队,既不绿也不红,等于没有门禁」。
#   云效上这个失效模式**更严重**:
#     · 云效 flow 里根本没有 timeout 字段(实测)
#     · WAITING 状态在 run 7/8/18/19/20/21 里反复出现 —— 小程序门禁多轮没跑到
#     · 而流水线【整体 status 照样给出终态】
#   ⇒ 一个必需门禁可以完全没跑,而合并卡点看到的是"流水线跑完了"。
#   ⇒ 这正是「回执 ≠ 观测」在云效上的形态:流水线说跑完了 ≠ 每个门禁都跑了。
#
# 用法:
#   ALIBABA_CLOUD_YUNXIAO_ACCESS_TOKEN=... \
#   ALIBABA_CLOUD_YUNXIAO_ORGANIZATION_ID=... \
#   bash ci/verify-pipeline-run.sh <pipelineId> [runId]   # 从仓库根跑
#
# 退出码:
#   0 = 12 个 job 全部真跑过且 SUCCESS
#   1 = 有 job 没跑(WAITING/INIT/QUEUED)、失败、或【工具层读不到状态】
#       ⚠️ 工具层失败会明确打印「【工具层失败】…不是门禁判红」并附上 stderr,
#          别把它和真的门禁红混为一谈(2026-08-27 之前这种情况是【零输出静默退 1】)
#   2 = 还在跑(RUNNING/INIT),不给结论
#
# 负控(必须能红):
#   bash ci/verify-pipeline-run.sh --selftest
set -euo pipefail

PY=/usr/bin/python3

# 判据本体 —— check_run 与 --selftest 共用同一份,杜绝「自测测的是拷贝」
read -r -d '' PYCHECK <<'PYEOF_INNER' || true

import sys,json
d=json.load(sys.stdin)
jobs=[(j.get("name"),j.get("status")) for s in (d.get("stages") or [])
      for j in ((s.get("stageInfo") or {}).get("jobs") or [])]
overall=d.get("status")
rid=d.get("pipelineRunId")
print("run %s 整体=%s 共 %d 个 job" % (rid, overall, len(jobs)))
bad=[(n,s) for n,s in jobs if s not in ("SUCCESS",)]
for n,s in jobs:
    print(f"  {s:8} {n}")
# ★ 判据不是「整体 status」,而是「每个 job 都真的产生了终态且为 SUCCESS」
# ★ 2026-08-27 实测踩到:run 30 排在 run 29 后面没开始,整体=WAITING、12 个 job 全 WAITING,
#   而下面那条「有 job 根本没跑」把它判成了红。那是【假红】——
#   整体也是 WAITING 时,流水线是【诚实地说自己还没开始】,没有掩盖任何东西。
#   真正的病灶是「整体已是终态,却仍有 job 停在 WAITING」——那才叫掩盖。
#   ⇒ 只有整体进了终态,WAITING 才算「根本没跑」。
TERMINAL = ("SUCCESS", "FAIL", "FAILED", "ERROR", "CANCELED", "CANCELLED")
running=[(n,s) for n,s in jobs if s in ("RUNNING",)]
if running or overall not in TERMINAL:
    print()
    print("⏳ 尚未跑完,不给结论(整体 %s;RUNNING/WAITING 都是中间态,不能当通过):" % overall)
    for n,s in (running or jobs): print("   · %s = %s" % (n,s))
    sys.exit(2)
never_ran=[(n,s) for n,s in jobs if s in ("WAITING","INIT","QUEUED",None)]
if never_ran:
    print()
    print("❌ 有 job 根本没跑(既不绿也不红)—— 流水线整体状态掩盖了它:")
    for n,s in never_ran: print(f"   · {n} = {s}")
    print("   这等于该门禁不存在。fail-closed。")
    sys.exit(1)
# ★ 白名单,不是黑名单(2026-08-27 修 fail-open)。
#   原来写的是 failed = s in ("FAIL","FAILED","ERROR","CANCELED") —— 枚举【已知】失败名。
#   于是 status="CANCELLED"(双 L)既不在 never_ran 也不在 failed,
#   整体又已终态 ⇒ 直接掉到最后那句「✅ 全部 job 都真跑过且通过」。
#   【取消的 job 被报成通过】= fail-open,正是本脚本要防的东西。
#   而 GitHub 把【超时】就报成 cancelled,云效用哪种拼写并不确定 ——
#   ⇒ 判据必须是「只有 SUCCESS 才算过」,任何未知/新增状态一律不放行。
not_success=[(n,s) for n,s in jobs if s != "SUCCESS"]
if not_success:
    print()
    print("❌ 有 job 不是 SUCCESS(只有 SUCCESS 才算通过,未知状态一律不放行):")
    for n,s in not_success: print(f"   · {n} = {s}")
    sys.exit(1)
if not jobs:
    print("❌ 读不到任何 job —— 没验 ≠ 没问题,fail-closed"); sys.exit(1)
print()
print(f"✅ 全部 {len(jobs)} 个 job 都真跑过且通过")
PYEOF_INNER

check_run() {
  local pid="$1" rid="${2:-}"
  local json err rc

  # 自测注入口:让负控喂假数据【走真代码路径】。
  # ⚠️ 2026-08-27 教训:原来的 --selftest 在自己内部【复制了一份判据】,
  #   于是真函数里的 WAITING 假红 bug 存在、而自测照样全绿 ——
  #   负控不走真代码路径就等于没验(同 feedback_negative_control_anchor_must_be_structural)。
  if [ -n "${VPR_FAKE_JSON:-}" ]; then
    printf '%s' "$VPR_FAKE_JSON" | "$PY" -c "$PYCHECK"
    return $?
  fi

  # ⚠️ 2026-08-27 实测踩到:原来这里写 `json=$(aliyun … 2>/dev/null)`。
  #   API 瞬时失败时 stderr 被吞、`set -e` 让整个脚本【静默退出 1】——
  #   而 1 的含义是「门禁没跑或失败」。于是「工具坏了」和「门禁真红了」
  #   变成同一个信号,零诊断,无法区分该查代码还是查网络。
  #   这正是本脚本要防的毛病(fail-closed 没错,但必须说清为什么红)。
  # ⇒ 显式接住失败:仍然 fail-closed(非 0),但把 stderr 打出来并写明是【工具层】失败。
  err=$(mktemp)
  if [ -n "$rid" ]; then
    json=$(aliyun devops flow-get-pipeline-run --pipeline-id "$pid" --pipeline-run-id "$rid" 2>"$err") && rc=0 || rc=$?
  else
    json=$(aliyun devops flow-get-latest-pipeline-run --pipeline-id "$pid" 2>"$err") && rc=0 || rc=$?
  fi
  if [ "$rc" != "0" ] || [ -z "$json" ]; then
    echo "❌ 【工具层失败】读不到流水线运行状态(aliyun 退出码 $rc),不是门禁判红:"
    # 只打一遍,走 stdout —— 本脚本的所有结论都在 stdout,
    # 同时写 stderr 会让 `2>&1` 的调用方看到重复(2026-08-27 自己踩到)。
    sed 's/^/     /' "$err"
    rm -f "$err"
    echo "   常见原因:clash 代理掐断国内端点(先 env -u http_proxy …)、令牌过期、瞬时 EOF。"
    echo "   仍按 fail-closed 处理 —— 没读到 ≠ 没问题。"
    return 1
  fi
  rm -f "$err"
  printf '%s' "$json" | "$PY" -c "$PYCHECK"
}

if [ "${1:-}" = "--selftest" ]; then
  # ★ 三条负控全部【喂假数据走真 check_run】,不再内联复制判据。
  fail=0
  t() {  # t <期望退出码> <说明> <假 JSON>
    local want="$1" desc="$2" fake="$3" got
    VPR_FAKE_JSON="$fake" check_run fake >/dev/null 2>&1 && got=0 || got=$?
    if [ "$got" = "$want" ]; then
      echo "  ✅ $desc(退出码 $got)"
    else
      echo "  ❌ $desc —— 期望 $want,实得 $got"; fail=1
    fi
  }

  echo "== 负控 1:整体 SUCCESS 却有 job 停在 WAITING —— 这才是「被掩盖」,必须红 =="
  t 1 "整体 SUCCESS 含 WAITING → 判红" \
    '{"pipelineRunId":1,"status":"SUCCESS","stages":[{"stageInfo":{"jobs":[
      {"name":"A","status":"SUCCESS"},{"name":"B","status":"WAITING"}]}}]}'

  echo "== 负控 2:全 SUCCESS 必须判绿 =="
  t 0 "全 SUCCESS → 判绿" \
    '{"pipelineRunId":1,"status":"SUCCESS","stages":[{"stageInfo":{"jobs":[
      {"name":"A","status":"SUCCESS"},{"name":"B","status":"SUCCESS"}]}}]}'

  echo "== 负控 3:整体也是 WAITING(run 还没开始)—— 是【还没】不是【没跑】,必须给 2 =="
  # 2026-08-27 实测:run 30 排在 run 29 后面,整体 WAITING、12 个 job 全 WAITING,
  # 旧代码判成红(exit 1)。那是假红 —— 流水线诚实地说自己还没开始,没掩盖任何东西。
  t 2 "整体 WAITING 全 WAITING → 不给结论" \
    '{"pipelineRunId":1,"status":"WAITING","stages":[{"stageInfo":{"jobs":[
      {"name":"A","status":"WAITING"},{"name":"B","status":"WAITING"}]}}]}'

  echo "== 负控 4:有 job 失败必须红 =="
  t 1 "含 FAIL → 判红" \
    '{"pipelineRunId":1,"status":"FAIL","stages":[{"stageInfo":{"jobs":[
      {"name":"A","status":"SUCCESS"},{"name":"B","status":"FAIL"}]}}]}'

  echo "== 负控 5:CANCELLED(双 L)必须红 —— 旧代码的 fail-open 就在这 =="
  # 旧判据枚举的是 ("FAIL","FAILED","ERROR","CANCELED") 单 L,
  # 双 L 的 CANCELLED 会掉到「全部通过」。GitHub 把【超时】就报成 cancelled。
  t 1 "含 CANCELLED → 判红" \
    '{"pipelineRunId":1,"status":"SUCCESS","stages":[{"stageInfo":{"jobs":[
      {"name":"A","status":"SUCCESS"},{"name":"B","status":"CANCELLED"}]}}]}'

  echo "== 负控 6:未知状态必须红(白名单的意义) =="
  # 判据是「只有 SUCCESS 才算过」,所以云效将来新增任何状态都不会静默放行。
  t 1 "含未知状态 SOMETHING_NEW → 判红" \
    '{"pipelineRunId":1,"status":"SUCCESS","stages":[{"stageInfo":{"jobs":[
      {"name":"A","status":"SUCCESS"},{"name":"B","status":"SOMETHING_NEW"}]}}]}'

  [ "$fail" = "0" ] || { echo "检查器自测未通过"; exit 1; }
  echo "检查器自测通过(6 条负控,全部走真 check_run)"
  exit 0
fi

check_run "${1:?缺少 pipelineId}" "${2:-}"
rc=$?
# 0=全部真跑过且通过  1=有 job 没跑或失败(fail-closed)  2=尚未跑完
exit $rc
