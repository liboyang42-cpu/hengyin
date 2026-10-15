#!/usr/bin/env bash
# 资金 MySQL 集成测试的显式门禁(本机 self-hosted runner 专用)。
#
# 它接的是 RegistrationFundsMybatisIntegrationTest(59)/RegistrationEquityMybatisIntegrationTest(10)/
# RefundPayoutMybatisIntegrationTest(30) 等全部以 @EnabledIfEnvironmentVariable(CHENGYIN_FUNDS_MYSQL_IT=1)
# 门控、用 MysqlIntegrationTestConfig 建唯一 scratch 的真库套件(完整登记见 ci/funds_it_gate.py EXPECTED)。
# 2026-09-15 并入地图组队 PlayTeamMybatisIntegrationTest(21)/PlayTeamNearbyMybatisIntegrationTest(4):
# 它们同样带 @EnabledIf,不点名就会在普通 CI 里静默 skip 仍"绿"。
# 普通 CI(无 MySQL)会把它们静默跳过并仍然"绿"——那等于没验证。本门禁把它们钉成显式必跑:
#   1) fail-closed 前置:MySQL 连接前提(env + TCP 可达)缺一即红,绝不静默跳过;
#   2) 唯一临时 schema:每次运行用独立后缀建库,跑完 @AfterAll DROP,绝不依赖/触碰业务库;
#   3) 真跑后由 ci/funds_it_gate.py 从 surefire 回读 skipped=0/无错无失败/用例数达标(回执≠观测)。
#
# 用法:
#   bash ci/funds-mysql-it-gate.sh            # 完整:前置 → 跑全部登记套件 → 回读门禁
#   bash ci/funds-mysql-it-gate.sh preflight        # 只跑资金库 fail-closed 前置
#   bash ci/funds-mysql-it-gate.sh atomic-migration # 只跑 localhost 原子迁移 Python IT
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1   # 路径均为仓库根相对,见 ci/test_cwd_independence.sh

MODE="${1:-run}"

# workflow 注入 4 个 secret,这里必须逐个校验。
# ⚠️ 曾经漏了 CHENGYIN_TEST_MYSQL_PASSWORD:secret 没配也照样进到 mvn,再以一句含糊的连接失败收场,
#    甚至可能连上某台免密 root —— 声明了却不校验的前提,等于没有前提。
# HOST/PORT/USER 要求非空;PASSWORD 只要求**已定义**(空密码是合法配置,但必须是显式配的空)。
REQUIRED_NON_EMPTY_ENV="CHENGYIN_TEST_MYSQL_HOST CHENGYIN_TEST_MYSQL_PORT CHENGYIN_TEST_MYSQL_USER"
REQUIRED_DEFINED_ENV="CHENGYIN_TEST_MYSQL_PASSWORD"

require_env() {
  local missing=()
  local var
  for var in $REQUIRED_NON_EMPTY_ENV; do
    if [ -z "${!var:-}" ]; then
      missing+=("$var")
    fi
  done
  for var in $REQUIRED_DEFINED_ENV; do
    if [ -z "${!var+defined}" ]; then
      missing+=("$var")
    fi
  done
  if [ "${#missing[@]}" -gt 0 ]; then
    echo "❌ 缺少资金 IT 前提环境变量: ${missing[*]}(fail-closed,不跳过)" >&2
    echo "   请在 runner 上以 secrets 注入 MySQL 连接信息后再跑;空密码也要显式配成空串。" >&2
    return 1
  fi
}

preflight_mysql() {
  local host="${CHENGYIN_TEST_MYSQL_HOST}"
  local port="${CHENGYIN_TEST_MYSQL_PORT}"
  # 纯 TCP 探活,不依赖 mysql 客户端。MySQL 不可达 = fail-closed。
  if ! (exec 3<>"/dev/tcp/${host}/${port}") 2>/dev/null; then
    echo "❌ MySQL ${host}:${port} 不可达(fail-closed,不静默跳过)" >&2
    return 1
  fi
  echo "✅ MySQL ${host}:${port} 可达"
}

