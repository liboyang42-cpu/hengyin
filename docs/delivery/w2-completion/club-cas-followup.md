# F0498 俱乐部资料并发编辑续交付

基线：公共候选 `efba970132d25c3285071e6e1ec909e013b83804`，独立工作树 `w2-club-cas`。本轮补上原报告明确保留的服务端条件写缺口；不将源码、隔离数据库测试记为真实身份或微信业务验收。没有导入私有历史、恢复延期的 22 项、修改共享 action-ledger / UI baseline / README、push、部署或生产操作。

## 合同与最小改动

- 原 HTTP 路由继续为 `POST /api/club/detail`、`POST /api/club/update-mine`，没有另造回读端点。
- `Club.profileVersion` 对应新增的 `club.profile_version BIGINT NOT NULL DEFAULT 1`；详情随现有 Club 对象读取。
- 新客户端提交 `expectedVersion` 与 `editRequestId`，两者必须同时存在。版本为正整数，保存标识为 16–64 位字母、数字、下划线或短横线；旧客户端不传这两项，保留原无条件覆盖行为，并推进资料版本。
- 保留 `updateOwnedClubProfile(Club,Long,Integer):boolean`。新增 `saveOwnedClubProfile(Club,Long,Integer,String):Map<String,Object>` 与 `readOwnedClubProfileReceipt(Club,Long,Integer,String):Map<String,Object>`；最后一项为服务端计算的请求 SHA-256。
- 原事务内主理人/在册管理员权限判断仅提取到 `requireProfileActor`，判据未改；经营配置和加入方式的原 Controller 主理人判断保留。回执回读同样重查当前 actor/club 权限，不允许跨账号、跨俱乐部或已撤销的管理员读取。
- Controller 只将原白名单请求字段构成新的 Club patch，避免将之前读到的成员数、定级、审核状态、经验等无关旧值写回。优惠价未提交时保留锁内当前值；明确提交 `null` 仍沿用旧的清空合同。
- 原行锁内核对版本；真实 `updateClub` SQL 使用 `WHERE id + profile_version + member_id + del_flag=0` 条件写。`updateClub`、加入策略、违规媒体下架和开放设置写均推进版本；成员数变动不推进版本，也不由资料 patch 覆盖。
- 加入策略写与资料条件写仍是一个事务。加入策略先推进版本时，资料 CAS 使用本事务刚产生的版本；结果在全部写入后重新锁读实际版本，不猜 `expectedVersion+1`。

新增迁移：`chengyinhub-admin/sql/migration_20261013_club_profile_edit_version.sql`。必须先迁移、后新后端，再上线依赖版本字段的编辑页；本轮只在任务隔离 fixture 库执行，生产迁移仍需独立授权。

## 原结果回读与前端恢复

回执表 `club_profile_edit_receipt` 的主键为 `(club_id,actor_id,request_id)`，仅存请求摘要、原版本/策略结果和时间，不存资料正文。回执和资料同事务提交；回执失败整笔回滚。同一保存标识但不同 payload 拒绝；相同 payload 回读原结果，即使后续已有其他编辑也不会再次写入。回执读取发生在外部内容审核前，已提交请求不会因审核服务暂不可用变成新的修改；真正新写仍走现有审核链。

编辑页沿用账号 + clubId 的草稿 key 与 v1 记录，增量保存原 payload。在写请求前先持久化保存标识，失败则不提交。丢回包、普通 500、异常状态或缺少可验证回执的 200 都保留待核对原请求，并锁定编辑；“核对上次保存结果”重发完全相同的 payload，读已有回执或完成尚未提交的原条件请求。只有明确 `saveRejected=true` 或 409 才解除未知结果状态；不把 HTTP 200 当成功。

409 保留草稿并读取当前资料。用户可“重读当前资料”，查看差异后主动“载入草稿，继续核对”，再由正常保存提交新版本；没有自动覆盖或自动合并。旧 v1 草稿没有版本时保持可读并要求显式核对。新版页面读不到合法资料版本时拒绝无条件降级写。

原请求在途和待核对期间，原生输入、文本域、开关禁用。媒体上传回调绑定账号、对象、编辑 epoch 和组件生命周期；保存前启动的迟到上传不会替换 pending payload。旧回执到达时若实际表单已有更新，更新仍落在草稿，不清理或关闭；切账号、旧读取回包和卸载回包不操作另一账号草稿或已离开的组件。

## 验证与证据

前端：4 个直接相关文件，**36/36，通过，0 skip**：`club-edit-draft`、`club-ui-true-defects-contract`、`club-edit-deep-link`、`scene-club-edit-dissolved-binding-contract`。其中 helper/实际组件行为 15 项覆盖账号隔离、旧草稿、恢复、CAS 冲突、重读/核对后新版本、丢回包重开、未知业务响应、存储失败、管理员展示字段、迟到上传、卸载回包、新改动保留及输入禁用。日志 `/tmp/w2-club-frontend-tests.log` 为临时任务证据，不提交原始日志。

