# P3-0 决策建议与研究结论（2026-07-27）

> **性质：只读研究，不是上线批准。** 本轮没有修改代码、SQL、生产配置，也没有执行部署、数据库迁移、微信上传或真实支付/提现。
>
> **研究基线：** 2026-07-27 已读取 `github/master`，远端 SHA 为 `d4fe111d2536d1412a472ba780a056d7b41486f4`；当前本地 `HEAD=a37a63c5690769d7cbf02f2393802cab4732b0cc`，工作树有其他未提交改动。仓外 D-12/D-13 方案明确标注自己的代码事实基于旧 SHA `8de640...`，因此本文把它们当作“现有提案”，不当作生产观测或已拍板决策。

## 结论先行

1. **P3-0 当前不能宣称容量。** 仓库审计明确写着没有负载测试，不能给出 QPS、并发用户、P95/P99 或最大容量。先批准一版单城市试运行目标，再用影子环境压测和生产低速只读探针校准。
2. **推荐首发容量假设：** DAU 500–1000、全站峰值 50 rps、报名开抢 10–20 rps 持续 1–2 分钟、日订单峰值 200、漫游同时在线 100。它们是规划输入，不是实测上限。
3. **推荐延迟预算：** 读接口 P95 ≤300 ms / P99 ≤800 ms；写/交易接口 P95 ≤800 ms / P99 ≤2 s；支付回调处理 P95 ≤2 s / P99 ≤5 s；上传 P95 ≤1.5 s / P99 ≤3 s（当前本地盘/同步外部调用仍需单独压测）。业务错误率目标 <0.5%，API 错误率 >1%/5 min 触发告警。
4. **资金告警必须比普通可用性更严格：** 任一退款/提现/结算对账差异 >0、已支付但核销码缺失积压 >0、提现处理中超过 24 h，立即 P0；支付成功率 <90% 且近 1 h 发起数 ≥5，立即核查。
5. **企业微信为主通道；投递失败必须有独立出口。** 最低可行组合是不同群、不同 webhook secret 的第二个机器人，并由 GitHub-hosted runner/云监控等独立执行来源发送；若要覆盖“企业微信平台整体故障”，还需短信/电话等不同供应商，不能把第二个企微机器人说成完全独立。
6. **资金链路的 RPO/RTO 不能从现状推定。** 建议先拍板：资金台账 RPO ≤5 min、RTO ≤30 min；身份/内容 RPO ≤1 h、RTO ≤4 h；现有本地图片盘 RPO ≤24 h、RTO ≤4 h。每个值都要用恢复演练、生产读回和渠道对账证明。
7. **微信“合并/CI/ECS 部署”不等于小程序上线。** 小程序必须另过预检、开发者工具预览/截图、真机关键链路、上传、提交审核和发布；#365/#394 的真机和上传仍是外部闸门。
8. **OSS 有一个应按 P0 安全/正确性处理的源码问题：** `generatePresignedUrl` 计算了十年有效期并生成签名，但返回的是手工拼接的无签名 URL，丢弃了 `signedUrl`。是否因 bucket 公读而实际暴露要从 OSS 控制台/对端读回；源码本身已经足以阻止宣称“预签名安全链路完成”。
9. **快速提现默认关闭是正确基线。** 真实支付与快速提现只能在外部密钥、商户能力、回调、对账、负控和小额真交易均通过后，由明确责任人批准开启；`isReady()` 仅证明 SDK 初始化，不证明渠道端到端可收付。

## 证据口径

| 标记 | 含义 |
|---|---|
| **E** | 仓库代码、CI、工作流或 PR 的可复核事实 |
| **O** | 官方文档/官方工程实践，可用来定义术语或安全边界 |
| **I** | 根据 E/O 推导的风险或解释，不能代替生产观测 |
| **R** | 本文建议的默认值/决策，需产品或运维 owner 拍板 |
| **P** | 仍需从生产、微信/OSS/支付控制台或真机读回的事实 |

