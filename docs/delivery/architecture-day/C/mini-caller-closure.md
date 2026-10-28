# C：07172 小程序合法调用夹具收口

结论：已复现并修正两份测试夹具；没有修改生产 caller、canonical asset、独立同意或 UNKNOWN 规则。相关 11 文件实跑 **141/141 pass、0 fail/skip**。Java/MySQL、完整小程序分片和真机未运行；不能据此宣称 root 联合候选验收通过。

## 候选与范围

- 公共仓库 `liboyang42-cpu/hengyin`，独立工作树 `/workspace/hengyin-c`，分支 `codex/architecture-day-c-play-command`。
- 本 delta parent `971b77d2b06539102587a4a1588df64743f82bfe`；参考公开集成源码 `07172e6fddf28f99ea4ad214b5c92cfe7608e441`。
- 仅改三个 C 专属 Node 测试及本目录报告证据，不重写 9da/971，不改 root ledger、Java/Mapper/schema、WP08 编译修复，不 dispatch。
- 页面、advanced-game、advanced-photo-pending、共享 upload-client 四份生产源码与公开 07172 逐字节相同，SHA-256 见 `evidence/mini-caller-closure/source-proof.json`。

## 实际失败与修复

`play-qa-recovery-contract.test.js` 修前 6 条中 1 pass/5 fail。真实 Component 的 properties 由宿主传入，旧夹具只复制 definition.data，漏掉 node/topic/activity；9da 启动时先读取按主体/节点分区的 Photo pending，缺 node 的合成宿主被合法拒绝，所以没有 start→emitSession→kit，也不会上传。

夹具现在安装原宿主 node=51/topic=10/activity=0，合成个人会话视图明确相同 scope/owner，并使用 Map 实现存储写入/读回/删除。QA 仍沿用其原 URL 输入；canonical media-asset 合同限定 SUBMIT_PHOTO_CHECK，没有将 QA 偷换成 Photo 或改其恢复断言。原六条真实页面+QA+advanced-game 链全部保留：生命周期、缺身份零上传、未提交恢复、已提交丢包、回读失败后恢复、未提交后重拍。

`separate-consent-feature.test.js` 修前 7 条中 6 pass/1 fail。旧上传替身直接把 URL 放进 onDone.results，没有执行生产 upload-client 的 mapResult；Photo 新 caller 需要由真实上传响应 assetId 生成 canonical 引用，URL 替身被拒绝，原 actions[0] 不存在。

改为真实 createUploadClient，仅替换 wxUploadFile I/O；成功响应带 assetId=701，生产 mapResult 自己生成 media-asset:701，原同意持久化完成前零上传断言保留，新增精确 payload 断言。补两个负控：上传响应缺 assetId 不调用 runtime；同意持久化失败既不上传也不调用 runtime。原拒绝、切题、离页、actor/token/page 迟到和作用域同意文档校验保留。

`advanced-game-receipt-contract.test.js` 保留原 59 条，再加一条真实 Photo pending 负控：setStorageSync 不抛错但没有保存任何内容时，读回验证必须明确报“未保存”，acting/unknown 不伪装执行，网络调用数为 0。原存储抛错、缺媒体、UNKNOWN 同 key/expected/payload、重启、切号及 A→B→A 的断言不改。

## 实际执行证据

目录 `evidence/mini-caller-closure/` 保存两份修前失败 TAP、逐文件修后 TAP、计数摘要和源文件校验；TAP 仅去除行尾空格。

命令逐文件执行：`node --test --test-isolation=none --test-reporter=tap tests/unit/<name>.test.js`，工作目录 chengyinhub-xcx。11 个文件各自 exit 0，实际计数如下：

| 测试 | pass/tests |
|---|---:|
| advanced-game-receipt-contract | 60/60 |
| play-qa-recovery-contract | 6/6 |
| separate-consent-feature | 9/9 |
| play-qa-photo-upload-contract | 11/11 |
| advanced-game-object-card-relay | 8/8 |
| playkit-photocheck-server-judge-contract | 6/6 |
| playkit-photocheck-frame-contract | 20/20 |
| object-card-page-lifecycle | 3/3 |
| advanced-game-variants-recovery | 7/7 |
| advanced-game-draw-audio | 4/4 |
| architecture-day-c-intent | 7/7 |

这是相关文件的实际 141 条，不是 shard16 或完整 20 分片的通过计数。未运行 Java/Maven/MySQL/Redis/真实供应商/微信模拟器或真机，也未将 root 编译失败前的 0 业务执行记为通过。

## 集成与余项

本 delta 无 DDL 和业务数据影响，可独立回退测试改动；不能以回退测试为由解除生产 asset/pending/同意校验。root 取 parent 后的最小差异即可，不需重建或覆盖 ledger。WP08 Matcher 编译歧义由 root 独占修复。

WP09 按最新指令停在只读：已读公开规范并核实控制器 25 个端点、68 个 private 方法实现/58 个名字、18 个 Mapper 注入，未编码或移动任何业务。完整用例迁移计划继续排在当前联合 CI/H 真缺陷之后。