require_local_target() {
  case "$CHENGYIN_TEST_MYSQL_HOST" in
    127.0.0.1|localhost|::1) ;;
    *)
      echo "❌ 原子迁移 IT 只允许显式 localhost scratch，拒绝 $CHENGYIN_TEST_MYSQL_HOST" >&2
      return 1
      ;;
  esac
}

require_env

if [ "$MODE" = "atomic-migration" ]; then
  require_local_target
  preflight_mysql
  export CHENGYIN_MYSQL_IT=1
  export CHENGYIN_MYSQL_IT_HOST="$CHENGYIN_TEST_MYSQL_HOST"
  export CHENGYIN_MYSQL_IT_PORT="$CHENGYIN_TEST_MYSQL_PORT"
  export CHENGYIN_MYSQL_IT_USER="$CHENGYIN_TEST_MYSQL_USER"
  export CHENGYIN_MYSQL_IT_PASSWORD="$CHENGYIN_TEST_MYSQL_PASSWORD"
  atomic_it_log="$(mktemp "${TMPDIR:-/tmp}/chengyin-atomic-mysql-it.XXXXXX")"
  trap 'rm -f "$atomic_it_log"' EXIT
  set +e
  python3 -m unittest -v \
    ci.test_tandianri_mysql_integration.TandianriAtomicMysqlIntegrationTest \
    ci.test_circle_theme_seed_repair_mysql_integration.CircleThemeSeedRepairMysqlIntegrationTest \
    ci.test_club_constraint_null_safety_mysql_integration.ClubConstraintNullSafetyMysqlIntegrationTest \
    ci.test_merchant_candidate_schema_repair_mysql_integration.MerchantCandidateSchemaRepairMysqlIntegrationTest \
    ci.test_merchant_crm_delivery_check_mysql_integration.MerchantCrmDeliveryCheckMysqlIntegrationTest \
    ci.test_quartz_jobs_pause_repair_mysql_integration.QuartzJobsPauseRepairMysqlIntegrationTest \
    ci.test_migration_historical_schema_forward_fix_mysql_integration.HistoricalSchemaForwardFixMysqlIntegrationTest \
    ci.test_merchant_offer_withdrawal_columns_mysql_integration.MerchantOfferWithdrawalColumnsMysqlIntegrationTest \
    ci.test_community_legacy_owner_mysql_integration.CommunityLegacyOwnerMysqlIntegrationTest \
    >"$atomic_it_log" 2>&1
  atomic_it_status=$?
  set -e
  cat "$atomic_it_log"
  if [ "$atomic_it_status" -ne 0 ]; then
    echo "❌ 原子迁移 IT 失败(unittest exit=${atomic_it_status}，保留原退出码)" >&2
    exit "$atomic_it_status"
  fi
  if grep -Eq 'skipped=[1-9][0-9]*' "$atomic_it_log"; then
    echo "❌ 原子迁移 IT 存在 skipped>0(要求 skipped=0，fail-closed)" >&2
    exit 1
  fi
  atomic_ran_lines="$(grep -Ec '^Ran [0-9]+ tests? in ' "$atomic_it_log" || true)"
  if [ "$atomic_ran_lines" -ne 1 ]; then
    echo "❌ 无法唯一解析原子迁移 IT 实际执行数(fail-closed)" >&2
    exit 1
  fi
  atomic_ran="$(sed -nE 's/^Ran ([0-9]+) tests? in .*/\1/p' "$atomic_it_log")"
  if [ "$atomic_ran" -lt 41 ]; then
    echo "❌ 原子迁移 IT 实际只跑了 $atomic_ran 条(应至少 41 条，fail-closed)" >&2
    exit 1
  fi
  if [ "$atomic_ran" -lt 49 ]; then
    echo "❌ 原子迁移 IT 实际只跑了 $atomic_ran 条(原41条加社区归属8条，应至少49条)" >&2
    exit 1
  fi
  echo "✅ 原子迁移 IT 实际执行 $atomic_ran 条，skipped=0，回读通过"
  exit 0