## 1. P3-0 性能、容量和告警

### 1.1 现状事实（E）

- 全仓健康度审计的结论是：**没有负载测试，不能给出 QPS、并发用户、P95/P99 或最大容量**（`城瘾项目全仓健康度审计报告_20260724.md:238-242`）。因此任何“能扛 X QPS”的说法，在压测前都只能是猜测。
- 当前 D-13 物理约束是单 ECS、4 vCPU、约 7.3 GiB 内存；Tomcat `max=800`、`accept-count=1000`，Druid `maxActive=20`、`maxWait=60000ms`，Redis pool `max-active=8`；生产无预发，上传仍走本地盘（`城瘾_D13性能容量治理范围与方案_2026-07-22.md:7-16`）。线程数不是容量，排队/慢 SQL/外部依赖必须采样。
- D-13 当前提案建议首月 DAU 500–1000、峰值 50 rps、开抢瞬峰 10–20 rps/1–2 分钟、日订单 200、漫游同时在线 100（`城瘾_D13性能容量治理范围与方案_2026-07-22.md:18-31`）。这是待拍板的目标规模，不是线上数据。
- 资金压测优先级是支付回调、核销、unlockHint、提现；接口断言必须包含响应、业务码、数据库不变量和外部调用桩，不能只看 HTTP 200/P95（`城瘾_D13性能容量治理范围与方案_2026-07-22.md:33-47`）。
- `reveal` 已知客户端行为是累计 50 格立即 flush，未满则 10 秒 flush，`onHide/onUnload` 也会 flush；客户端单批 ≤200，服务端边界 500。100 人在线不能直接换算成 rps，需用实机分布校准（同文档 `:24-31`）。

### 1.2 推荐目标（R，拍板后才是验收线）

| 范围 | 首版推荐预算 | 说明 |
|---|---:|---|
| 读接口 | P95 ≤300 ms；P99 ≤800 ms | 以首屏、列表、详情、未读、地图读为主；按端点拆分，不用全站平均数。 |
| 写/交易接口 | P95 ≤800 ms；P99 ≤2 s | 报名、进度、积分、提现申请；业务状态可异步推进，但不能用“受理”冒充“成功”。 |
| 支付回调处理 | P95 ≤2 s；P99 ≤5 s | 只衡量本地状态落库/应答处理；渠道最终态仍由查询、补偿和对账确认。 |
| 上传 | P95 ≤1.5 s；P99 ≤3 s | 当前本地盘或 OSS 同步链路需分别测；外部内容检查耗时必须计入。 |
| 业务成功率 | ≥99.5%（业务错误 <0.5%） | HTTP 200 但 `AjaxResult.code` 失败必须计入；这是目标，不是当前基线。 |
| HTTP/依赖错误告警 | >1%/5 min | 与业务成功率分开统计，沿用 D-12 候选阈值。 |
| 试运行容量 | 50 rps 目标；100 rps 短时安全系数压测 | 只有在核心不变量、P95/P99、错误率和资源曲线均通过后，才可写入容量结论。 |

