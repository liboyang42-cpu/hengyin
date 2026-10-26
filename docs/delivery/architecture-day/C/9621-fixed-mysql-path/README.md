# 固定组合 HTTP：8.0 客户端、原 owned host 路径与 native absence

Parent `9621da223f18cbddfdb7973e613fa406f8afaa54`。本包只含两个 C owned 源文件和静态交付；没有运行证据、原内部运行状态、原日志或凭据。既有执行结果由 root 保管。本包不根据版本源码推定某次运行的全部根因。

## 源码依据与最小接缝

- MySQL `mysql-8.0.45/mysys/my_default.cc` 的 `get_defaults_options` 没有 `--no-login-paths`，`my_default_get_login_file` 先读取 `MYSQL_TEST_LOGIN_FILE`。`mysql.cc` 没有该开关。固定 namespace 8.0 客户端不能使用后续版本才引入的开关；host 客户端由原 runner 提供，不假定与 namespace 客户端同版本，SQL VERSION() 返回的是 server 版本。两处原 SQL metadata 调用统一使用既有 `--defaults-file=/dev/stdin`，host 环境清除 `MYSQL_PWD/MYSQL_HOME` 并固定 login file `/dev/null`；docker exec 也显式覆盖这三个变量，不依赖 host env 传播。密码仍只在 stdin，不增加镜像/驱动/依赖。
- Docker 官方 port-publishing 文档区分 ordinary internal bridge 与 isolated gateway；Moby v28.5.1 `libnetwork/endpoint.go` 的 `!n.internal` 分支才调用 `ProgramExternalConnectivity`。该旧版本源码不能当作当前 runner 的 daemon 实现或某次故障证明。仅声明 `-p` 不是实际发布证明，internal bridge 的 host 可达性也不等于 host loopback published port 已存在；新接线显式要求 relay，不依赖这个版本差异或假定某次未发布。本包不改 internal、host network、daemon、firewall 或权限；改为复用原 `authenticated_redis_recovery_probe._owned_mysql_relay`，不复制 relay/provisioner。
- 原 shared relay 唯一不足是只 bind 临时端口；原 host fresh-schema / cleanup 仍要求127:33080。`relay-owner.review.patch` 仅追加 keyword `loopback_port=0`：原 caller/default0不变；额外只允许33080且必须原 hosted/manual/repository/fixed-class scope。原绑定前/每连接/阶段后 CID/network/IP/PID 校验、socket关闭、worker/listener join 保留。新固定端口允许复用已关闭的 TIME_WAIT，不启 SO_REUSEPORT，不接管占用的 listener，不采用其他地址或随机 fallback。
- Moby v28.5.1 `libnetwork/error.go` 的 `ErrNoSuchNetwork.Error()` 为 `network <original-target> not found`，并实现 `NotFound()`；原只检查 `No such network` 不能承接该原生错误形状。`exact_absence` 现在核单目标、退出1、空或[] stdout、完整且 target 精确相等的 native stderr。daemon/权限错误、其他 target、附加错误行、泛 not-found、成功 exit、对象 stdout 均不算 absence；不声称所有非零都是404。

官方固定来源：https://raw.githubusercontent.com/mysql/mysql-server/mysql-8.0.45/mysys/my_default.cc 、同 tag `client/mysql.cc`；https://docs.docker.com/engine/network/port-publishing/ ；https://raw.githubusercontent.com/moby/moby/v28.5.1/libnetwork/endpoint.go 与 `libnetwork/error.go`。这些是源码参考，不是本包执行验收或某个 runner 的版本证明。

## 完整 caller 与身份链