fi

preflight_mysql

if [ "$MODE" = "preflight" ]; then
  echo "✅ 前置检查通过(preflight 模式,不跑测试)。"
  exit 0
fi

export CHENGYIN_FUNDS_MYSQL_IT=1
# 唯一临时 schema 后缀:优先用调用方(workflow)给的 run 级唯一值;本地兜底用随机值。
# 只允许字母数字下划线(MysqlIntegrationTestConfig 会再校验一次)。
export CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX="${CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX:-_local${RANDOM}${RANDOM}}"
echo "使用唯一临时 schema 后缀: ${CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX}"

# 在同一个 reactor 里构建上游模块 + 只在 admin 模块按类名跑这几套(2026-08-19 前是「先 install 再 test」两步,
# 为什么改成一次,见下面那段 ★)。
#
# ★ 2026-08-14 并入两套 Explore 真库 IT。它们类名以 IT 结尾,而本仓**没有任何 pom 配
#   surefire/failsafe**,Maven 默认 include 只收 *Test ⇒ 它们在 `mvn test`(即 ci/backend-test.sh
#   跑的那条)里**一次都没执行过**,写了等于没写。这里按类名点名,才真跑得到。
#   ⚠️ 不改名、不加 failsafe profile:本 workflow 的两个 job 名已进 master 的 branch protection
#   必需清单,另起一套机制等于再造一个需要维护的闸;点名跑复用的是已被验证过的这条链
#   (fail-closed 前置 + skipped=0 回读)。
# ★ 2026-08-19:原来这里是「先 install 上游到 ~/.m2,再单独 -pl admin test」两步。两步都改掉了。
# ★ 2026-08-19:test 这一步必须带 -am,理由不是"顺手",是**这条闸测的到底是谁的代码**。
#   不带 -am 时 admin 从 ~/.m2 解析 chengyinhub-system.jar;而这台 self-hosted runner 上
#   ~/.m2 是所有分支、所有 worktree 共用的一份 —— 谁最后 install,jar 就是谁的。
#   2026-08-19 实证:#741 的 funds IT 连红五次,本地却绿。真因就是 admin 的测试跑在
#   **master 的** chengyinhub-system 上(master 的 #739 给 CmsActivityMapper 加了
#   `(select t.product_type from cms_topic t ...)` 子查询,而本分支还没并那条),
#   于是"本分支的 fixture + 别的分支的 mapper"凑出一个谁都没写过的组合。
#   ⚠️ 这个方向同样能造出**该红却绿**:本分支自己写坏的 mapper,可能被别人装进去的好 jar 盖住,
#   闸照样绿 —— 那比这次的红危险得多。-am 让上游模块从**本次 checkout 的源码**在同一个 reactor 里构建。
#   两个开关都要,而且都得是 false —— 与 ci/backend-test.sh 里那段是同一条经验:
#     · -DfailIfNoTests=false:上游模块(common/framework/…)本轮一条用例都不跑,带 true 会红在
#       "No tests to run!" 上,红的原因跟资金没有半点关系;
#     · -Dsurefire.failIfNoSpecifiedTests=false:surefire 3.x 的另一个开关,管的是"-Dtest 点名的类
#       在这个模块里不存在"。
#   ⚠️ 把 failIfNoTests 放开**不会**让本闸退化成静默跳过:真正的 fail-closed 在下面那句
#   `python3 ci/funds_it_gate.py` —— 它逐套要求 surefire 报告存在、skipped=0、用例数达标,
#   一套没跑就没有报告,当场红。这才是观测,mvn 的退出码只是回执。
#   顺带把原先的 `install -DskipTests` 去掉:有了 -am,上游模块在同一个 reactor 里构建,
#   本闸不再需要 ~/.m2 里有什么;而且它自己也不再往那份共用仓库里写 jar ——
#   否则这台 runner 上跑一次资金闸,就会把别的分支/worktree 的构建结果覆盖掉一次(方向相反的同一个坑)。
mvn -B -pl chengyinhub-admin -am clean test -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=CoopListReadOnlyMybatisIntegrationTest,RegistrationFundsMybatisIntegrationTest,RegistrationEquityMybatisIntegrationTest,RefundPayoutMybatisIntegrationTest,ExploreAllianceMvpRealDbIT,ExploreAllianceRedeemRealDbIT,TandianriBatch0FundsMybatisIntegrationTest,ShopDayOneTicketMySqlIT,TandianriBatch3FundsMybatisIntegrationTest,ClubCorporatePayeeMybatisIntegrationTest,ClubEditionCompensationMybatisIntegrationTest,PlayerWithdrawalCooldownMybatisIntegrationTest,ClubPayableMybatisIntegrationTest,CouponLifecycleMybatisIntegrationTest,MysqlItSchemaIsolationTest,ActivityCancelFreeOrderMybatisIT,PlayTeamMybatisIntegrationTest,PlayTeamNearbyMybatisIntegrationTest,WxPaymentCallbackFencingMySqlIT,WxContentCallbackFencingMySqlIT,CoopDepositRefundMybatisIntegrationTest,GrowthMissionProofMybatisIntegrationTest,MerchantIdentityMybatisIntegrationTest,MerchantProfilePersistenceMybatisIntegrationTest,StoreVerifyGrowthDeliveryTransactionTest,RefundNotificationMybatisIntegrationTest,SessionRefundClockMybatisIntegrationTest,SettlementGenerateBatchMybatisIntegrationTest,FirstReviewPointsClawbackMybatisIntegrationTest,ClubSettlementSummaryMybatisIntegrationTest,CoopComplaintCloseMybatisIntegrationTest,RegistrationReadContractMybatisIntegrationTest,ClubWaitlistTokenMybatisIntegrationTest,CmsMemberTemplateAudioPatchMybatisIntegrationTest,CmsTemplateLibraryAudioPatchMybatisIntegrationTest,ContentInteractionMybatisIntegrationTest,CreativeSquarePublishIdempotencyMybatisIntegrationTest,FollowConcurrencyMybatisIntegrationTest,FollowEditRaceMybatisIntegrationTest,NearbyRunnerShopsMybatisIntegrationTest,PhotoReviewProgressMybatisIntegrationTest,SpoilerAccessMybatisIntegrationTest,SquareEditFollowMybatisIntegrationTest,RoamSessionWriteMybatisIntegrationTest,RoamLocationRetentionMySqlIT,ComplaintPeriodWithdrawalGateMybatisIntegrationTest,ShareSnapshotRunSessionMybatisIntegrationTest,PlayerRunSessionOrderingMybatisIntegrationTest,RoamShareSnapshotConcurrencyMySqlIT,ClubEventTransferCancellationMySqlIT,RefundRetryAndRemainderMySqlIT,SignupNoticeAfterCommitPersistenceMySqlIT,TopicLikeDesiredStateMySqlIT,MonitorMissingJobsMySqlIT,MemberPasswordWidthMySqlIT,LegacyCategoryForwardGuardMySqlIT,MissingBusinessSeedsMySqlIT,RoamPresenceRevokeDurabilityMySqlIT,RoamPresenceRevokeJobMySqlIT,WxBillIngestionPersistenceTest

# 回读观测:surefire 绿不够,必须逐套确认真跑、skipped=0、无错无失败、用例数达标。
python3 ci/funds_it_gate.py chengyinhub-admin/target/surefire-reports
