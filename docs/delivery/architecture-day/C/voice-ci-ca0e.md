# ca0e 已定位声音 CI 修复

父源：`ca0e0d2598727f6e567c802b7a664f9634d945c6`；对应 run40122522919 的声音共享夹具、BL-43、Figma 超时恢复及 F01-F17 文案失败。独立增量，不改 H 的 review。

- 共享节点夹具设置真实已打开表单/nodeId=1，原各正常/换号/换节点/重开/隐藏断言不改。
- 两生产 status catch 恢复当前 pending 的原只读轮询，保持 session/page/form/version 守卫，旧回包不调度；不发送 enroll。节点查询失败保持原静默显示合同。
- Figma 夹具安装真实 session/journal helper，原 UNKNOWN 经 begin→dispatched→observe 建立；状态响应保留原 requestId。原清文件/清UNKNOWN/不二发断言保留，并加原请求身份断言。未伪装生产 storage 或 provider。
- BL-43 锚点迁至真实 context 参数和守卫委托，仍要求危险确认、POST reset、回读0再成功、不加 success toast，并新增 HTTP 前 current 检查的源码断言。
- 6处用户提示改为返回结果/原请求的可理解表达，原身份/样本/终态判断逐字保留，不改术语门禁。

实际定点命令：`node --test --test-isolation=none --test-reporter=tap chengyinhub-xcx/tests/unit/npc-media-owner-isolation.test.js chengyinhub-xcx/tests/unit/npc-voice-reset-bl43.test.js chengyinhub-xcx/tests/unit/merchant-decor-figma-save.test.js chengyinhub-xcx/tests/unit/audit-f01-f17-contract.test.js`。用例级输出137 tests /137 pass /0 fail /0 skip；原默认模式另仅汇报4个文件进程通过，未当成完整用例数。原始输出保留 `/tmp/c-voice-ca0e-cases.tap`。

未运行 Java/MySQL/全批测试/CI/DevTools/provider；没有生产/DDL操作。root统一集成后继续最终验收，不借用旧source结果。
