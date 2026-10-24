# 后续 pool 身份与清理取证补丁

本片 parent 为 `7d742dc7442ead070cd23fcf9048b054e431a69a`，其 parent 为已执行的 public ccd5。只改既有 smoke 的 pool 分支取证/资源标签与清理、原 pool workflow 上传检查、原专属控制测试及本说明。普通 startup 路径仍执行原配置/原删除命令；无新依赖、job、权限、配置值或业务改动。

## 新运行的合同

- `pool-execution.json`：source SHA/tree、run/attempt、任务名；启动前宿主 JAR 摘要/长度；启动后及最终清理前宿主和容器内 `/application.jar` 的摘要。精确检查容器 ID、任务标签及该路径的只读 bind mount，前后摘要一致才通过。只记录摘要，不额外上传大型 JAR；这仍是运行器/挂载文件取证，不是独立下载 JAR 后的字节核验，更不能与旧 4ef3 制品自动等同。
- Pool 创建的 app/MySQL/Redis/短暂 trace-check 容器与 internal network 仅追加当前任务标签。清理先从成功的 inventory 中识别精确名字，再逐 ID inspect 核对标签/名字，按不可变 ID 删除；拒绝未匹配标签资源，仍继续其他任务自有资源的清理。最终再次独立枚举容器/网络，名字和已知 ID 均不得残留。未成功列举不能当不存在。
- `pool-cleanup.json` 保留原退出码、实际删除 ID、剩余对象和分阶段错误/返回码。清理或身份摘要失败会使原本成功的 pool 运行失败；原本非零错误码继续保留，不被清理错误覆盖。原 application/entry 日志照常保存。没有全局 prune、模糊名称删除或外部资源删除。
- 原探针 `pool-recovery.json` 在外层最终发布时移除未测量的 `profile.connectTimeoutMs`，明确 `connectTimeoutObservation=UNKNOWN_NOT_MEASURED`。不改 `pool_recovery_probe.py`、实际 connectTimeout 配置、5 秒等待阈值或任何原断言。
- 原 workflow 的唯一回执检查追加 source/tree/run/attempt、所有 JAR 摘要、清理结果及未知字段语义校验；缺任一回执失败。失败也保全两个新增小 JSON。无新增构建/测试批次，无新 workflow 派发。

## 实际已执行与未执行

一次定点执行：`python3 -B ci/test_pool_recovery_probe.py`，**12/12 PASS，0 skip，0.090 s**；原 4 项逐项保留，新增 8 项覆盖精确挂载摘要、source/attempt/JAR/容器漂移、标签不符、删除失败、after inventory 失败、损坏回执不妨碍剩余清理、原失败码保留与新清理失败转红、真实 workflow 检查对缺失/冲突回执拒绝。测试执行嵌入协议代码并使用 Git/Docker doubles；shell trap 用 shell 函数替身，未连接 Docker/HTTP/数据库，不是实际资源清理证明。`bash -n` 通过。

后续 root 批准的 hosted 故障运行、实际新 JAR 前后摘要及真实删除后 inventory：**NOT_RUN**。补丁自身的 source SHA 必须由新运行记录，不能借用旧 ccd5 run 的绿色；不补造旧回执。

## 旧 run 的后续证据分层

F 原下载 403 限制继续保留。root 后续直接回读 artifact `12579050590` 并重算 ZIP SHA256 `0c95af72b13821bf0268e56414f0bc3f5b1f75e5112dabc802ec2cdc0835d1aa`，确认 CRC/7 文件。以下是 **root 已独立取证后提供的数值，非 F 此次亲自下载核验**：blocked=20、holderOwnershipCounts=[1]、releaseAcknowledged=true；native=5001 ms、overflow HTTP200/业务500/5.061 s；20 个阻塞请求及 3 个新读均业务200；前后 app PID3340/MySQL2695/Redis2793 一致；UUID/两表 checksum 一致。

这补足了旧 JSON 的运行内容，但旧 run JAR hash 和外层删除后 inventory 仍 UNKNOWN。新脚本不会追溯改变该事实；局部 SQL 池超时/同进程恢复仍不代表完整容量、业务恢复或 Q4/Q8 全部完成。
