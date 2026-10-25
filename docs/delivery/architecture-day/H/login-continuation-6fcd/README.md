# H 登录迟到续接 P2：交付与验证边界

基线：`6fcd5ac009cec1dd82c438528d24da30c1f302f4`，tree `208624fd71a08b94e0d9e752cdec2270257070a4`。

本提交修 `require-login.withLogin` → `app.ensureLoginFor` 的原续接窗口。游客浏览、明确写前登录、合法同页继续一次均保留；不修改任何 journal、UNKNOWN、服务端权限、金额或付款语义。

## 必须由 root 串行应用的一处共享补片

`session-manager.review.patch` 针对基线 `utils/session/session-manager.js`：在原登录在途校验及成功结果的 `isCurrentSession()` 中各加入已有 session-store revision。该共享生产文件本提交不直接改。

**主补丁与此共享补片必须组合。71 PASS 是该组合的直接 Node 结果，不能写成本提交独立已完成 ABA 修复。** 未应用此补片时，真实 SessionManager 仍不能识别同 token / guest 的 ABA。回归使用真实 SessionManager + session-store，不以自造登录成功证明代替它们。

## 源码范围

- `utils/auth/require-login.js`：在等待前捕获原宿主页实例、原 owner 生命周期代次及调用者显式提供的稳定业务 ID/scope。只比较小的原始值数组，不复制整个 page.data、不包装全局 Page。共享登录 Promise 保留其原会话有效性证明，续接前再次核对 revision。
- `app.js`：原 facade 透传续接 context，并提供真实页面栈。
- 既有 `ensureLoginFor` 全部直接调用者：`activity/baoming`、`activity/official-detail`、`club/detail`、`publish/activity`、`publish/fabu`、`publish/temp`、`square/list`、`square/detail`、`square/components/cy/post-compose`、`talent/list`、`team/join`、`template`、`topic/index`、`subpackageMember/.../report-sheet`。只加显式业务身份及原生 hide/unload/detach/关闭生命周期的续接退休代次。既有提交体、权限检查、请求、回执及恢复记录不改。
- `utils/content-report.js` 绑定原 page/举报目标；`subpackageMember/utils/team-up.js` 接收真实宿主 context；其唯一生产调用者 `scene-member-order-detail` 传原订单号、订单代次及活动号。
- `guest-browse-login-on-write.test.js` 保留原 62 个场景与业务断言；工具层源码定位断言只按新增 context 参数更新并增加 owner/target 断言。新增 9 个直接续接场景。
- 不改 `pages/play/index.js`、资料/扫码源、D 行号断言、F/G 门禁、台账生成物。新增生命周期计数不增加 WXML 控件；台账只由 root 最终生成。

## 实际执行

1. 原 6fcd helper + app +真实 team Page，控制登录 Promise：新增最初 6 个场景实际 2 PASS / 4 FAIL；离页、邀请变更实际都发出了不应发送的 1 次 join。`before.tap` 保留原输出；循环在首个失败处停止，不把未到达子分支称为已复现。
2. 首次全文件试跑 65 PASS / 3 FAIL：隔离副本的 roam/index、首页仍为旧树，且缺只读 Java 源码输入。保留 `intermediate-input-gaps.tap`；未改期待值，按同一冻结 SHA 补齐原测试读取的源文件。
3. 主补丁 +明确的 SessionManager 共享补片，Node v24.19.0：
   `node --test --test-isolation=none --test-reporter=tap .../guest-browse-login-on-write.test.js`
   **71 tests / 71 PASS / 0 FAIL / 0 skip / 0 cancelled**，见 `after-composed.tap`。
4. 20 个直接修改 JS 文件执行 `node --check`；通过。`SOURCE_SHA256.json` 绑定主补丁文件字节。

新增直接场景：登录在途离页零加入、邀请变更零误写、合法同页恰好一次、hide 后返回/同 route 新实例不复活、登录结果后换号/同 token ABA不继承、取消保留原 UNKNOWN 存储、modal 期间 guest ABA、不明确交换回包前 guest ABA、退休后新的明确点击正常继续。后两类真实登录代次场景依赖共享补片。

首次 Node 默认 test 隔离启动未执行到用例，随后显式使用允许的 `--test-isolation=none`。未运行全量 Node、台账生成、静态门禁全套、CI、Java/JUnit、MySQL/Redis、HTTP、微信/真机、供应商或任何真实业务写；不得把直接客户端单测写成上述验收通过。

## 回退与接入

root 将主提交及共享补片同批集成，统一检查其余直接测试与最终台账。回退仅涉及 JS/测试源码，不清空或迁移任何账号原 pending/UNKNOWN/journal；旧版本退回会重新打开已证实的迟到续接窗口。
