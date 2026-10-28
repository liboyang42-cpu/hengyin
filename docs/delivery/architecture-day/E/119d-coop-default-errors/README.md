# 默认可见错误窄增量（SOURCE）

Parent: df37bf0e49ba85d175ce943e2580404414af040c。
仅两个代码文件；不回写/重建父候选，父候选原金额单位、拒绝负控、GET required 分类、冻结 payload、type2 退场和 UNKNOWN 场景全部保留。

- invite/index.js: 仅移除 loadSupplyTopic 内分页 POST /api/topic/list 与 refreshSupplyStations GET /api/coop/supply-stations 两个 silentError:true。此前称两处“station相关GET”中，前者实际为既有 owner 列表 POST。原 loadMyTopics、loadTargets 两条分支的三处 silentError:true 均保留。
- 原 success/fail/successStatusAbnormal、页内 error/retry、current/identity/epoch/session/token/scope、分页和 UNKNOWN 零自动重发均不变。
- coop-supply-station-contract.test.js: 两例读取失败回归 SOURCE 改为断言没有 silentError 属性，使用 request-client 默认可见错误；原页内错误/重试/零写断言保留。fixture 的 app.sendRequest 只捕获请求，不冒充真实 transport toast 执行；tips.length=0 仅证明页内回调没有另加 toast。
- 不添加白名单豁免或额度。父目录 119d-coop-caller-repair/shared-silent-error.review.patch 明确撤回，不得应用；其 README 对建议3→5的内容是历史提案，本次裁决覆盖。共享 ci/silent-error-allowlist.txt 本页保持3，root维护的42总cap/其它metadata本供体不改。
- 不改 Controller/API parser/action-ledger/全局 baseline。无新权限、请求重发、写意图、依赖或业务规则。

source-manifest.json 给出两文件精确前后像哈希和 decoded owned.patch.b64 的代码补丁 SHA256。preimage 对齐公开 df37 两个实际 blob。
只执行文件编辑、源身份哈希、git diff/apply --check；测试、Node、编译、Java、DB、HTTP业务调用、CI/微信均 NOT_RUN。由 root 统一最终验收。
