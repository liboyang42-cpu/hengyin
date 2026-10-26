# controlled-combination MySQL 早失败与精确清理 SOURCE

Parent：`95c153fb763ce332adaad99e6a096abe48b9a466`。只修改预算 owner 和其直接控制源码；共享 workflow / root fixture 只交 review.patch。Java namespace probe、业务 Java、selector、HTTP 计数、summary、旧 scope、镜像与预算均未修改。

## 已知事实与限制

原 run40252082564/job121345993050 在 MySQL readiness 失败，尚未进入业务执行。旧证据缺少可信 State、stderr 与独立 absence，根因和清理结果仍 UNKNOWN。本补丁不能追溯修复该次运行，也不认定 OOM、磁盘或网络故障。

已只读核对 docker-library/mysql `7a8f81aad11e871baf1dbc755dbff1652e13cba5` 的 8.0.45 源码：Dockerfile.oracle 指向该版本，entrypoint 临时服务使用 `--skip-networking`，之后停止临时服务再 exec 最终 mysqld。因此 socket/临时 server 日志不能证明双 TCP endpoint 已就绪。这是版本源码参考，不是旧运行或 pinned OCI 内文件的实测证明；未来运行另采实际 entrypoint SHA。

官方源码 SHA256：Dockerfile.oracle `a7cbb1f292d8382918fba7f6a05751b31b7e2b02fc332513e0ef4f0689baa5ed`；docker-entrypoint.sh `deb978cfaf04f14f3afce6a6cf835f8a5c611fd90108c796e11d9d080a29d2ca`。

## 实际调用与收据

1. 固定新 scope 的原 Start MySQL step 调 `bootstrap-init`，在创建资源前写 `combined-http-bootstrap.json`。ERR/INT/TERM/HUP 通过 `bootstrap-failure EXIT` 保存原非零退出；不覆盖更早非零首因。CID 先 export，再写 GITHUB_ENV，失败捕获可保存实际身份。
2. 原镜像、1CPU / 1GiB / no-swap / 256PIDs / tmpfs768m 不变。`bootstrap-ready` 在最多 90 次且 90 秒 deadline 内核 host127:33080 与同 MySQL CID 内127:3306 的真实 TCP UUID/version/port；密码仅 stdin，stderr 私有。临时 socket 或 SELECT1 不能签双端点身份。
3. 首因先落盘，再取独立 owner 已核的有限 State、OOM、exit、预算、端点、cgroup、磁盘计数；raw logs/client stderr 仅原 target 子目录 `combined-mysql-private`，0700/0600，不进 public artifact。public 仅有限分类、MY 数字码、摘要与有限 marker，不输出异常正文、SQL、argv、环境、密码或 raw health/log。
4. `prepare` 重新实查双端点，并与 bootstrap/source/run/attempt/class/CID 绑定；原 `require_subject_budget` / `require_final_budget` 签名及先内层后外层顺序不变。新增嵌入 bootstrap 证明，不借无业务结果的资源收据通过两段 ABI。
5. `cleanup-mysql` 不再要求业务 gate、healthy、Running、原预算合格或仍有 live-IP。删除前仍逐一核实际 CID/name/pinned Config.Image/image ID 形状/标签/声明 network，原对象变化拒绝；先封存诊断，再复核、精确删除、以 CID 和 name 独立核 absence。network 另核实际 ID/name/标签且必须无附着对象；不自动删除未知对象或按 name 收养新 CID。daemon 错误不算 absence。
6. 日志/收据写失败不能阻止独立已证 owner 的回收；证据写失败最终仍非零。缺实际 CID 只可核预期 name 已不存在；有同源旧收据的实际 ID 可补丢失 GITHUB_ENV，但必须再用 Docker 原生 owner 读核。缺证始终 UNKNOWN。原 bootstrap 非零与 firstFailure 保存在清理收据，资源清理成功不代表业务 PASS。

public 新文件：`combined-http-bootstrap.json`；原 `combined-http-storage-cleanup.json` 与 `combined-http-budget.json` 保留并增加必要关联。root 负责现成 public artifact 上传；不上传 private 子目录。

## 精确 shared 接线（root 唯一应用）

`bootstrap.review.patch` 仅改原 workflow 新固定 class 的初始化、捕获和 readiness；原默认三类 readiness 分支逐字保留，cold-cache、业务 selector、清理调用与 artifact 步骤不变。

`caller-fixture.review.patch` 只给原 `ci/test_controlled_combination_entry.py` 工厂增加一行：它修改顶层 source/run/attempt/class 时同步其模拟 bootstrap 身份。无新成功 mock 或断言删除；root 同文件新增 zero-receipt 控制不由 C 覆盖。

shared 后像与 patch 字节/hash 见 `shared-postimages.sha256` / `postimages.sha256`。两份 review.patch 均仅做了 Git apply --check，没有实际应用到共享文件。

## 控制源码及执行状态

原 18 个 test 方法与断言保留；只补原 positive `subject_report` 的新真实证明字段。新增 16 个直接控制 SOURCE：stopped/OOM/预算不符仍 own-cleanup、foreign 拒绝、写失败仍回收但非绿、私有诊断失败不拦回收、附着未知对象不删网、缺 CID 不收养、network name/ID 边界、daemon 非 absence、脱敏分类、safe State、双 TCP stdin、错 UUID 拒绝、超过原 deadline 拒绝、未知 bootstrap 不可入场、首因先于私有取证、丢失 ENV 的已核 ID 恢复。总计 34 方法 SOURCE，不是 34 PASS。

实际仅 Git diff --check、两份 Git apply --check、文本读取、差分、SHA256 与限本片秘密模式扫描。没有执行 Python/Node 控制、parse/import、Java、Maven、Docker、DB、HTTP、故障或 CI，全部 NOT_RUN。旧运行真实启动原因、新 readiness/cleanup、原业务 workload、资源预算与两个 ABI 仍由 root 同源实际验收。
