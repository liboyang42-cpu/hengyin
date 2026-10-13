# 页面框架与导航规范

## 普通页面

普通二级页固定使用：

1. `navigationStyle: "custom"`
2. `cy-nav-bar`
3. `cy-page-title`
4. 页面正文
5. 必要的 sheet / modal host

导航和标题不得放进只覆盖某一状态的 `wx:if`。loading、empty、error、ready 切换时，返回出口和页面身份必须始终存在。

仅有两个已登记例外：俱乐部创建向导由步骤问句承载标题；WebGL 徽章墙使用 `cover-view` 字幕与返回按钮。新增例外必须先补合同测试。

## 安全区与滚动

- 顶部高度使用设备 `statusBarHeight` / `navBarHeight`，不写死机型尺寸。
- 页面横向留白使用 `--cy-page-x`。
- 页面只保留一个主滚动容器；固定按钮必须避开底部安全区和系统胶囊。
- 深层截图必须证明目标正文进入视口，不能只证明滚动条动过。

## 弹层与退出

- sheet/modal 打开后只保留一个清晰退出路径；关闭、返回和遮罩行为必须一致。
- 提交中禁止重复点击；结果未知时保持锁定并提供“确认结果”，不能直接显示失败或成功。
- 危险操作写明对象和不可逆后果，再显示确认按钮。

## 文案

- 标题描述用户当前任务，按钮使用动作词。
- 错误文案包含恢复动作；无恢复动作时说明去哪里查看最终结果。
- 用户界面不得出现内部工程词、测试提示、字段名或开发说明。

对应门禁：`title-system-owner-contract.test.js`、`nav-title-unconditional-contract.test.js`、`audit-f01-f17-contract.test.js`。
