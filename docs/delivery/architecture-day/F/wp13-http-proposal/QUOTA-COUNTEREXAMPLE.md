# 玩家 themeDraft UNKNOWN：源绑定的最小分支反例

结论：addeb 的已发 UNKNOWN 能到达原 receipt 的退款请求；这不是 Redis 已退款、Java 已执行或经济链路验收证明。root 已认领生产修复，本目录不改它。

精确源码路径在 evidence.json：`AiCreatorServiceImpl` 位于 system/business/service/impl，`AiGatewayServiceImpl` 位于 system/business/service/ai/impl；异常、recovery 位于 system/business/service/ai；XML 位于 system/src/main/resources/mapper/business。以下行号源绑定到 addeb。

| 步骤 | 源码事实 | 原 charge/stage |
| --- | --- | --- |
| Creator.reserve `597..628` | `key` 包含原 member/day，receipt 拼原 key + UUID；`prepare:603`；Lua reserve `607..610`；`reserved:624` | mapper insertPrepared `27..31` = PREPARED/PRE_RESERVE；recordReserved `35..38` = RESERVED（stage 仍 PRE_RESERVE），绑定原 generation/used |
| Creator.themeDraft `119..122` | 得到 reservation，明确 `quotaRecovery.stage(reservation.receipt,"INPUT_REVIEW_STARTED")` 再调 gateway | 同一个玩家 receipt 是 RESERVED/INPUT_REVIEW_STARTED |
| Gateway.generate `131..132` | player/theme_draft 使用 `reservation=null`，由 Creator 独立三次池计费 | 并非新的普通 gateway reservation |
| Gateway.stageQuota `392..394` | receipt null 时不更新；`receiptOf:421..428` 对 reservation null 返回 null | generate `196` 的 MODEL_STARTED 对本玩家 receipt 是 no-op；不能误报它已到 MODEL_STARTED |
| Gateway `206..224` | `client.chat` 抛 RuntimeException，catch 抛 typed AiProviderOutcomeUnknownException | 此反例假定模型已发但没有确定结果；不是把所有 exception 都当已发 |
| 异常继承 | AiProviderOutcomeUnknownException extends AiServiceException，构造 `super(Reason.UNKNOWN, message, cause)` | reason UNKNOWN |
| Creator `144..147` 与 finally `155..158` | catch 只令 inputBlocked = (reason==INPUT_BLOCKED)，accepted 未置 true；随后 `!accepted && !inputBlocked` 成立 | 调用 releasePlayerThemeDraftQuota，而非保留 UNKNOWN |
| Creator.release `631..638` | 有 recovery 时 `requireRefund(originalReceipt,"PLAYER_DRAFT_FAILED")`；无 recovery 则按原 key/generation/receipt 执行补偿 Lua | recovery `74..87` 请求 REFUND_DUE；mapper `44..48` 在原 PREPARED/RESERVED、lease/owner 有效时可记录，stage=RESULT_CLASSIFIED |

这里证明“会请求补偿且当前状态允许其成功”的静态路径；lease 丢失/存储错误会阻止或延迟实际退款，不能借此称不会错退。退款使用原 receipt 没有重复退款身份问题，但退款条件仍缺发送结果判据。玩家 receipt 未推进 MODEL_STARTED 是阶段事实；当前 INPUT_REVIEW_STARTED 恢复本来就保守 UNCERTAIN，因此不要求新增 beforeModel callback、接口或阶段来修此窄缺口，也不应删除 stage 或放宽恢复条件。

下列 Python 仅校验原源码锚点并复现这个布尔分支，完全不导入 Java、Maven、Redis/HTTP 客户端。它含正常保留、输入阻断保留、本地未发释放及 generic 输入审核 Reason.UNKNOWN 可退控制；只有 typed providerUnknown 的“不得释放”合同负例在模型中为假。不能当业务回归测试；owner 还需真实 Java 的 stub gateway/原 receipt 测试及现有受控 MySQL/Redis 恢复验证。