> P95/P99 是尾延迟，不应以平均值替代。Google SRE 建议按 SLI/SLO、窗口和聚合方式定义延迟/错误率，并用 P50/P95/P99 观察尾部：[Service Level Objectives](https://sre.google/sre-book/service-level-objectives/)、[Monitoring](https://sre.google/workbook/monitoring/)。

### 1.3 资金与可用性告警（R；D-12 候选值已存在，但尚未接通手机）

| 级别 | 指标与阈值 | 处置/停止条件 |
|---|---|---|
| P0 | 任一退款、提现、结算对账差异 >0 | 立即冻结相关人工操作，按渠道真实态与本地台账逐单对账；未完成对账不得继续放量。 |
| P0 | 已支付但核销码缺失积压 >0 | 查回调、副作用和 `VerificationQrcodeRepairTask`；不能把“支付成功”当作权益已完成。 |
| P0 | 提现处理中 >24 h | 读微信最终态、批次号和本地状态机；超时人工接管，不能自动退款造成双花。 |
| P0 | 支付成功率 <90%，近 1 h 发起 ≥5 | 查商户平台、回调和依赖；低样本不触发比例告警，但保留单笔资金差异告警。 |
| P0 | liveness/readiness 连续 2 次失败 | 分离进程死亡、网络不通和支付依赖未就绪；恢复后必须从生产读回 commit/进程状态。 |
| P0 | 非部署窗口进程重启 >0，或短窗重启风暴 | 保留 `journalctl`/OOM 证据；已知 CI 发布重启不能误报为事故。 |
| P1 | Druid/Tomcat 等待或超时持续 5 min | 先查慢 SQL、连接泄漏、外部调用超时；数值水位由压测校准。 |
| P1 | API HTTP/业务/外部依赖错误率 >1%/5 min | 按 route、错误码和依赖拆分；HTTP 200 不代表业务成功。 |
| P1 | 磁盘 >80%、上传失败 >5%/1 h | 保护资金链路，停止大文件/原图等可降级流量并读回恢复。 |
| P1 | HTTPS/支付材料 <30 天进入排期，<7 天 P0；不匹配立即 P0 | 从证书/微信商户平台读回有效期和 ID，不能只看 SDK health。 |
| P1 | runner offline 或部署漂移 | 从 GitHub/生产两端读回；不能把 workflow 绿当部署完成。 |

D-12 的候选阈值、责任和“每条规则要做阈值触发、webhook 非 2xx/超时、恢复负控”见 `城瘾_D12可观测性范围与方案_2026-07-22.md:52-89`。目前生产未证明 `alarm.webhook` 已配置、投递失败已被观测或手机确实收到（同文档 `:17-31,91-112`）。

### 1.4 告警出口（R）

- 主通道：企业微信机器人，P0 标题带 `[P0]`。
- 独立投递失败出口：第二个企微机器人（不同群、不同 secret），由 GitHub-hosted runner、阿里云云监控或外部拨测执行；不能由同一个应用进程再次调用主 webhook。
- 负控：人为制造主 webhook 非 2xx、超时、DNS/网络失败，确认第二出口真的收到；再恢复主出口，确认 `RECOVERED` 通知。从“调用返回”到“对端产生消息”必须分开验证。
- 边界：第二个企微机器人仍不能覆盖企业微信平台整体故障。若 P0 要覆盖此类故障，另增短信/电话/不同供应商；这项是否本期做是产品/运维决策，不应默认为“两个企微已完全独立”。

## 2. RPO/RTO 与压测环境

### 2.1 术语和当前缺口

RPO 是故障时可接受的数据丢失窗口，RTO 是从中断到恢复服务的最长时间；AWS、Google Cloud、Azure 都强调按业务流分别定义并通过恢复演练验证，而不是全系统共用一个数字：[AWS DR FAQs](https://aws.amazon.com/disaster-recovery/faqs/)、[Google Cloud DR](https://docs.cloud.google.com/architecture/disaster-recovery?hl=en)、[Azure business continuity](https://learn.microsoft.com/en-us/azure/reliability/concept-business-continuity-high-availability-disaster-recovery)。

仓库/审计尚未证明生产备份、恢复演练和 RPO/RTO；D-13 只确认“无预发、资金写链路不得直压生产”（`城瘾_D13性能容量治理范围与方案_2026-07-22.md:49-56`）。因此下表是 **R，不是 P**：

| 业务流 | 推荐目标（R） | 必须证明的事实 |
|---|---:|---|
| 资金台账、支付回调状态、提现状态 | RPO ≤5 min；RTO ≤30 min | 恢复数据库后金额/状态不丢；对微信支付/转账最终态重查并对账；“已受理但未知”不能因恢复被重复扣款或重复出金。 |
| 登录、会员、主题/活动内容 | RPO ≤1 h；RTO ≤4 h | 备份可恢复、唯一约束和权限边界保留；恢复后关键读写 smoke 通过。 |
| 当前本地图片/附件盘 | RPO ≤24 h；RTO ≤4 h | 现有 `/www/wwwroot/uploadPath` 盘备份可读；若迁移 OSS+CDN，可再拍板收紧到 RPO ≤1 h/RTO ≤2 h。 |

### 2.2 压测环境和窗口（R）

1. **本机 Docker**（jar + MySQL 8 + Redis）只做应用层摸底；**同规格临时 ECS 影子环境**校准机器层。使用脱敏 schema/seed、测试前缀、可重复清理脚本。
2. 测试 profile 必须 `wx.pay.enabled=false`，或使用隔离测试商户；出网默认拒绝。不得复用生产 API key、回调 URL、真实用户/订单。真实支付/提现不进入压测。
3. 用支付 provider mock 注入“成功、明确失败、超时/结果未知、重复回调、部分副作用失败”，并断言订单、余额、权益、核销码和补偿最终收敛。
4. 纯读生产探针只在低峰、≤10 rps、明确到期时间的窗口运行；不能一键静默全部告警。`ci/perf/` 属 CI/CD 控制面，按分类不会触发业务部署，但仍须 code review 和可复现报告。
5. **建议窗口（待 owner 批准）：** Asia/Taipei 工作日 02:00–05:00；先 30 分钟 canary，再 2 小时混合场景，保留 30 分钟观察/恢复。若当日有发布、迁移、商户活动或值班人不可用，自动取消；窗口、联系人、回滚动作写入变更单。
6. 报告必须同时给出速率→P50/P95/P99、HTTP/业务错误、CPU/内存/GC、Druid/Redis/Tomcat 等待、MySQL 锁/慢 SQL、磁盘/带宽、非部署重启和数据库不变量。D-13 明确要求按目标规模 ×2 安全系数逐级加压，不能只报一个“最大 QPS”（`城瘾_D13性能容量治理范围与方案_2026-07-22.md:58-70`）。

## 3. 微信小程序发布边界与真机验收

### 3.1 五道闸门

| 闸门 | 可证明什么 | 不能证明什么 |
|---|---|---|
| 代码/PR/CI | 代码进入分支、静态检查和单测通过 | 没有微信构建产物、视觉或真实设备行为 |
| DevTools 预览/模拟器截图/automator | 页面路由、状态、截图和脚本级行为 | 不等于不同机型/网络/权限下的真机结果 |
| 真机 smoke | 登录、支付/核销、上传、定位、错误恢复等关键流可用 | 不等于已上传线上版本 |
| 微信开发者工具“上传” | 微信后台出现开发/体验版本 | 不等于审核通过或用户可见 |
| 提交审核→审核通过→发布 | 线上版本对用户可见 | 不等于后端迁移、真实资金对账或所有角色验收完成 |

仓库流程明确：合并后后端部署与小程序上传是两条链路；小程序改动对 ECS 是 no-op，需另走 `preflight-release.js` 和微信上传（`docs/CICD工作流说明_GitHub-first.md:61-90`）。发版 checklist 又要求隐私接口、后端/迁移、支付/核销真机冒烟、版本号和“上传→审核→发布”顺序（`chengyinhub-xcx/docs/发版checklist.md:1-31`）。`preflight-release.js` 只检查生产 HTTPS、明文地址、console 残留和主包页数等机械项（`chengyinhub-xcx/scripts/preflight-release.js:1-4,32-99`），**没有**视觉或真机证明。

官方操作入口（需在已登录的微信开发者工具/公众平台中读回结果）：[开发者工具 CLI](https://developers.weixin.qq.com/miniprogram/dev/devtools/cli.html)、[预览](https://developers.weixin.qq.com/miniprogram/dev/devtools/preview.html)、[上传](https://developers.weixin.qq.com/miniprogram/dev/devtools/upload.html)。这些链接是操作入口，不替代本仓库的截图、真机和后台版本号证据。

### 3.2 发布停止条件

- 预检 BLOCK、主包体积/隐私声明不通过：停止上传。
- 前端依赖的新接口/字段未完成 jar 部署和迁移读回：停止体验版/线上发布。
- 支付/核销真机 smoke 未通过：停止涉及资金或入场的发布。
- 只有合并、CI 绿、ECS 部署或开发者工具本地预览，没有微信后台上传版本号和真机截图：只能称“候选”，不能称“已上线”。

## 4. OSS 上传与预签名 URL

### 4.1 当前代码证据（E）

- `/api/common/uploadOSS` 接受 `file`，调用 `AppUploadService`，返回 `fileName` 和 `url`（`chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiCommonController.java:95-112`）。小程序当前用 Bearer + `wx.uploadFile`，不是把 AK/SK 放到客户端（`chengyinhub-xcx/utils/transport/upload-client.js:1-81`）。
- 上传前已有严格扩展名、文件大小、图片策略、元数据清理和微信内容安全检查（`chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/AppUploadService.java:141-221`）。对象键使用随机 UUID 和已验证扩展名，不带原始文件名（`FileOssUploadUtils.java:107-114`）。
- **关键问题：** `generatePresignedUrl` 设置了 10 年有效期（`FileOssUploadUtils.java:122-128`），调用 OSS SDK 生成 `signedUrl`（`:130-136`），但返回手工拼接的 `https://bucket.endpoint/object`，真正的 `signedUrl.toString()` 被注释掉（`:138-140`）。因此 API 返回值没有 URL 签名参数；实际是否可读要再查 bucket ACL/CDN，但不能据此宣称“预签名已生效”。
- 现有安全测试覆盖恶意/多态文件和元数据清理，但没有断言返回 URL 必须含有效签名、method、过期时间或私有 bucket 访问失败；测试绿不能关闭该风险。

### 4.2 推荐安全设计（R）

1. bucket 默认私有；下载/预览只返回 SDK 真正生成的签名 URL，严禁手工拼接无签名 URL。下载授权先做用户、业务对象和 owner/scope 校验。
2. 优先 OSS V4 签名；普通预览 URL 建议 TTL 5–15 分钟、最长不超过业务需要，上传 presign 建议 ≤15 分钟且绑定 method、object key 前缀、Content-Type 和大小。阿里云文档说明 V4 更安全、V1 URL 分享有效期最长 7 天，STS 临时凭证最长 12 小时；以控制台/SDK 版本实际限制为准：[预签名下载](https://help.aliyun.com/en/oss/developer-reference/download-using-a-presigned-url)、[对象 URL 与签名](https://help.aliyun.com/zh/oss/user-guide/how-to-obtain-the-url-of-a-single-object-or-the-urls-of-multiple-objects/)。
3. 当前“后端接收 `wx.uploadFile`”模式可以先保留；若未来改为小程序直传，必须由后端签发临时凭证/策略，客户端永不接触 AK/SK，并限制单次 key、大小、类型和过期时间。阿里云直传说明见[使用预签名 URL 上传](https://help.aliyun.com/zh/oss/user-guide/upload-files-using-presigned-urls)。
4. 图片继续做 MIME/魔数/像素/比例/元数据清理和内容安全检查；下载侧加审计、限速和异常 URL 监测。若资产需公网访问，用 CDN 私有回源/鉴权，不把整个 bucket 改成 public-read。
5. 验收必须有负控：篡改签名、过期、改 method、改 object key、无权限用户访问均应失败；再从 OSS 对端读回 ACL、对象 HEAD 和签名访问结果。不要只读回刚写入的 URL 字符串。

## 5. 真实支付与快速提现开关

### 5.1 当前代码证据（E）

- 快速提现由 `withdrawal.fast-withdraw.enabled` 控制，默认 `false`；关闭时接口直接拒绝，开启前置条件写明需开通“商家转账到零钱”和证书配置（`ApiWalletController.java:34-40,56-70`）。
- 提现服务先落处理中单、生成确定性 `CW{id}/CD{id}`、扣余额；渠道“已受理”保持处理中，超时/未知结果也保持处理中，只有查询确认终态才成功或退款；CAS 防止重复退款（`CashWithdrawServiceImpl.java:83-158,184-259`）。这是较好的资金状态机基础，但不等于渠道/商户已实测。
- `wx.pay.enabled` 在代码默认值是 `false`（`WxPayConfig.java:26-29`），当前仓库 `application.yml` 却写为 `true` 并含商户号、证书路径和回调地址（`application.yml:206-217`）。这只是仓库配置事实，**不是**生产密钥可用、微信商户能力已开通或真实交易成功的证据。
- `WechatPayServiceImpl.isReady()` 只检查开关、SDK service 和 notification parser 非空（`WechatPayServiceImpl.java:88-135`）；它不能证明商户平台配置、IP 白名单、回调可达、退款/转账额度和对账链路完整。

微信官方“商家转账到零钱”文档要求商户开通能力并满足安全控制；转账存在收款人名单/实名和转账关闭窗口等产品约束，且“受理”与最终到账需区分：[发起转账](https://pay.wechatpay.cn/doc/v3/merchant/4012065126)、[产品介绍](https://pay.wechatpay.cn/doc/v3/merchant/4012064660)。

### 5.2 推荐开关策略（R）

| 环境/动作 | 推荐默认 | 必须通过的闸门 |
|---|---|---|
| 本地、CI、影子压测 | `wx.pay.enabled=false`、`withdrawal.fast-withdraw.enabled=false` | provider mock 覆盖成功/失败/未知/重复回调；出网拒绝；无真实资金。 |
| 生产支付收款 | 外部配置显式开启，仓库默认关闭 | 私钥/APIv3 key/平台公钥可读且权限正确；回调 HTTPS 可达；小额真支付→退款→本地台账/商户平台对账。 |
| 生产快速提现 | 继续关闭，单独批准开启 | 商户转账能力、IP/实名/收款人限制、查询任务、退款和告警均已演练；单测试账号小额转账并读回最终态。 |
| 任何配置变更 | 两人复核/变更单 | 记录操作者、时间、旧/新开关、证据链接；异常立即关闸但保留处理中单查询/对账。 |

不要用真实支付压测吞吐；压测只能在隔离商户或 mock provider 中进行。`isReady()` 通过也不能绕过商户平台、渠道最终态和资金对账闸门。

## 6. #363 / #365 / #394 的产品表述边界

以下只给出**不夸大事实的候选 wording**，不替产品负责人创造用户承诺；产品文案、验收范围和发布日期仍需 owner 批准。

| PR | 代码/PR 可证明的事实 | 可用的保守表述 | 明确不能说 | 上线前外部闸门 |
|---|---|---|---|---|
| [#363](https://github.com/liboyang42-cpu/chengyin/pull/363) | OPEN；主题模板 P0 字段/元数据落库和 `/api/topic/list` 来源字段白名单修复；含迁移，作者声明未在生产执行；seed 标为 EXPERIMENTAL/evidence NONE。 | “主题模板字段与来源展示修复已进入待合并候选；迁移和内容证据待核验。” | 不能说已落库、已部署、14 组内容已被用户验证，不能把实验 seed 当正式内容。 | 迁移预检/备份/执行/读回 → 合并 → 后端部署 commit 读回 → 角色归属和真实页面 smoke。 |
| [#365](https://github.com/liboyang42-cpu/chengyin/pull/365) | MERGED；小程序主题模板详情阅读性收口；PR 自述 ECS skip、需 DevTools 上传，截图证据修复后尚未新鲜验证；city-instance domain 未完整解决。 | “主题模板详情阅读性改动已合并，等待微信上传与真机验收。” | 不能说已发布、视觉已最终验收、城市实例已闭环。 | 新鲜 DevTools 截图/automator → 真机关键流 → 微信上传/版本号 → 审核/发布。 |
| [#394](https://github.com/liboyang42-cpu/chengyin/pull/394) | OPEN；探索护照成果信息/状态四文件和契约测试；目标测试通过，错误态截图无效，分享/保存卡明确是后续范围。 | “探索护照成果信息与状态已完成分支候选，等待 PR、真机和发布闸门。” | 不能说用户已看到、错误态已验收、分享/保存卡已交付。 | PR/CI → fresh-port DevTools 错误态截图 → 真机 loading/error/ready/ARIA smoke → 上传/发布。 |

仓库自己的 mini-program engineering review 也把包体积、DevTools 和真机列为未闭环项；因此测试数和 CI 绿只能证明代码/契约，不是微信线上发布证据。

## 7. 决策矩阵（请 owner 逐行拍板）

| 决策 | 当前证据状态 | 推荐默认 | 责任人与停止条件 |
|---|---|---|---|
| 首发规模 | D-13 提案，未实测 | DAU 500–1000 / 峰值 50 rps / 瞬峰 10–20 rps / 日单 200 / 漫游 100 | 产品 owner；未批准前不把数字写成容量承诺。 |
| P95/P99 | 无压测，DEC-04 未定 | 读 300/800 ms；写 800/2000 ms；回调 2000/5000 ms；上传 1500/3000 ms | 技术 owner；影子压测未通过或资源/不变量异常则冻结放量。 |
| 普通错误率 | 无持续指标 | 业务错误 <0.5% 目标；>1%/5 min 告警 | 值班 owner；必须有业务码与 HTTP 双口径。 |
| 资金告警 | D-12 候选，手机未证明 | 差异/核销积压/提现 >24 h P0；成功率 <90% 且 n≥5 P0 | 资金 owner；对账不闭环不得开启真实提现。 |
| 主/独立告警 | 主企微已选，投递未读回 | 主企微 + 第二群/secret + 外部执行源；平台级故障另评估短信/电话 | 运维 owner；非 2xx/超时/恢复负控不通过则不能宣称告警接通。 |
| RPO/RTO | 无备份/恢复证据 | 资金 5m/30m；身份内容 1h/4h；本地图片 24h/4h | 运维/资金 owner；恢复演练和渠道对账未达标则保持 FAIL。 |
| 压测环境/窗口 | 无预发 | Docker 摸底 + 同规格 ECS；资金禁真；拟 02:00–05:00 Asia/Taipei | 变更 owner；无联系人、回滚和观察窗口即取消。 |
| 微信发布 | CI/后端与微信链路独立 | 预检→预览截图→真机→上传→审核→发布 | 小程序 owner；缺任一外部证据只能称候选。 |
| OSS URL | 源码丢弃签名，ACL 未读回 | 私有 bucket + 实际短 TTL V4 URL + 授权校验 | 后端/安全 owner；签名、过期、method、ACL 负控未通过不得上线。 |
| 真实支付/快速提现 | 仓库 enabled 与生产事实分离；提现默认关闭 | 外部 secret、渠道能力、回调、对账、真交易全通过后显式开启 | 资金 owner；任一门槛缺失保持关闭。 |
| PR 产品 wording | #363/#394 OPEN；#365 MERGED 但外部证据未齐 | 使用第 6 节保守 wording | 产品 owner；禁止把 merge/CI/测试数改写成已发布或用户验证。 |

## 8. 最小执行顺序

1. 产品/运维先拍板首发规模、P95/P99、业务错误率、RPO/RTO 和压测窗口。
2. 先补 D-12 告警出口和负控，再建影子环境；告警自身失败必须可见。
3. 在影子环境跑资金 mock、读写混合和 `reveal` 三种 flush 场景；形成容量曲线，不先调线程池“碰运气”。
4. 对 OSS 先做签名/TTL/ACL 负控和对端读回，再决定是否迁 CDN；当前源码问题未修前不宣称预签名安全完成。
5. 对真实支付/提现做小额、单测试账号、可对账的端到端验证；通过后仍需单独审批开关。
6. 小程序按预检→截图→真机→上传→审核→发布逐闸留证；#365/#394 在缺真机/上传证据时保持“候选” wording。

## 来源索引

### 仓库与项目文档

- `/Users/arlan/Documents/城瘾/文档整理_20260711/04-未开始/城瘾_D13性能容量治理范围与方案_2026-07-22.md:1-16,18-31,33-70,85-113`
- `/Users/arlan/Documents/城瘾/文档整理_20260711/04-未开始/城瘾_D12可观测性范围与方案_2026-07-22.md:9-31,33-50,52-89,91-119`
- `/Users/arlan/Documents/城瘾/文档整理_20260711/04-未开始/城瘾项目全仓健康度审计报告_20260724.md:238-242,337-350,380-390,538-546`
- `docs/CICD工作流说明_GitHub-first.md:61-100`
- `ci/classify-runtime.sh:28-50`
- `chengyinhub-xcx/AGENTS.md:12-29`
- `chengyinhub-xcx/docs/发版checklist.md:1-31`
- `chengyinhub-xcx/scripts/preflight-release.js:1-4,32-99`

### 仓库代码

- `chengyinhub-common/src/main/java/com/chengyinhub/common/utils/file/FileOssUploadUtils.java:78-114,122-167`
- `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiCommonController.java:95-112`
- `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/AppUploadService.java:37-87,141-221`
- `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiWalletController.java:34-70`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CashWithdrawServiceImpl.java:83-158,184-259`
- `chengyinhub-common/src/main/java/com/chengyinhub/common/config/WxPayConfig.java:26-68,134-165`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/WechatPayServiceImpl.java:88-135`
- `chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/RegistrationCheckoutServiceImpl.java:55-128`
- `chengyinhub-admin/src/main/resources/application.yml:206-217`
- `chengyinhub-xcx/utils/transport/upload-client.js:1-81`

### 官方资料

- Google SRE：[Service Level Objectives](https://sre.google/sre-book/service-level-objectives/)、[Monitoring](https://sre.google/workbook/monitoring/)、[Error budget policy](https://sre.google/workbook/error-budget-policy/)
- 灾备定义：[AWS DR FAQs](https://aws.amazon.com/disaster-recovery/faqs/)、[Google Cloud DR](https://docs.cloud.google.com/architecture/disaster-recovery?hl=en)、[Azure business continuity](https://learn.microsoft.com/en-us/azure/reliability/concept-business-continuity-high-availability-disaster-recovery)
- 阿里云 OSS：[预签名下载](https://help.aliyun.com/en/oss/developer-reference/download-using-a-presigned-url)、[对象 URL 与签名](https://help.aliyun.com/zh/oss/user-guide/how-to-obtain-the-url-of-a-single-object-or-the-urls-of-multiple-objects/)、[预签名上传](https://help.aliyun.com/zh/oss/user-guide/upload-files-using-presigned-urls)
- 微信支付：[发起商家转账到零钱](https://pay.wechatpay.cn/doc/v3/merchant/4012065126)、[产品介绍](https://pay.wechatpay.cn/doc/v3/merchant/4012064660)
- 微信开发者工具入口：[CLI](https://developers.weixin.qq.com/miniprogram/dev/devtools/cli.html)、[预览](https://developers.weixin.qq.com/miniprogram/dev/devtools/preview.html)、[上传](https://developers.weixin.qq.com/miniprogram/dev/devtools/upload.html)

