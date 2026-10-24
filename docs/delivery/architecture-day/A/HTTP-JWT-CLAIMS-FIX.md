# A：HTTP安全测试过期JWT构造夹具最小修复

结论：只改 `ApiAiThemeDraftHttpSecurityTest.signedAppToken` 的一个表达式，将传入JJWT的immutable `Map.of(...)` 改为 `new HashMap<>(Map.of(...))`。35项、所有凭证类型、真实生产安全链装配、请求内容、所有认证拒绝/业务零调用断言以及过去60秒的exp均逐字保留。没有生产/依赖/配置或旧共享测试修改。

父候选 `e51a81af4d8604e8fcc8d49fbb4d6232e6fa5080`。root反馈源 `d49c12b820419308236010e0710f96d7bf48c4c9` 的本测试blob与父候选完全相同：`373132e51598ee6ac0dd24778bce508ff9496713`。修复在独立工作树 `/workspace/hengyin-a-http-jwt-claims-fix`、分支 `codex/architecture-day-a-http-jwt-claims-fix`；资金census未提交文档原样保留在另一个工作树，暂停未混入此候选。

## 实际失败与诊断边界

协调者给出的真实回执：run `40062169180` / backend job `120777912885`，35实际执行=31 PASS、0 failure、4 error、0 skip。四个error是 invalidAppCredentialsAreRejectedBeforeAnyBusinessCall 的[3]/[7]/[11]/[15]，在行198调用signedAppToken后、行339设置expiration时UnsupportedOperationException，均在发送HTTP请求前失败。它们不能算认证拒绝通过；其它31通过仅属于原d49源，不是修复后的新结论。

源码对照：四个位置恰是四路的EXPIRED_JWT；其余三类凭证不会执行该helper的setExpiration。父源先setClaims(Map.of(...))，再setExpiration；Map.of不可变，而添加exp需要修改claims。项目固定JJWT 0.9.1，保留签名/解析API不变，改用独占可变副本承载该exp写入。BAD_SIGNATURE分支仍使用原不同key，不删除过期分支或扩大允许结果。

完整原栈读取限制：GitHub连接器fetch_workflow_job_logs返回Transport closed；现有gh读取指定job返回Forbidden，未成功下载完整原日志/XML；已向root请求任一完整异常栈。因此本报告不伪称直接核验了JJWT内部stack frames。源码/反馈位置足以支持此窄fixture修复，但修后真实效果仍须root复跑35确认。

## 实际检查与未跑项

实际Git/Python检查：新文件恰等于父源替换上述唯一表达式；五个参数化方法+一个@Test、全部方法标识及其余所有字节不变；EXPIRED_JWT与原setExpiration(Date(now-60000))仍存在；git diff --check通过。无新import（HashMap原已导入），无新依赖。

SHA256：原测试 `10d14b141fee5463e580cd5f572691c55bc3484e70833b41e7e654312ad88b69`；修后 `670660e442aa73b2f55a6c68bb2c2921a595082845a78217761d8ec4f609cc43`。

本环境Java/JUnit/MockMvc/JWT运行、MySQL/Redis、CI全部NOT_RUN，未启动后端、未dispatch。只有原测试一行delta与本报告；root可只cherry-pick此增量，不回退其已修10参或其它联合源码。无schema/数据迁移或生产回退事项。
