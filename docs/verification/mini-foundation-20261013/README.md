# 小程序底层修补：2026-10-13

基于公开仓库快照 `fafde987`，仅包含下面四组独立修补。没有导入旧 Git 历史或私有迁移备份。

| 修补 | 规则与验收 | 本轮证据 | 留下的断点 |
| --- | --- | --- | --- |
| 支付中订单对账 | 每条已尝试订单保留扫描进度；后页查询失败后从断点恢复；支付谓词、限额和渠道规则不变 | 对账测试 2 项通过；原实现新用例失败于游标应为 200、实际为 0 | 未支付订单公平扫描另包；不是实际微信支付验收 |
| 聊天列表旧回包 | 删除、静音成功后旧列表回包不复活旧状态；回滚按会话 ID 定位；重复操作按会话隔离 | Node 28 项通过，包括删除、静音和失败恢复 | 聊天内容增量、已读权限、真实身份和真机另验 |
| 普通草稿保存顺序 | 按提交数组明确保存章顺序和每章站点顺序；创建及重建都验证名字、新 ID、父章归属 | 顺序 2 项及相关草稿、发布规则、块化写入测试共 74 项通过 | Mapper 边界单测；没有数据库回读或完整云端草稿保存证据 |

| 同意记录重复请求 | 同一事件 ID 必须对应原成员、文档版本、场景、范围和事件类型；匹配只返回已有结果，冲突拒绝；原行保持不变 | 服务和接口单测 18 项、真实 MySQL 5 项通过，含两个连接并发重复与冲突 | 未覆盖撤回后发布限制、真实身份 HTTP 或真机 |

## 核对打勾

- [x] 四组源码差分和相关调用范围已核对。
- [x] 相关后端单测 94 项通过；另一次执行的真实 MySQL 测试 5 项通过。两次合计 99 个不同测试，均零失败、错误、跳过。
- [x] 小程序 Node 回归 28 项通过，零失败、跳过。
- [x] 原实现缺陷已复现；草稿测试两次夹具错误单独保留，不计作缺陷复现或通过。
- [x] 暂存快照 VPS 独立编译，源码提交 `0984189`、`55cd557`。
- [ ] GitHub-hosted CI 通过当前提交。
- [ ] 真实身份 HTTP 与异常链回读。
- [ ] 微信开发者工具、真机。
- [ ] 合并、部署、微信上传及线上回读。

源哈希与逐测试类计数见 [source-and-tests.json](source-and-tests.json)。初次 MySQL 测试有 2 项夹具屏障超时；改为 INSERT 前同步，保留真实 SQL 和两个独立连接，再执行全部 5 项通过。旧超时保留，不算业务缺陷或通过。每次源码提交前都使用暂存快照在 VPS 独立编译。

[上一轮 GitHub CI](https://github.com/liboyang42-cpu/hengyin/actions/runs/38365659866) 验证的是 `1a87263`：9 项门禁通过，小程序检查取消，因此整轮未通过；它不能作为新增同意修补的 CI 证据。当前提交仍待 CI。

公开 CI 使用 GitHub 临时运行器；开发测试和数据库使用专用 VPS，不在 VPS 配置 GitHub Actions runner。后续新矩阵的 `max-parallel` 设为 20，不复制测试凑任务数。

## NPC pass expiry follow-up

New text and voice requests require a current paid selfplay pass. The voice path checks again after transcription. Renewal restores access; history export remains available. The shared legacy paid-registration query is unchanged. Activity exploration expiry is a separate pending package.

- [x] Entry negative controls: 2 behavior failures on the old controller (200 instead of 403).
- [x] Post-transcription negative control: 1 behavior failure when its second check is removed.
- [x] 29 controller/mapper unit tests passed; separate real MySQL query test passed, zero skipped.
- [x] Only this test's random schema was cleaned; existing schema set unchanged.
- [x] Staged-source VPS compilation passed for NPC source commit `9c5d903`.
- [ ] GitHub CI for the new NPC source commit. The running 7046676 CI covers the previous four packages.
- [ ] Real authenticated HTTP, real ASR/model, WeChat/device, merge and deployment.

Across the five packages, 129 distinct backend tests passed in separate invocations, plus 28 Node tests. Counts do not imply one combined run or full product acceptance.

## Legacy IM and exploration NPC follow-up

Legacy read locks the current conversation/member and preserves higher read positions. Closed/deleted membership is rejected; fanout does not revive deleted members. Public incremental read-to and client rendering remain separate.

- [x] 2 legacy behavior negatives, 59 units and 5 real MySQL tests passed.
- [x] The original MySQL fixture failure is preserved. Only its closure/leave assertion changed; all 5 reran.
- [x] Exploration NPC SQL denies end equality, past or missing date; a future end restores access. Other kinds and selfplay rules remain. Its 1 expanded SQL case replaces the earlier count.
- [ ] Current source GitHub CI, real club authority, authenticated HTTP, WeChat/device, merge and deployment.

193 distinct backend cases passed across source-specific invocations, plus 28 Node cases. The 7046676 CI was cancelled after 9 other jobs passed; it does not certify these newer changes. No full-item acceptance is inferred.
