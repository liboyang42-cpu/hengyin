#!/usr/bin/env bash
# 后端门:编译 + 单元测试。CI(云效 Flow)与本地共用。
# 用法:bash ci/backend-test.sh   (从任何目录执行皆可,脚本自己回到仓库根;CI 环境有网,自动拉依赖)
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1   # 路径均为仓库根相对,见 ci/test_cwd_independence.sh

python3 -m unittest ci/test_role_permission_contract.py
GATE_SELFTEST=1 GATE="$PWD/ci/assert_it_ran.py" python3 -m unittest ci/test_assert_it_ran.py

# 全量编译 + 跑测试。首次接入若命中历史遗留失败用例,
# 先在此收敛或用 -pl 缩到改动模块,再逐步扩全。
backend_unit_log="$(mktemp)"
if mvn -B clean test >"$backend_unit_log" 2>&1; then
  # 完整 Maven 输出仍由原命令生成；只在控制台保留结果摘要，避免日志读取通道溢出。
  tail -n 30 "$backend_unit_log"
  rm -f "$backend_unit_log"
else
  backend_unit_exit=$?
  # 保留所有 Maven 错误和失败套件，再附末尾上下文；退出码原样传递，不能静默变绿。
  python3 - "$backend_unit_log" <<'PY'
import re
import sys
from pathlib import Path

lines = Path(sys.argv[1]).read_text(errors="replace").splitlines()
for line in lines:
    if "[ERROR]" in line or re.search(r"Failures: [1-9]|Errors: [1-9]", line):
        print(line)
print("--- Maven failure context ---")
print("\n".join(lines[-160:]))
PY
  rm -f "$backend_unit_log"
  exit "$backend_unit_exit"
fi

