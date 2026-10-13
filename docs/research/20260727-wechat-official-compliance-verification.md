# 微信官方小程序发版与合规核验

> 核验日期：2026-07-27（Asia/Taipei）  
> 范围：只读核对微信官方文档与当前仓库的小程序发版清单/预检脚本；不改业务代码，不把上传、审核、发布或真机体验混成同一个事实。  
> 证据来源：仅 `developers.weixin.qq.com`。搜索接口未返回结果、网页工具直接打开出现内部错误；改用 HTTPS 直连官方页面抓取，以下列出的页面均返回 HTTP 200。没有使用二手文章或博客。

## 结论先行

1. `cli preview` 是“打包并生成二维码供手机体验”的工具动作；`cli upload` 是“把代码上传到开发版本”的工具动作。CLI 返回 0 只代表命令正常结束，不能证明体验版已选定、审核已通过或线上版本已发布。
2. 微信官方发布链路是“预览 → 上传代码 → 提交审核 → 发布”。体验版是从开发版本中选取的一份内测版本；审核和发布仍需在小程序后台完成。当前 checklist 的三件套顺序与官方链路一致，但必须保留每个环节的独立回执/读回。
3. 隐私合规至少有两层：后台《小程序用户隐私保护指引》要覆盖实际使用的个人信息/接口；用户同意同步后，才能调用已声明的隐私接口或组件。`app.json` 的 `requiredPrivateInfos` 只是地理位置接口声明，不等于后台隐私指引或用户同意已经完成。
4. 官方分包上限是：所有分包合计不超过 30M（服务商代开发 20M），单个主包或分包不超过 2M。仓库当前的 `<1.8M` 是更严格的内部目标，但 `preflight-release.js` 目前没有计算字节大小，只检查主包页数并输出分包数量。
5. 官方真机预览/真机调试能证明某个被打包上传的版本在指定手机/网络/微信环境中的观察结果；不能单独证明后台审核、线上发布、生产后端/数据库或所有真实角色路径已经可用。后半句是发布证据边界的推论，不是微信文档声称的能力。

## 官方来源与证据边界

### 1. 开发者工具 CLI：preview / upload