后端精确测试快照：最终 tree `9e15991e0d8cc932be01b98f49b78f023de34b4c`；仅传输公共后端模块、pom 与已有测试需要的 sql。压缩归档 SHA-256 `2d96443a30b3a3a06756a09b5999d65afc4828931eaf28847080f4b84734bea2`，VPS 独立重读一致；12 个变更后端文件逐一校验，manifest SHA-256 `bab2f43ed13b759d94e65529aa5c1e8a06c0e170ce2c7f1bc0e1ebe69d563872`。没有 Mac bind mount，没有本地 Java/Maven/MySQL/Redis。

指定 runner：`cy-w2-followup-20261013-test`，已核验 ID `099eb30714c0c5d61c9c26e220a1e2ca0be542dde54e3a0a367a7611d758885e`，所有 Docker 调用显式 `chengyin-vps-sandbox`；工作路径 `/w2/club`。全部 Java 操作在 `flock /tmp/w2-java.lock` 中串行执行，Maven 448 MB / surefire 320 MB。只使用协调者的 fresh fixture 环境；连接目标为 runner 内 loopback 任务 relay，MySQL 版本和隔离级别由真实测试读取，不连接生产。首次临时复制的 fixture env 在加载后已删除；复跑通过 Docker CLI 的 Mac-local `--env-file` 注入，不打印凭据。

F0498 与原有相关回归：11 个类，120 个不同用例（Controller 6、真实 CAS MySQL 6、原权限/生命周期/Mapper/经营配置/身份/退款回归 108）。新增 MySQL 用例证明：两编辑者只有一笔成功、Mapper 原子版本/owner 条件、旧请求兼容、无关字段保持、跨账号/俱乐部和撤权拒绝、所有资料写入口推进版本、已提交原结果在后续编辑后仍可读、不同 payload 不可复用标识，以及回执失败时资料/加入策略一起回滚。最终独立核心复跑 **120/120，0 failure/error/skip，BUILD SUCCESS，退出 0**（`/tmp/w2-club-core-final.log`，2026-10-13 19:49:46 UTC）。包含历史 fixture 失败的早期 reactor 没有被写成通过。

完整 r2 tree 为 `22559d2829b9acc2a2b11fb3447a6a0973880f10`，Controller 纠正测试桩后的 r3 tree 为 `7a2129ef58ec6bc23b73ec45a9e0877bd0746fb6`；r2→r3 只有两处 test 文件变化，CAS 生产源码逐文件一致。后续前端存储冲突保护已独立复跑 36/36。

前两次验证发现并修正**测试设置**问题：历史 run41 DDL 已包含 join_policy，移除新增测试里的重复 ALTER；Mockito 默认 Map 返回空 Map，明确 stub 无回执为 null，并用 doThrow 重新配置已有抛错桩；FreeSelfPlay 原本硬编码 root/空密码，现读现有 MysqlIntegrationTestConfig 的 fixture 用户与密码，并补应用其原先遗漏的公开迁移 migration_20260930_registration_first_successful_scan.sql，历史 DDL 未改。没有为通过测试改变授权或应用错误处理。历史 migration / 生产 DDL 快照未改；MerchantIdentity fixture 加版本列，transfer/cancel fixture 在旧 DDL 后应用本轮新增迁移。FreeSelfPlay 的现有 Mapper/历史 DDL 连续漂移已按协调者指示作为**第二个独立 fixture 提交**处理；第一份 CAS 提交不含这个文件。当前检测到的 theme runtime / merchant offer 字段缺失不是 club CAS 失败，完整字段清单与最终复验在后续提交补记。

公开差分 `gitleaks stdin --redact` 零泄漏，没有加忽略规则；`git diff --check` 通过。最终报告入暂存后重扫，正常提交仍执行完整门禁。

## 提交门禁

协调者明确批准任务专用 `/tmp/w2-followup-hooks-club/pre-commit`，只将原 Java 构建位置改到上述已批准 runner、同一 flock，并压缩/传输/校验**暂存 tree**，完整执行 `mvn -q -B -DskipTests -pl chengyinhub-admin -am compile`。原 endpoint/Linux 检查、暂存 JS/JSON 语法、禁止修改历史 migration/生产配置及 DS 门禁均保留；没有改共享 hook/config，没有 `--no-verify`。

原 hook SHA-256 `74a873bf05614e1fcd4acdc6462b2bbd84bb069010ed35c57707832d64e5aa5d`；任务 hook SHA-256 `88b28be6a3ee3740ad936a9f31962bba917f826d42b0f0e754a64cfc2df1ce78`。最终提交结果将在正常门禁完成后补记到交接消息。

## 真实剩余边界

- 微信模拟器前后截图、真实两个账号/两台设备、弱网/进程被杀恢复和业务回读未验，协调者统一负责共享 DevTools；本 worker 未操作共享窗口。
- 生产迁移、新后端部署、微信上传、CI、合并与生产验收均未做。代码需要新增迁移；不能只发前端。
- 无版本旧客户端仍保留原覆盖合同，因而不能承诺所有历史客户端都具备防覆盖。新编辑页与版本客户端的双编辑冲突已有真实服务/数据库验证。
- 持久原结果需要保留回执；本轮不擅定 TTL、删除策略或自动合并政策。账号权限已撤销时仍拒绝回读，不新增绕过权限的恢复通道。
