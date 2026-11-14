# integration/backend-app-20261112 — 后端整合结果（TEST 环境）

基线：master `92eda9126`（#812）。全部构建/测试/真库/启动都在测试 VPS（be-int）上执行；未连接或修改任何生产库与生产配置。

## 合入
- 已在 master（0 个独有提交，无需合入）：#746 #740 #741 #744 #745 #743 #742 #747 #755 #756 #766 #769 #764 #767 #751(review build，不合入)。
- 本分支合入（按依赖序）：#730 → #725 → #724 → #749 → #750 → #757 → #788 → #787。
- 未合入：#770 r4-motion（仅小程序动效，PR 标题「勿合并」，与当前主干 33 个小程序文件冲突，无后端影响）——留给小程序 owner 重新基于 master 变基。
- 频道/好友 #726 #728 #727 按分工留给 channels worker。

## 冲突处理
- #730：6 个文件冲突全部取主干。主干已含该 PR 的等价实现（ExploreSaleSnapshotGate、四店停售、P-01 城市定向开售）且更新；保留 PR 新增的两条 ClubSessionServiceImplTest 用例与 funds 门计数 ShopDayOneTicketMySqlIT 5→6（源码实有 6 条）。
- #725：ci/migration-dependencies.json 取并集（主干条目 + 20261105_l4_invoice_command_receipt）。

## 修复
- PersonalGameplayRuntimeServiceTest（#724 新增用例）：回执/重放比对按 JSON 线格式归一（Long/Int 节点差异，不是行为差异）。
- PlatformPassMapper.selectLibrary：cms_topic.lifecycle 是 tinyint(1)，生产 JDBC 未设 tinyInt1isBit=false，驱动给 Boolean（3→1），游戏库 passCovered 恒 false。改为 `lifecycle + 0`。PlatformPassMySqlIT 由 7/9 转 9/9。
- 真库 IT 登记：AppMediaAssetMySqlIT 源码 6 条、登记 5 条 → 6；批次总数 238→239。

## 结果
- `mvn -DskipTests package`：通过。
- 全模块单测：15513 条，修复后 0 失败（373 skipped 为门控真库类）。
- 新库迁移：scripts/e2e/build_schema.py 在一次性 MySQL 8.0.45 上建 cy_e2e_beint：base 227 表 + 台账拓扑序 355 个快照后迁移，问题 0，最终 522 表（5 条仅数据断言观察，空库预期）。
- MDB-F2（marketing-db-acceptance，ci/real_storage_it.py 原门禁逐类新容器）：25 类 / 239 条全部 PASS、cleanup CLEAN。MarketingRevenueAllocationMySqlIT 需 RUNNER_ENVIRONMENT=github-hosted 门控（一次性回环容器上满足）。
- 启动：TEST overlay（随机每次生成的密钥、cy_e2e_beint、Redis db9）启动成功；微信支付因无商户证书降级（预期）。
- 冒烟：真实 /api/login/phone（验证码夹具种入 TEST Redis）取得玩家 token；App 调用的 154 个 GET 路由日志中无 SQL/NPE/缺列/缺表异常。

## TEST 基址
https://8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech （仅 VPS 运行期间可达；TEST 数据；后台 sys_user 均已禁用）
