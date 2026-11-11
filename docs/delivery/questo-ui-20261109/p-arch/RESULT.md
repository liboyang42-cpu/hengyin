# p-arch 结果：为上线 PR #779 准备 master 架构策略

分支 `claude/arch-owner-launch-20261111`（从 `origin/master` `000a48f6b` 切出），做法照搬已合并的 #748。草稿 PR 合入 master，没有合并，也没有标记为可评审。

## 一、门禁复跑（参数与 CI 相同）

- 候选：`launch/questo-20261110` 当前头 `afa90ffd5`。
- 验证器：`c7c5e838b`，即 master 当前钉住的版本。
- 模拟 `pull_request` 事件：target 为 master `000a48f6b`，比较基线取 merge-base。

运行 `gate.py source`，结论 BLOCKED，共 9 条：

| 规则 | 对象 |
|---|---|
| DEPENDENCY_EXPANSION | `chengyinhub-system/pom.xml`（StoreKit，业主已接受） |
| NEW_UNOWNED_BOUNDARY | `ApiMerchantPayoutAccountController` |
| NEW_UNOWNED_BOUNDARY | `ApiPlatformPassController` |
| NEW_UNOWNED_BOUNDARY | `ApiPlayFeedbackController` |
| NEW_UNOWNED_BOUNDARY | `EcomCombineMapper` |
| NEW_UNOWNED_BOUNDARY | `EcomPayoutMapper` |
| NEW_UNOWNED_BOUNDARY | `PlayHelpAuditMapper` |
| NEW_UNOWNED_BOUNDARY | `PlayRunFeedbackMapper` |
| NEW_UNOWNED_BOUNDARY | `PlayRunFeedbackService` |

这与 qx-final2 RESULT 第四节一致。

还没合进 launch 的 lane 分支，没有新增 `src/main/java` 文件，所以不会再出现新的边界：

| 分支 | 领先 launch 的提交数 |
|---|---|
| f-merchant | 14 |
| f-pages | 4 |
| f-play | 4 |
| l-icons | 10 |
| l-iconsB | 7 |
| f-compliance、f-money、f-release | 0 |

Backend 的编译期引用门只有 1 条发现，取自 CI run 42994679091 的 job 日志：`EXACT_STORAGE_PORT_CONSUMER`，`ApiTopicTemplateController → AuthorWriteReceiptService`（`M_AUTHOR_WRITE_RECEIPT`）。

## 二、登记内容

### 提交 1 `15618bc6e`：登记归属

新增 8 个 `P_` 技术职责。每个职责只登记 1 个类型，并写明禁止回依赖：

| 职责 | 模块 | 成员 | 禁止回依赖 |
|---|---|---|---|
| `P_ECOM_PAYOUT_STORAGE` | system | `EcomPayoutMapper` | `CreatorPayoutService`、`EcommerceSubMerchantService`、`EcommerceProfitShareService` |
| `P_ECOM_COMBINE_STORAGE` | system | `EcomCombineMapper` | `EcommerceCombinedSettlementService`、`EcommerceCombinedPayment` |
| `P_PLAY_FEEDBACK_STORAGE` | system | `PlayRunFeedbackMapper` | `PlayRunFeedbackService` |
| `P_PLAY_HELP_AUDIT_STORAGE` | system | `PlayHelpAuditMapper` | `PlayHelpAuditController` |
| `P_PLAY_FEEDBACK` | system | `PlayRunFeedbackService` | `ApiPlayFeedbackController` |
| `P_MERCHANT_PAYOUT_ACCOUNT_API` | admin | `ApiMerchantPayoutAccountController` | `EcomPayoutMapper`（不得绕过服务直连存储） |
| `P_PLATFORM_PASS_API` | admin | `ApiPlatformPassController` | `PlatformPassMapper`（同上） |
| `P_PLAY_FEEDBACK_API` | admin | `ApiPlayFeedbackController` | `PlayRunFeedbackMapper`（同上） |

新增 2 条允许方向，都是源码里实际存在的前向引用：

