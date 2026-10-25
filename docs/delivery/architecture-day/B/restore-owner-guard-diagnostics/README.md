# B：e822 原 owner predicate 诊断

公共parent：`e82229ef85e58159078a42956560356074192541`。只修改原restore host、直接控制源码和本说明；原Docker参数/预算/镜像、workflow/canary、C/F/A/G/D及生产文件不改。

## 原故障结论

root已核run40228832838/job121276293085/artifact12612108143：firstFailure是HOST_EXCEPTION_UNKNOWN/OWNER/ValueError，原returnCode=null、stderrCaptured=false，stage=source-create。它不能证明Docker子进程拒绝，也不能确定是哪条owner predicate。原stage在父host第201行设置后一直覆盖预检、create、start、inspect、归属、绑定、port转换及cnf写入，直到第225行才切ready。B本轮仅读取公共源码与root转述原回执，没有重跑或把UNKNOWN改写成根因已修复。

## 必要最小接线

- firstFailure新增固定substage与guardCode。substage沿原执行顺序区分network-absence/create、container-absence/environment/create/cid/start/inspect/ownership、loopback-binding/port-parse/port-range、client-config-write及readiness/probe。
- 原require增加可选的固定guard metadata；有标记的owner拒绝仍是ValueError子类，原inspect absence委托拒绝仍是RuntimeError子类。只认这两种本地typed异常，不从任意异常message推断guardCode。未标识的int/配置ValueError仍UNKNOWN，但其原substage可定位。错误消息不进入公开JSON。
- guardCode精确区分原CID格式、资源名、label、锁定image、network集合、internal属性；原binding的存在/单条/127.0.0.1及原port范围分别标识。逻辑合取拆成同序检查，没有放宽或替换predicate，没有重试/自动改端口/改flags。
- inspect仍调用原require_absent证明精确不存在；失败标INSPECT_ABSENCE_UNPROVEN，绝不将daemon不可达当absence。原inspect JSON解析失败/非唯一行也只明确标记并停止。
- 保留原首个失败、source/run/attempt/scope/cohort及清理合同；后续cleanup不能覆盖substage/guardCode。没有公开raw argv、stdout、env、cnf/private路径、SQL/provider/密钥，也不补造stderr或Docker退出码。原lost-ACK和foreign资源不删除的围栏不变。

## 配套源码与未验证

原18个控制方法及全部断言保留，新增4个（22条SOURCE声明）：原owner各predicate固定code；真实provision源码的CID/label/image/binding/port/config失败路径与原cleanup拒绝；inspect原absence证明/daemon未知/JSON非唯一；普通ValueError消息不能伪造guard或覆盖首因。subprocess/inspect受控double仍只是单元边界，不冒充真实Docker/数据库/资金恢复验证。

只做读取、源编辑、diff/applycheck/hash。所有测试、解析/导入控制、编译、Java/DB、Docker/HTTP、资源/故障和CI均 **NOT_RUN**。原单cohort仍需root定点回读新firstFailure后判明具体失败；完整7项尚未通过，不改完整验收和恢复放行门槛。
