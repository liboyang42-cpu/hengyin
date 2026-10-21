# W20.19 CITY 运营诊断与纠错

候选合同 `CITY_OPS_TRACE_V1`。接口标 `candidate`，`transport` 为 `NOT_APPROVED`。本文件不批准生产开通。

## 调用链

1. `POST /business/citygame/operations/trace` 权限 `citygame:ops:trace`
2. `POST /business/citygame/operations/propose` 权限 `citygame:ops:propose`
3. `POST /business/citygame/operations/review` 权限 `citygame:ops:review`，决定只能是 `APPROVE` 或 `REJECT`
4. `POST /business/citygame/operations/apply` 权限 `citygame:ops:apply`
5. `GET /business/citygame/operations/case/{id}`、`/request/{requestId}`、`/store/{merchantId}` 按原请求或本店事实回读

登录身份没有上述权限时返回 `NO_AUTHORITY`，`actions` 为空，不写诊断、请求、审计或本店事实。权限来自现有 `PermissionService`，角色键只从登录用户读取。请求正文里的 `role`、`owner`、`approved` 会被拒绝。

同一 `requestId` 加同一规范正文返回已保存的结果，包括 `UNKNOWN`。正文不同返回 `CONFLICT`，不执行。`UNKNOWN` 不会再次调用命令。审计表只有插入，没有更新和删除。

纠错必须带原因、对象归属、角色、版本和原 `sourceRequestId`。提交人不能审核或执行自己的纠错。商户暂停、停用、恢复只写 `city_game_ops_store_fact` 里该 `merchant_id` 的一行，不写积分费率，也不写另一家店。

## 命令

| 命令 | 结果 |
| --- | --- |
| `PAUSE_MERCHANT` / `DISABLE`（本店） | 本店事实 `PAUSED` 或 `DISABLED` |
| `RESTORE` | 仅把本店已暂停或停用的事实改成 `RESTORED` |
| `REVOKE` | 撤销上一次已执行的纠错；不退回他队资产 |
| `CORRECT` | 只改运营分类和备注 |
| `COMPENSATE` | 记为 `NON_EXECUTABLE`。资产适配不调用 `IPointsAwardService.award` |

## 只读适配

`ExistingCityGameOperationsFacts` 调用已有 `CityBoardMapper.selectCurrentRelease`、`IMmsMerchantService.selectMmsMerchantById`、`IUmsMemberPointsDetailService.selectUmsMemberPointsDetailList`。跑步会话只在 `CityGameOperationsExecutionPolicy.runnerFreshMillis` 被显式配置后调用 `RoamMapper.selectActivePresence`。生产构造不填这个窗口。

没有上游的槽位返回明确缺失，不补履约：

- 证据 `NO_EVIDENCE_SOURCE`
- 任务 `NO_TASK_SOURCE`
- 骰子 `NO_DICE_SOURCE`
- 报价 `NO_QUOTE_SOURCE`
- 归属 `NO_OWNERSHIP_SOURCE`（因此生产适配上的纠错在归属核验前停在 `NO_AUTHORITY`）
- 未配置窗口的时间线 `RUNNER_WINDOW_UNCONFIGURED`

归属一旦由端口核验，可配置的本店纠错可以走完。这不是放行资金。

## 未执行

候选 SQL 不执行。Mapper XML 未对 MySQL 运行。事务中断只在内存映射里模拟了“请求停在 UNKNOWN”。没有生产发布、DDL、支付或发奖。