来源：[命令行 V2](https://developers.weixin.qq.com/miniprogram/dev/devtools/cli.html)

官方页面明确说明：

- CLI/HTTP 接口可被外部调用来登录、预览、上传；使用 CLI 前，需要在开发者工具“设置 → 安全设置”开启服务端口。
- CLI 返回码为 `0` 表示正常，`-1` 表示错误。
- `cli preview --project <path>` 可生成预览二维码；可用 `--qr-output` 输出二维码，用 `--info-output` 输出预览额外信息（包括代码包大小等）；还可用 `--compile-condition` 指定编译条件。
- `cli upload --project <path> -v <version> -d <desc>` 需要工具处于登录状态；上传需要项目根目录、版本号和可选版本备注；`--info-output` 可输出代码包/分包大小等信息。

能证明：

- `preview` 成功：工具完成了当前项目的一次预览打包并产生二维码/预览信息；拿 `info-output` 还能把该次打包的包体信息保存为证据。
- `upload` 成功：工具接受了登录态、项目、版本号和备注，并执行了上传动作；结合后台“版本管理 → 开发版本”读回，才能证明该代码在开发版本列表中可见。

不能证明：

- CLI 返回 0 本身不证明体验版已选择，不证明代码已提交审核、审核已通过或已发布线上。
- `preview`/`upload` 的包体信息不等于线上版本读回，也不等于生产后端、数据库迁移、支付/核销或真实角色路径已验证。
- CLI 文档没有替项目完成微信后台的隐私指引、审核表单、发布/灰度选择或版本回退。

与仓库对应：

- `chengyinhub-xcx/docs/发版checklist.md:3-4` 把“上传 → 提交审核 → 发布”列为发版前流程，并要求运行 `preflight-release.js`。
- `chengyinhub-xcx/docs/发版checklist.md:22-26` 的第 3 步与官方链路一致；实际执行时应额外保存 CLI 的二维码/`info-output`、后台开发版本读回和审核/发布状态，而不是只保存终端退出码。

### 2. 体验版、审核版、线上版与发布顺序

来源：[小程序协同工作和发布](https://developers.weixin.qq.com/miniprogram/dev/framework/quickstart/release.html)

官方页面给出四种版本：

- 开发版本：开发者工具上传的代码；每个开发者只保留自己最新的一份；可删除且不影响线上版本和审核中版本。
- 体验版本：从某个开发版本中选取一份作为体验版，并且同时只有一份体验版。
- 审核中版本：同时只能有一份；审核结果出来后可发布线上，也可重新提交审核覆盖原审核版本。
- 线上版本：所有线上用户使用的版本，新版本发布后覆盖更新。

官方发布顺序为“预览 → 上传代码 → 提交审核 → 发布”：

- 预览会打包当前项目、上传至微信服务器并生成二维码，开发者微信扫码可在手机客户端查看真实表现。
- 上传代码用于提交体验或审核；上传成功后在小程序后台“版本管理 → 开发版本”查看，并可把该版本设置为体验版或提交审核。
- 提交审核是质量/规范门槛，官方要求严格测试后再提交。
- 审核通过后，管理员在“审核版本”中点击发布；支持全量发布或分阶段（灰度）发布。

能证明：

- “开发版本存在”只能证明上传进入开发版本池；“体验版可扫码访问”能证明选定的开发版本被作为内测体验版本提供给体验成员。
- “审核通过”与“点击发布”是两个不同后台状态；只有发布后才是线上版本。

不能证明：

- 开发版本上传、体验版二维码或审核通过都不能直接证明线上用户已经切换到该版本。
- 线上发布证明的是小程序代码版本，不自动证明同一批后端 jar、数据库迁移、域名、支付或第三方服务也已部署/读回。

与仓库对应：

- `chengyinhub-xcx/docs/发版checklist.md:14-20` 已把隐私、后端部署、支付/核销真机冒烟、版本号/更新日志列为后台/人工项，符合“代码上传不是发布完成”的边界。
- `chengyinhub-xcx/docs/发版checklist.md:24-26` 把数据库/后端部署放在微信上传之前；这是项目自己的跨系统发布顺序，微信官方页面只规定小程序侧的四步，不能替代仓库的后端/DB 证据。

### 3. 隐私保护指引、隐私接口与用户同意

来源：

- [小程序隐私协议开发指南](https://developers.weixin.qq.com/miniprogram/dev/framework/user-privacy/PrivacyAuthorize.html)
- [用户隐私保护指引填写说明](https://developers.weixin.qq.com/miniprogram/dev/framework/user-privacy/)
- [wx.getPrivacySetting](https://developers.weixin.qq.com/miniprogram/dev/api/open-api/privacy/wx.getPrivacySetting.html)
- [wx.onNeedPrivacyAuthorization](https://developers.weixin.qq.com/miniprogram/dev/api/open-api/privacy/wx.onNeedPrivacyAuthorization.html)
- [wx.openPrivacyContract](https://developers.weixin.qq.com/miniprogram/dev/api/open-api/privacy/wx.openPrivacyContract.html)

官方要求和机制：

- 涉及处理用户个人信息的小程序，需用明显方式提示用户阅读隐私政策等收集使用规则；微信要求开发者同步“用户已阅读并同意”，之后才能调用微信隐私接口。
- 小程序管理后台必须配置《小程序用户隐私保护指引》。只有在指引中声明所处理的用户信息，才可以调用对应接口/组件；未声明的接口或组件会被禁用。
- `wx.getPrivacySetting` 可查询 `needAuthorization`，并返回后台配置的指引名称；`wx.openPrivacyContract` 可打开该指引。
- 存在待同意信息时，应提示用户，并在界面使用 `button open-type="agreePrivacyAuthorization"`；在 `bindagreeprivacyauthorization` 回调后调用已声明的隐私接口。
- 也可以通过 `wx.onNeedPrivacyAuthorization` 被动监听“用户触发了微信侧未记录同意的隐私接口调用”，再展示自定义授权界面；`wx.requirePrivacyAuthorize` 可用于模拟隐私接口调用。
- 官方页面记载：2023-10-17 之后隐私相关功能无论 `app.json` 是否配置 `__usePrivacyCheck__` 都会启用。因此该字段不应被当作今天唯一的隐私开关。

能证明：

- 后台指引条目、代码中的授权入口和 `agreePrivacyAuthorization` 事件形成了可检查的接入链；用户同意事件回调后再调用接口，符合官方机制。

不能证明：

- `app.json` 有 `__usePrivacyCheck__`、`requiredPrivateInfos`，或页面能打开，并不证明后台指引已经审核/生效、每个接口都已声明、用户真的完成同意，也不证明拒绝/撤回后的业务行为符合隐私承诺。
- 本地单测/预检不能替代微信后台配置读回和真机上“未同意 → 提示 → 同意/拒绝 → 接口行为”的观察。

与仓库对应：

- `chengyinhub-xcx/docs/发版checklist.md:14-20` 已将“`app.json` / 微信后台指引与实际接口一致”列为人工项，官方资料支持该项不能由纯源码脚本代替。
- `chengyinhub-xcx/app.json:175-189` 设置了 `__usePrivacyCheck__`、5 个 `requiredPrivateInfos` 地理位置接口和 `scope.userLocation`/`scope.userFuzzyLocation` 描述。官方配置文档说明 `requiredPrivateInfos` 是地理位置相关接口数组；它不是后台隐私指引本身。
- `chengyinhub-xcx/app.js:263-290` 注册 `wx.onNeedPrivacyAuthorization`，保存 resolver 并跳到隐私页；`chengyinhub-xcx/pages/privacy/index.wxml:42-56` 提供 `agreePrivacyAuthorization` 同意与暂不同意/撤回入口；`pages/privacy/index.js:28-75` 调用 `wx.openPrivacyContract`、记录同意/撤回并 resolve。代码形态与官方回调机制相符，但仍需后台指引和真机行为读回。

### 4. `requiredPrivateInfos`、权限声明和定位边界

来源：[小程序配置 `requiredPrivateInfos`](https://developers.weixin.qq.com/miniprogram/dev/reference/configuration/app.html#requiredPrivateInfos)

官方配置页说明：2022-07-14 之后发布的小程序，使用以下 8 个地理位置相关接口时需要在 `app.json` 声明 `requiredPrivateInfos`，否则无法正常使用：`getFuzzyLocation`、`getLocation`、`onLocationChange`、`startLocationUpdate`、`startLocationUpdateBackground`、`chooseLocation`、`choosePoi`、`chooseAddress`；同时还要在小程序后台“开发 → 开发管理 → 接口设置”自助开通接口权限。

当前仓库声明的是 `chooseLocation`、`choosePoi`、`getLocation`、`onLocationChange`、`startLocationUpdate`，并声明了 `scope.userLocation` 与 `scope.userFuzzyLocation` 的用途描述。静态配置覆盖了当前列出的 5 个接口，但不能据此推断代码没有调用其余 3 个接口，也不能据此证明后台接口开通、隐私指引声明或用户同意已完成；这些需要分别核对。

### 5. 包体与分包

来源：[分包加载](https://developers.weixin.qq.com/miniprogram/dev/framework/subpackages.html)

官方限制：

- 每个使用分包的小程序都有一个主包；启动时默认下载主包，进入分包页面时再下载对应分包。
- 所有分包大小合计不超过 30M（服务商代开发小程序不超过 20M）。
- 单个分包或主包大小不能超过 2M。
- 分包的目的包括缩短首次启动下载时间；`preloadRule` 可用于分包预下载（配置页也将其定义为分包预下载规则）。

能证明：

- 开发者工具/CLI `info-output` 的代码包、分包大小信息可以作为某次编译产物的包体证据；应保存主包、每个分包及总量，而不是只看页面数量。

不能证明：

- 主包页数少于阈值不等于主包字节数低于 2M；分包数量也不等于分包大小合规。
- 预加载规则改善的是下载时机/体验，不会放宽 2M/30M 限制，也不等于所有页面已在真机加载验证。

与仓库对应：

- `chengyinhub-xcx/docs/发版checklist.md:8-12` 把主包页数 ≤40 和开发者工具代码依赖分析主包 <1.8M 设为内部发版目标；`1.8M` 比官方单包 2M 更保守，应保留，但不能把它写成微信官方阈值。
- `chengyinhub-xcx/scripts/preflight-release.js:74-79` 只读取 `app.json` 的主包页数和分包数量；超过 40 仅产生 `WARN`，没有读取编译产物、没有测量字节数、没有检查单个分包 ≤2M 或全部分包 ≤30M。因此当前脚本不能独立闭合官方包体门禁。
- `chengyinhub-xcx/app.json:2-156` 当前有 39 个主包页面、10 个 `subPackages`；`preloadRule` 在 `app.json:157-173`。这些是结构静态事实，不是实际压缩后包体读回。

### 6. 预览、真机调试与真实验证边界

来源：

- [真机调试](https://developers.weixin.qq.com/miniprogram/dev/devtools/remote-debug.html)
- [真机调试 2.0](https://developers.weixin.qq.com/miniprogram/dev/devtools/remote-debug-2.html)
- [小程序协同工作和发布：预览](https://developers.weixin.qq.com/miniprogram/dev/framework/quickstart/release.html#预览)

官方页面说明：

- 预览用于检查小程序在移动客户端上的真实表现；开发者工具会打包并上传当前项目，成功后显示二维码，开发者微信扫码后在手机客户端查看。
- 真机远程调试会将本地代码处理、打包并上传；手机扫描二维码后打开调试窗口，可查看 Console、Sources、AppData、Storage 和手机/网络/服务器连接信息。
- 真机调试 2.0 使用新的调试模型，使调试结果更接近真机表现；Android/iOS 的微信客户端与基础库有最低版本要求，并要求选择的机型与真实机型一致。
- 真机无网络或停在断点时，官方文档说明手机端会出现提示并阻止进一步操作；因此网络、客户端版本、基础库版本和机型都是验证上下文的一部分。

能证明：

- 预览二维码/真机调试窗口能证明指定构建产物在一台或多台具体手机、微信客户端、基础库和网络条件下被实际打开；结合截图、操作步骤、控制台/网络错误和版本信息，可作为真机体验证据。
- 真机调试适合定位手机特有行为和连接问题，不只是模拟器静态截图。

不能证明：

- 真机预览/远程调试不是后台审核，也不是线上发布；它使用的是开发者工具打包上传的调试/预览版本。
- 一台手机、一个账号、一个网络环境的通过，不能代表所有机型、基础库、地区、角色、权限、支付渠道或生产数据都通过。
- 官方真机调试文档没有承诺生产后端、数据库迁移、线上配置、审核材料或全量发布状态；这些必须由仓库自己的部署、DB、后台版本和业务角色证据单独证明。

与仓库对应：

- `chengyinhub-xcx/docs/发版checklist.md:18-20` 的“后端已部署”“支付/核销真机冒烟”“版本号 + 更新日志”应继续作为相互独立的人工证据；不能以 CLI/预览绿灯替代。
- `chengyinhub-xcx/docs/发版checklist.md:26` 的“上传 → 提交审核 → 发布”与官方流程一致；真机冒烟应记录使用的体验/预览版本、手机型号、微信/基础库版本和结果。

## 对现有清单与预检脚本的闭合建议（仅研究结论，不在本次改动）

1. 保留 `preflight-release.js` 的域名、明文 HTTP、调试日志和结构性页数检查；把它标注为“源码/结构预检”，不要称为官方包体合规或发布成功证明。
2. 增加一个人工/CLI 证据项：执行 `cli preview` 或 `cli upload` 时保存 `--info-output`，逐项核对主包 ≤2M、每个分包 ≤2M、总量 ≤30M；继续以内部 `<1.8M` 作为更早阻断目标。
3. 把 `upload` 成功、开发版本可见、体验版可扫码、提交审核、审核通过、发布线上分别记录；每一项都从微信侧读回，不用同一条 CLI 退出码代替全部状态。
4. 隐私项至少要同时核对：后台《用户隐私保护指引》实际声明、`requiredPrivateInfos` 与实际地理接口调用、后台接口设置、真机未同意/同意/拒绝/撤回行为。`__usePrivacyCheck__` 不能作为唯一门禁。
5. 真机冒烟记录版本、机型、系统、微信客户端、基础库、网络和账号/角色；对于支付、核销、定位等物理或账号边界，保留“已验证范围”，不扩写成全量上线证明。

## 本次仓库核查记录

- 执行：`node chengyinhub-xcx/scripts/preflight-release.js`
- 当前输出：`siteBaseUrl` 为生产 HTTPS；无明文 `http://localhost`/本地 IP 命中；主包 39 页、10 个分包；无 BLOCK，WARN 0，INFO 3。
- 解释：这只说明当前脚本的三类静态检查通过。由于脚本未计算真实压缩包体、未读取微信后台、未执行审核/发布/真机路径，不能把该结果写成“微信合规”或“已上线”。
