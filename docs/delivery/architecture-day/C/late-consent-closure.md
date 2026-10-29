# C：H-C9-01 迟到 Photo 同意回调隔离

结论：H 的真实反例已复现并修正。Photo 同意的 fulfilled/rejected 两条路径都验证原登录代次、场次、页面及题目上下文；过期结果只清理原操作，不重置当前卡片或发 toast。相关 11 文件实际 **158/158 pass、0 fail/skip**。Java/MySQL、供应商和真机未运行；本补丁不宣称后端 Photo 编排或联合候选已验收。

## 候选与文件边界

- 公共仓库 `liboyang42-cpu/hengyin`，工作树 `/workspace/hengyin-c`，分支 `codex/architecture-day-c-play-command`。
- 精确 parent `09b1c1ad317c0f1449aef0ec378f48665638cb82`；先前 9da/971/09b 均不重写。本 delta 在 09b 的合法 mini caller 夹具之上继续修真实生产漏口。
- H 公开审查 `c5909f9ad856f7c80423f7230cf91a3949652333`，只读取得 `docs/delivery/architecture-day/H/c-photo9da/REVIEW.md` 与 `late-consent.review.patch`；回归新增到 C 自有测试，没有修改 H 文件。
- 生产只改 `chengyinhub-xcx/pages/play/index.js`；测试改 `play-qa-photo-upload-contract.test.js`、`separate-consent-feature.test.js`，并按最终源核对 `tests/lint/ui-integrity-baseline.js`。其余为本 C 交付证据。
- 不修改 ThemeRuntimeMySqlIT，markApplied 写后抛错场景由 root 独占；不改 E/G 代码、schema、共享 upload-client、pending/helper、root ledger；WP09 保持只读。未 dispatch、未部署。

## 失败与最小合同

真实路径：`_submitPlayKitAction(shoot)` → `_submitKitPhoto` → 真实 separate-consent → recordConsent 异步结果。Page-bound operation 仅按会员轮询，A→B→A 可在两次轮询之间完成而未 abort。真实同意 helper 正确拒绝旧登录，但原 Page catch 只看 isAborted，直接 fail，经 `_notifyPhotoFail` 修改当前 CARD 的 photoFailSeq 并弹 toast。H 证明上传/动作都是 0，故这是迟到 UI 漏口，不把它说成已经跨账号上传或重复计费。

本补丁复用 Page 已有 `_capturePlayLoginContext` 和 `_kitPhotoIntent`：

1. 原 intent 冻结 owner、authorization/login generation（仅内存）、场次键、页面 epoch、原 active/组件对象；题目冻结原 session/version 以及组件、state、kit、页面的 node 身份，防对象原地改值。
2. 统一 consentCurrent 组合原 intent 指针、页面原上下文、原题目和 operation 状态；传给真实同意 helper，并在 fulfilled 和 rejected 回调入口再次验证。不能只靠 helper 或 memberId；即使同一 Page 被新 A 登录重绑，也不能接受旧 intent。
3. rejected 先记录 owned，再清理原 operation，仅 owned 时调用 fail；fulfilled 过期时清理退出，不上传。替换的新 `_kitPhotoIntent` 不被旧清理删除。仍保留原上传/loading 资源收尾，不新增另一套状态所有权。
4. 上传回调沿用同一个上下文守卫，并共用冻结的题目比较；原合法同意、原拒绝的本操作反馈、canonical assetId、存储读回、UNKNOWN 原 key/expected/payload 和账号 pending 分区均保留。

## 实际执行与反事实

证据在 `evidence/late-consent-closure/`；TAP 只去除行尾空格。

- 将 H 原 patch 的新增用例原样接入 C 测试后，修生产前实际 **12 tests：11 pass/1 fail**，失败是当前卡片数据被旧 catch 修改，见 `h-original-red.tap`。修后同一文件 **12/12**。
- `separate-consent-feature` 原 9 条保留，新增 fulfilled/rejected × 8 种上下文变化：ABA 后重绑、页面 epoch、组件节点、页面节点原地变更、场次、session、version、新 intent。实际 **25/25**。原当前同意持久化失败新增精确 photoFailSeq=1 断言，证明没有通过关闭所有错误反馈来变绿。
- 同一 25 条测试只把 Page 源替换为精确公开 parent 的只读编译输入，实际 **9 pass/16 fail**。`original-page-preload.cjs` 与 counterfactual-summary 记录重现方式，未替换工作树或改测试条件；不是当前源 16 fail。
- 相关 11 文件逐一运行 `node --test --test-isolation=none --test-reporter=tap tests/unit/<name>.test.js`，各自 exit 0：60+6+25+12+8+6+20+3+7+4+7=**158**。node-summary.json 含原 TAP SHA-256，包含此前缺 assetId/同意失败零上传/写入不抛错但无读回零发送/原命令 UNKNOWN/重启/切号等回归。不是完整 20 分片计数。

## UI 基线、集成及未验证

最终源 U4 `1708` 未移动，原 key/digest 保留；U5 原 `loadMilestone` 请求 `7776→7778`。两个完整原方法逐字节相同；原理由、u4Dynamic=62/u5Silent=39、ratchet/ceiling 均不变，仅等量替换 U5 key 和该组排序键摘要，未增加豁免。证明见 baseline-proof/source-proof；最终 UI-GATE-0 与原变异自证均实际 exit 0/PASS，原输出见 ui-gate/ui-selftest 与 ui-execution。git diff --check 与 Node 页面语法检查通过；这些静态证据不是 Java/MySQL 或真机证据。

root 应在最终联合源重新生成控件台账；本 delta 不写共享 inventory 或 source fingerprint。无 DDL、数据迁移、依赖新增和生产访问。若需停止受影响新入口，保留旧 pending/原命令查询，不删除意图或放开跨登录发送；不能回退为缺 assetId 或旧 UNKNOWN 换号逻辑。

明确未运行：Java/Maven/JUnit/MySQL/Redis、C Photo 编排真实存储、完整小程序分片、最终 root ledger、微信模拟器/真机。root 36 SQL 的片段成功与后续整体验收由 root 按完整 XML 判定，本报告不拼接为 C 编排通过。