| 原入口 | 本片实际 owner / 行为 |
| --- | --- |
| workflow 固定组合 Start MySQL | 仍原 image/内部 network/1CPU/1GiB/no-swap/256PIDs/tmpfs768m；只去掉新 class 的声明式 `-p`，旧三类原端口发布不动 |
| `bootstrap-ready` | 原90次/90秒 deadline；先等真实 namespace TCP metadata，再开原 owned relay；双端点 UUID/version/port 全等才 READY。未到 namespace ready 的 host 标记 WAITING，不当 host 成功；没有免验 host 的路径 |
| `mysql_host_path` | `backing_mysql` 原 budget/image/label/network 先核，再核 bridge/subnet/live PID/非 isolated；原 relay 仅把 host127:33080 连到该已核实例3306。raw IP不成为 CLI目标选项 |
| workflow 原 Run original class step | 新固定 class 仅改调 `run-host-path`；它重新核 bootstrap/current CID/原 UUID，固定执行原 shell，原 fresh builder→prepare→原 Java class→finalize→原 schema cleanup 全部处于同一个 relay context。无任意 shell/command/class/URL选择器，原 shell/Java/F consumer源码不改 |
| `mysql_address_proof` | prepare、run前后仍真实双端点 metadata；同一受支持客户端/无 login file 干扰，继续拒绝 CID/UUID/version drift；host33080/namespace3306、原 scope 与 schema 参数不改 |
| 两阶段 budget ABI | 签名/先后次序不变；除原 JAR/source/PID/cgroup/XML/schema/cleanup外，嵌入 bootstrap relay CID/host endpoint/已关闭绑定证明。没有 host binding 不能签 subject budget |
| `cleanup-mysql` | 独立 owner 规则不依赖健康、readiness或业务通过。network owner读取→复核无attached→按原ID删除→原ID absence→原name absence，记录有限 native exit/category 与固定 phase。foreign/daemon/缺证仍 UNKNOWN；原 nonzero 与 firstFailure 保留 |

新的 `combined-http-host-path.json` 只表明原 shell exit 与本次 owned host relay 的关闭结果，明确不签整系统/Q4Q8；依旧由原 public 目录上传。该文件不能替代原 Java/XML/PID/cgroup/JAR/receipt 或两阶段 ABI。relay/报告失败不能把原非零变绿。raw 日志/SQL/异常正文/argv/env/凭据不公开。

## 必须由 root 串行组合的 shared patch

1. `relay-owner.review.patch`：原 auth transport owner 的上述固定端口接缝，原 default0及既有 caller保留。
2. `workflow.review.patch`：仅固定新 class 去掉声明式 published-port，原 class step 通过 `run-host-path` 包住原 shell；else 原默认路径不变。

C没有编辑这两个 shared 实际文件，没有修改 F selector/seed/计数、root summary/ABI caller、任何 Java/SQL/生产文件或原 resource budget。两份 patch 必须与 owned source 一起由 root 组合；新增 relay扩展控制也必须对组合后的实际 owner执行，不能拿未应用 patch 的旧 owner当验收。pre/postimage 与 patch字节/摘要另附。

## 控制 SOURCE / NOT_RUN

原34方法/业务和安全断言保留。唯一过时实质断言是“必须含8.0不支持开关”，已替换为“不含该开关+两端固定login-file+stdin密码+清除覆盖env”，并保留原两端/UUID断言；旧 native cleanup doubles仅跟随真实调用迁移。共新增17方法，合计51控制 SOURCE：客户端兼容/隔离、namespace未就绪不冒充host就绪、relay开启次序、两处metadata、原relay固定port/端点/关闭、live bridge/subnet/PID边界、foreign target、完整原shell次序、错UUID不运行shell、原非零与晚cleanup/写失败、精确network/container absence、坏target/daemon/附加错误、native清理阶段、缺transport ABI拒绝、共享固定port gate与原default0不变。

仅 source读取/差分、Git apply --check / diff --check、SHA256、限本片秘密模式扫描；没有执行测试、parse/import、编译、Java/DB/Docker/HTTP/故障或CI。全部新增控制与完整运行合同 NOT_RUN；不得据本包宣称现成业务用例、完整Q4/Q8或旧失败已验收通过。