# ---- 真库集成测试(*IT):必须点名跑 ----
# ★ 为什么要单独一段:类名以 IT 结尾,而本仓**没有任何 pom 配 surefire/failsafe**,
#   Maven 默认 include 只收 *Test/*Tests/Test*/*TestCase ⇒ 上面那句 `mvn test` 一个都不会跑。
#   2026-08-14 实测:这批里 9 套从来没被执行过,而其中 6 条真跑起来是**红**的
#   (库 schema 漂了、服务长出新依赖没注入),假绿掩盖了多久无从考证。
#   它们内部原本还有 assumeTrue(false):库连不上就整套 skip,而 skip 算绿 —— 已一并改成硬失败。
#
# ★ 2026-08-19 起这批改成**一类一库**:每个类连 chengyin_mybatis_<suite>,由 MybatisItDatabase
#   在本 JVM 首次取用时 DROP+CREATE(不是 IF NOT EXISTS —— 那等于把上次崩掉的现场当成合法初始态)。
#   建库要的权限 ituser 没有(service 只把 chengyin_mybatis_it 授给它)⇒ 默认走 root,
#   密码由 .github/workflows/ci.yml 的 backend 步注入(本机 root 空口令,service 是 root/root)。
# 这几套连的是 CI 的 backend job 用 services 起的那个空 MySQL8,
# 各自建表或加载 test/resources/it-schema 里【生产导出的真实 DDL】,跑完即弃。
# ⚠️ 不在此清单里的两套 Explore*RealDbIT 连的是 chengyin_explore_it,
#   由自托管的 ci/funds-mysql-it-gate.sh 负责;CoopServiceRealDbIT 已烂透,见其类注释。
MYBATIS_ITS=(
  # 2026-08-19:类间隔离契约本身也点名跑 —— 它有三条负控(共用必撞 / 真查 information_schema /
  # ★A 的遗留数据不许喂饱 B 的 fail-closed 前置)。不点名 = 没人看着隔离,那这批又会慢慢滑回共用。
  MybatisItSchemaIsolationIT
  ClubLeaderboardContributionMyBatisIT
  ComplaintPickerOwnerType3RealDbIT
  CoopMapperMyBatisIT
  MemberPrivacyMyBatisIT
  MerchantGeoSortMyBatisIT
  NpcChatRetentionMyBatisIT
  NpcChatUserDataMyBatisIT
  NpcGovernanceMapperMyBatisIT
  # 2026-09-15 CR-927 节点NPC:npc_profile scope_type=4 的读闸(未审/停用不下发)、
  #   一节点一条的 uk_active_scope 唯一键、以及三条写语句的 scope 隔离 —— 全要真库才验得动。
  NodeNpcMapperMyBatisIT
  PendingRecruitTerminationMyBatisIT
  RoamExploreDayCandidateMyBatisIT
  # 2026-09-17 C-13/C-17:漫游地图图层的 found 按账号投影(roam_poi_found + roam_poi_reward_log)、
  #   待审/驳回投放不进玩家图层、玩家侧按 id 查询与后台全量查询不许混用。删任一条闸即红。
  RoamPoiPlayerVisibilityMyBatisIT
  TopicEditionReopenBlocksColumnMyBatisIT
  ShopDayMerchantDataBoundaryMyBatisIT
  TopicInsertColumnCoverageMyBatisIT
  CircleThemeSeedMySqlIT
  TemplatePreferenceJsonMySqlIT
  TemplateMarketLifecycleMySqlIT
  AdvancedGameRuntimeMySqlIT
  ThemeRuntimeMySqlIT
  ImSendIdempotencyMybatisIntegrationTest
  # R9-02 迟到下注竞态:两个真事务交错(结算等锁定读、锁定读拒已结算),Mock 单测测不到
  PredictLateBetRaceMySqlIT
  # 结算事务兜底:结算中途中断必须整轮回滚(走 @Transactional 真代理),负控=去掉事务边界留孤儿注
  PredictSettleAtomicityMySqlIT
  # 2026-09-15 F15 联系号码接口:归属/负责/同意/日限与审计在真库上的边界(越权拒、撤回同意拒、
  # 俱乐部带团拒、昨日不占今日额度、第 51 次拒且不写审计)。不点名 = 没人看着这条链。
  MerchantCrmContactRevealMySqlIT
  # 2026-09-15 R9-41 假绿修复后登记:待审/驳回/下线的 *在线* 投放(status=1)只有
  # RoamMapper.xml 的 cityNodeContentApproved 能挡,删闸即红。不点名 = CI 永远跑不到这条边界。
  CityNodePendingVisibilityMySqlIT
  # R12-E2E 旅程全链路:建局→到店→问问题→判错→检定→推进→阶段结算→结局全走真库真 mapper;
  #   并发 settle 幂等另有一条负控(测试内临时摘掉动作/回执两把唯一键,断言并发重放消失后再还原)。
  JourneyFullFlowMySqlIT
)
IFS=, ; IT_LIST="${MYBATIS_ITS[*]}" ; unset IFS
# ⚠️ 必须带 -am:上面那句 `mvn clean test` 不 install 兄弟模块,
#   只写 -pl chengyinhub-admin 会去远程仓库找 chengyinhub-framework:3.9.0 而找不到
#   (2026-08-14 实测 CI 红;本地不红只因为本地仓库里躺着先前 install 的产物 —— 典型的
#   「我这儿能跑」)。
# ⚠️ 带了 -am 就必须 -DfailIfNoTests=false:上游模块没有匹配 -Dtest 的用例,
#   true 会让它们直接以「No tests to run」失败。
#   surefire 3.x 还要 -Dsurefire.failIfNoSpecifiedTests=false(与 -DfailIfNoTests 是两个开关,
#   少一个上游模块照样以「No tests matching pattern」失败)。
#   「到底跑没跑」的保证**不靠这两个开关**,靠下面 assert_it_ran.py 的回读。
CHENGYIN_IM_SEND_MYSQL_IT=1 mvn -B -pl chengyinhub-admin -am test -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false -Dtest="$IT_LIST"

# 回读观测:surefire 绿不够。skipped != 0 说明又有人把「连不上就跳过」放回来了,
# 那等于这段白跑 —— 必须红。(回执 ≠ 观测)
python3 ci/assert_it_ran.py chengyinhub-admin/target/surefire-reports "${MYBATIS_ITS[@]}"
