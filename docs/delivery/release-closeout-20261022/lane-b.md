# Lane B 交付：社区帖图审核身份（#377 接续）+ #30 隐私回归核对

- 分支：`feature/lane-b-community-20261022`（基于 master `063a6c3cd7c1f131618148c385174037f1d2786e`，2026-10-22 拉取时仍是这个 SHA）
- Draft PR：https://github.com/liboyang42-cpu/hengyin/pull/455（引用 #377 和 #30；未合并、未标 ready）

## 改了什么
1. 移植 #377 的 a0d86f25。它在当前 master 上可以干净应用。
   - `IWxContentSecurityService` / `WxContentSecurityServiceImpl` 新增五参重载 `submitMediaCheckOne(bizType, bizId, fetchUrl, identity, openid)`。微信拉图用预签名 URL，inbox 用稳定的 OSS 对象 key 登记。
   - 原来的四参入口改为委托新重载（identity 就是送检 URL），其它业务行为不变。官方活动分支没有改动。
   - `ICommunityPostService.activeMediaUrls` 改为 `activeMediaKeys`（返回规范化后的对象 key，非本存储的媒体跳过）。
   - `CommunityPostServiceImpl.submitMediaForAsyncCheck` 送检时传 `canonicalMediaKey(url)` 作为身份。
   - 两个现有测试类（`CommunityPostAuditLevelTest`、`CommunityPostMediaCallbackVisibilityTest`）按 #377 调整为基于 key 的断言。
2. 新增验收测试 `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/CommunityPostMediaIdentityAcceptanceTest.java`。
   - 测试串起真实链路：publish → 送检 → inbox register/process → mutation → applyRiskyMediaResult。
   - 只把 mapper 换成内存实现。key 规范化走真实的 `FileOssUploadUtils.communityMediaObjectKey(ref, bucket, endpoint)`。
3. #30：没有代码改动（结论见下）。

本 PR 没有迁移、没有新依赖，也没有改鉴权或 Controller。

## 验收清单
| 验收项 | 用例 | 证据 |
|---|---|---|
| 同一个 key 不同签名，是同一个身份 | `sameKeyWithDifferentSignaturesIsTheSameIdentity` | 两次签名的 URL 不同，但 key 和 inbox media_hash 都等于 sha256(key)；回调后帖子变成 HIDDEN |
| key 变化后，旧回调被拒 | `oldCallbackIsRejectedAfterTheKeyChanges` | inbox 状态为 IGNORED_STALE，帖子仍是 PUBLISHED，没有复核单，也没有通知 |
| 他人的对象、错误的业务对象不影响当前帖子 | `anotherUsersObjectOrWrongBusinessObjectDoesNotAffectTheCurrentPost` | 别人的 key → STALE；别人帖子的回调只处置那条帖子；bizType=评论 → 不进入社区分支；同一个 trace 改登记到别的帖子 → register 返回 false |
| 违规图只改变一次可见性、只通知一次 | `violatingImagesChangeVisibilityOnceAndNotifyOnce` | 两张图都违规时：HIDDEN 转换 1 次，submitSafetyHold 1 次，IM 通知 1 次 |
| 重复回调不产生重复后果 | `duplicateCallbacksCauseNoDuplicateConsequences` | 同一个 trace 回调 3 次：outbox、复核单、通知各 1 次 |
| 四参旧调用方仍然可用 | `legacyFourArgumentCallerRegistersTheSubmittedUrl` | 登记的就是送检 URL 本身 |

**先红后绿**：把 `register(..., identity.trim())` 临时改回 `register(..., img)`（也就是 master 现在的行为）后，有 3 个验收用例失败：
- `sameKey…`：哈希不相等；
- `violating…`：帖子仍是 PUBLISHED；
- `duplicate…`：帖子仍是 PUBLISHED。

恢复后全部通过。

**本地验证（JDK 21.0.12，Maven）**
- `-Dtest=CommunityPostMediaIdentityAcceptanceTest,CommunityPostMediaCallbackVisibilityTest,CommunityPostAuditLevelTest`：23/23 通过。
- `-Dtest=Community*,WxContentSecurity*,WxContentCallback*,*MediaCallback*,OfficialEvent*,ContentSafety*,AuditTask*,FileOssUploadUtils*`：593 个用例，0 失败。有 2 个 error，都是 MyBatis 真库 IT（`CommunityMediaEditMySqlIT`、`CommunityActivityReferenceMySqlIT`），原因是沙箱里没有 MySQL；常规 CI 会带 MySQL 跑这两个。

## #30 核对（社区快照隐私，10-15 旧修复）
- #30 的 base 是 `codex/mini-bug-arch-integration-20261015`，不是 master。它共 54 个文件，其中 27 个是 docs。
- 非 docs 的 27 个文件在 master 上都存在。逐个文件对 #30 的补丁做 `git apply --check -R`：
  - 20 个文件 master 完整包含（包括 `CommunityPostServiceImpl` 的只读一致快照详情读取、`ViewCreativeSquare*`、`ApiCreativeSquareController`）；
  - 另外 7 个（`CommunityPostMapper.xml` 和 6 个测试夹具）的差异都是 master 后来的演进：`selectReferences` 多了 LEFT JOIN 主题投影，夹具补了 `del_flag/topic_id` 列和 `cms_topic` 表。这些都是 #30 的超集。
- 结论：**#30 没有需要移植的修复或回归测试**。
- 但有一个缺口：#30 当时标记 NOT_RUN 的真库隐私回归（E02_01/06/10/11/15/16/18/20/22、E02_25MySqlIT、B05CommunityVisibilityMySqlIT）在常规 CI 中被 `@EnabledIfEnvironmentVariable` 跳过。它们只能通过手动派发 `real-storage-it.yml` 来跑。
- 本 lane 在分支头 `9cad792e` 上派发了两个批次：`port-community-e02` 和 `port-b05-visibility`。结果见下方 CI 表。

## CI
（运行 ID 和结论在下方「CI 结果」一节补全。）

## 未解决的阻塞 / 后续
- 已知限制（与 #377 相同）：预签名 URL 5 分钟后过期。微信如果超过 5 分钟才来拉图会拉取失败，表现为收不到回调。
- 历史广场帖（legacy-square 投影）的违规图只下架 `ums_creative_square`，不会同步 `community_post`。
- Doc 1 §5.2 中旧的本地预上线社区改动不在本 lane 范围内，等 owner 提供。
- E02/B05 真库隐私 IT 是否要接进常规 CI 门禁，属于 Lane C（CI gates）的范围。本 lane 没有改 workflow。

## owner 需要在本地做的手工检查
- 不需要 WeChat DevTools 检查：本次没有小程序改动。
- 部署后如果推文切到先发后审（POST_REVIEW），用真机发一条带图帖，确认微信回调能命中，后台出现 `POST_MEDIA_RISKY_HIDDEN` 或者 PASS 记录。也可以查 inbox 表，确认 state 为 DONE 而不是 IGNORED_STALE。
