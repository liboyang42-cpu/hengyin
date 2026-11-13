# 平台城市路线走查：玩法运行时四项缺陷修复（2026-11-13）

分支 `fix/play-runtime-1113c`，基于 origin/master。

## 1. 开局 300 米校验被高级玩法绕过
- 根因（两处）：
  - 客户端 `onArriveTap` 以 `_advancedReadyNodeId === node.nodeId`（“解开本身就是到站证明”）为由跳过定位，首站用高级玩法时开局从不申请定位。
  - 服务端 `PlayBasicCommandApplication.arrive` 的开局闸写成 `hasNodeCoord && !solvedGame && !runStarted`，首站玩法已解开即整段跳过，导致 1066 km 外也落了无坐标进度行。
- 修改：去掉两处对“已解开”的豁免，开局闸只看 `runStarted`（沿用 arrival-start-300m 的 `ArrivalPolicy.startViolation`，未新增重复逻辑）。已开局的后续站仍免定位，已解开玩法的后续站照旧直接收尾。
- 测试：`ApiPlayGpsGeofenceValidationTest` 新增 3 例（首站已解开无定位被拒且不落进度不发奖 / 首站已解开 302 米外被拒 / 已开局后续站已解开免定位通过）并替换原“解开即免定位”一例；`dec-d69-arrival-radius.test.js`、`play-runtime-1113c.test.js` 钉住客户端判据。

## 2. 拍照审核 fallback=pass 被忽略
- 根因：`AdvancedGameRuntimeServiceImpl.submitPhotoCheck` 中 `fallbackPass = !r5 && ...`，按结果分支（outcomeRules）的站完全不认 fallback，降级且次数用完记成 REJECT。
- 修改：r5 站在 `fallback=pass` 且审核降级时直接放行，记为 `verdict=FALLBACK_PASS`（`flagged`、`degraded` 保留；`photoVerified` 仍为 false，奖励封顶、不铸卡，不是 AI 通过）；`AdvancedActionJudgement` 对“降级兜底放行”判为通过，其余 FALLBACK_PASS（真审核未达标被放过）仍判未过。fallback 为 reject/缺省行为不变。
- 测试：`AdvancedGameRuntimeServiceImplTest.r5PhotoAndAlbumUseVerifiedServerEvidence` 的 DEGRADED 例改为期望 PASS + FALLBACK_PASS，新增 DEGRADED_REJECT（无 fallback）仍 FAIL。

## 3. 逐字猜词解开后卡住
- 根因：内嵌在故事流里的逐字猜词，解开后“继续”按钮只在 `!inline` 时渲染，且页面 `kitaction continue` 只处理 `qa`；玩家解开后没有任何入口把本站交去到站收尾（会话由到站 `completeGate` 置为完成），重进时内嵌位置同样没有“继续”。服务端状态与判定本身正确（单字符/数字答案先错后对，新增用例证明可解开）。
- 修改：解开后内嵌/整屏都显示“继续”；内嵌抛 `continue`，整屏仍抛 `close`；`playkit` 转发该事件，页面对 `hangman` 与 `qa` 同样走 `continueStoryGame`。重进时已解开未打卡的站由既有 `_freshStoryGame` 自动接续，并且现在有可点的“继续”兜底。
- 测试：`HangmanGameplayTest`（单数字答案先错后对、单字符整词形状）、`play-runtime-1113c.test.js`（模板、组件行为、页面分派）。

## 4. QA maxTries 0 一次错就失败
- 编辑器/校验口径：`maxTries` 0 = 不限次（`AdvancedGameConfigValidator`“0 表示不限”，编辑器默认 0，judge 里 `allowed > 0` 才收摊）。
- 根因：按结果分支的站 `serverGameplayDecision` 对任何一次答错立即写 FAIL，不看是否还有机会；PICK 在非 r5 路线走另一条链所以能重试。
- 修改：SUBMIT_QA 答错且本题尚未 `finished`（还有机会）时不结算；maxTries 0 永不因答错结束，maxTries>0 用完次数才 FAIL。TYPE/PICK 一致。
- 测试：`r5WrongAnswerUsesOneImmutableServerOutcomeInsteadOfLegacyCompletion` 参数化 maxTries 0/1：0 时连错两次仍 RUNNING 且不写结果，答对完成；1 时一次错即 FAILED。

## 检查结果
- 本地（不跑 Java，仓库约束要求在 VPS/CI）：`node --test` 新增/改动的 JS 用例通过。Java 用例与 UI-GATE-0 等由 PR 检查执行，见下方 PR 状态。
- 未改 `ci/architecture/*`、workflows、`ui-integrity-baseline.js`；未新增 migration。