- `P_PLAY_FEEDBACK_API → P_PLAY_FEEDBACK`
- `P_PLAY_FEEDBACK → P_PLAY_FEEDBACK_STORAGE`

其余类型与已登记的职责之间没有引用。策略仍然无环。

另外两处改动：

- `M_AUTHOR_WRITE_RECEIPT.allowed_consumers` 末尾加入 `com.chengyinhub.web.controller.api.ApiTopicTemplateController`。
- 门禁自测：
  - `test_author_write_owners.py` 的消费者清单加上它。
  - `test_gate.py` 断言 `P_` 有 8 个职责、每个 1 个成员、只有上述 2 条方向；旧职责和旧方向逐条不变。

这次没有授予任何表或字段的写权，没有授予私有 Mapper 消费权，没有通配 owner，也没有删除任何检查。

### 提交 2 `22bbf6b40`：改钉验证器

ci.yml 两处可信验证器 checkout、`compiled_controls.py` 的 `VERIFIER` 及其自测，改钉到提交 1，与 #748 相同。合并时必须保留原提交，不能 squash，否则钉住的验证器版本不可达。

### StoreKit 依赖

StoreKit 依赖保持 #748 的记录方式：`gate.py` 没有依赖批准清单，所以本 PR 不改 `gate.py`。业主已接受这项依赖，批准记录写在 PR 正文里。#779 上的 `DEPENDENCY_EXPANSION` 会继续是红的，需要业主按批准合并。

## 三、验证

1. 用新策略（提交 1 作为验证器）复跑同一事件模拟。候选是本地合成的「launch 头 + 提交 1」合并 `3a1319fb9`，目标是提交 1；这模拟的是 launch 合入新 master 之后的状态。结果 BLOCKED，只剩 1 条 `DEPENDENCY_EXPANSION`，8 条 `NEW_UNOWNED_BOUNDARY` 全部消失。
2. 新旧两次运行的 1146 条 review item 逐条相同，changed_files 也相同。
3. 编译期规则离线回放（`collect_jdeps.technical_dependency_findings`，用合成索引）：
   - 正向引用的结论是 `PASS_DECLARED_OWNER_DIRECTIONS`，0 条发现。正向引用指 `ApiTopicTemplateController→AuthorWriteReceiptService` 和反馈链 2 条。
   - 反向用例仍然全部变红：控制器直连 Mapper、存储回引服务、未登记控制器调用 `AuthorWriteReceiptService`，分别报 `OWNER_GOD_BACKREFERENCE`、`NEW_OWNER_DEPENDENCY_DIRECTION`、`EXACT_STORAGE_PORT_CONSUMER`。
4. `python3 -m unittest discover -s ci/architecture -p 'test_*.py'`：334 项，与未改动的 master 结果完全相同。
   - 都是同样 6 个 `test_command_checklist_references_real_baseline_sources` 子用例报错，原因是本地克隆缺少对应基线路径，属于环境问题。CI 上的同一自测是通过的。
   - 本 PR 没有新增失败。
5. `test_compiled_controls.py`：10 项通过。

没有在本地跑 Java 编译。按 AGENTS.md 约定，Java 构建在 CI 或测试机上跑。CI 产物下载域名被本会话的网络策略拦截，所以没有拿到 jdeps 原始观测，编译期边以源码引用为准。PR 的 Backend job 会用真实 jdeps 复核。

## 四、已知副作用与后续

- 本 PR 登记的类型在当前 master 的编译产物里还不存在。合并后、#779 落地前，master 上 Backend 的 compiled-ports 会因 `OWNER_TYPE_INPUT_UNKNOWN` 报 UNKNOWN，情况与 #748 相同。建议与 #779 在同一窗口合并。
- #779 需要先合入新 master，候选里的 `ci/architecture/*` 才会与可信策略一致；不合入的话会报 `POLICY_CHANGE`。

STATUS: DRAFT_PR_OPENED — 待业主审阅合并；StoreKit 依赖按约定保持红灯由业主放行