从仓库根用 Python 运行本段；五个控制是本地布尔模型，不是五项 Java 测试。

```python
from pathlib import Path
import hashlib
import json

folder = Path('docs/delivery/architecture-day/F/wp13-http-proposal')
evidence = json.loads((folder / 'evidence.json').read_text())
records = {r['path']: r for r in evidence['records']}

def source(suffix):
    matches = [p for p in records if p.endswith(suffix)]
    assert len(matches) == 1, suffix
    path = matches[0]
    data = Path(path).read_bytes()
    assert hashlib.sha256(data).hexdigest() == records[path]['sha256'], path
    return data.decode()

creator = source('/AiCreatorServiceImpl.java')
gateway = source('/AiGatewayServiceImpl.java')
unknown = source('/AiProviderOutcomeUnknownException.java')
recovery = source('/AiQuotaRecoveryService.java')
mapper = source('/AiQuotaReservationMapper.xml')
for needle in [
    'quotaRecovery.prepare(memberId,"theme_draft","player-draft",key,receipt)',
    'quotaRecovery.reserved(receipt,used)',
    'quotaRecovery.stage(reservation.receipt,"INPUT_REVIEW_STARTED")',
    'inputBlocked = failure.getReason() == AiServiceException.Reason.INPUT_BLOCKED;',
    'if (!accepted && !inputBlocked) releasePlayerThemeDraftQuota(reservation);',
    'quotaRecovery.requireRefund(reservation.receipt,"PLAYER_DRAFT_FAILED")',
]:
    assert needle in creator, needle
assert 'String reservation = playerThemeDraft ? null : consumeQuota' in gateway
assert 'modelText = client.chat(systemPrompt, userPrompt);' in gateway
assert 'if(quotaRecovery!=null && receipt!=null) quotaRecovery.stage(receipt,stage);' in gateway
assert 'extends AiServiceException' in unknown
assert 'super(Reason.UNKNOWN, message, cause);' in unknown
assert 'outcome(receipt,"REFUND_DUE",reason)' in recovery
assert "state in ('PREPARED','RESERVED')" in mapper

# Explicit model of the source condition, not execution of Java or storage.
cases = [
    # label, accepted, reason, typed_provider_unknown, actual_release, contract_release
    ('accepted_result', True, 'NONE', False, False, False),
    ('input_blocked', False, 'INPUT_BLOCKED', False, False, False),
    ('proven_pre_send_failure', False, 'UPSTREAM_UNAVAILABLE', False, True, True),
    ('generic_input_reason_unknown', False, 'UNKNOWN', False, True, True),
    ('typed_sent_outcome_unknown', False, 'UNKNOWN', True, True, False),
]
for label, accepted, reason, typed, observed_release, contract_release in cases:
    release = not accepted and not (reason == 'INPUT_BLOCKED')
    assert release == observed_release
    if not typed:
        assert release == contract_release
    print(label + ': model_release=' + str(release))
contract_no_refund_for_sent_unknown = cases[-1][4] == cases[-1][5]
assert contract_no_refund_for_sent_unknown is False
print('NEGATIVE_CONTROL: sent UNKNOWN no-refund contract FAILS in source-bound branch model')
print('SCOPE: source anchors + boolean model only; Java/Redis/provider NOT_RUN')
```

不能把所有 Reason.UNKNOWN 都改成收费或不收费来代替发送分类：普通 AiServiceException 的默认构造也是 UNKNOWN；本地未发、已收到结果/输出拦截、真正已发未知须由 owner 使用实际阶段证据分别处理。此次提案的 HttpUtils typed NOT_SENT 为未来提供可靠证据，不能反向伪造现有请求的发送事实。root 已确认旧测试规定仅完整有效稿计费、输入审核 generic Reason.UNKNOWN 可退；准备最小 typed flag，仅 typed providerUnknown 跳过 release/retain，保留原 receipt 由原 worker 进 UNCERTAIN。该窄修复不新接口、不把所有失败/无效稿改成计费。root 负责独立生产修复，此目录不改其实现。
