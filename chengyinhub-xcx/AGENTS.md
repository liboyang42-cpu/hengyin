# AGENTS.md — 城瘾小程序（chengyinhub-xcx）

你是一名资深微信小程序工程师，维护城瘾的**原生**微信小程序（WXML/WXSS/JS，非 uni-app）。
目标：按任务做最小、可在模拟器验证的改动；保留它作为最强获客入口。

## 项目结构
- 原生微信小程序：主包 60+ 页面 + 3 个分包（subpackageP3 成长/世界·星图RPG/创作者；subpackageA 资产中心；subpackageB IM）
- 登录：`app.js` firstLogin → 后端 AppLoginService 换 JWT；RBAC `role` 登录即落地（单一真源）
- 主题基调：**全站默认深色**（玩家域 consumer-night，`app.js onLaunch applyTheme('dark')`）；商家域页面根节点挂 `theme-merchant`/`theme-light`，创建域挂 `theme-topic-editor`。与 Brand Handbook「玩家默认暗、商家灰底白卡」一致。（2026-09-01 更正：此处原写「全站浅色、仅首页切深色」，与 `app.js:32` 及手册均不符。）
- 配置：`utils/config.js`（baseUrl / baseImgUrl）；mock：`pages/club/utils/mockData.js`（仅 dev 用，随 pages/club 分包，不进主包）

## 命令 / 验证
- 用微信开发者工具打开本目录预览（CLI 自动化见 wechat-miniprogram-devtools skill）
- 做页面必须**截图模拟器**走「Goal→发现→优化→完美」闭环，不能只靠改代码收工
- 改动前先截图分析现状 + 复述对需求的逐条理解再动手

## 代码风格
- 跟随现有页面范式（Page/Component、setData、app.tips）；新页面接入统一主题，别硬编码颜色/间距（设计 token 迁移进行中）
- 网络请求走现有封装；列表/详情复用现成组件（route-map / activity-picker / roleBadge）

## Git
- feature/<scope> 分支；commit 中文 + 类型前缀
- **push ≠ 微信发布**：上传/发布需用户在开发者工具操作；攒着和后端 jar 一起部署

## 边界（重要）
- ✅ Always：定位仅前台按需采集 + 先弹用途说明（合规，scope.userFuzzyLocation 已补）；改完截图自验
- ⚠️ Ask first：改登录/RBAC 视角逻辑、改分包结构、引入新第三方库
- 🚫 Never：把后台持续采集定位做无关用途；明文存敏感信息；提交隐私密钥

## 已知坑 / 刻意保留项（**别擅自删**）
- ✅ `utils/config.js baseImgUrl` 已切正式域名 `https://www.chengyinhub.com/prod-api/profile/`（M0-9 测试域名清零已完成，全仓 `test2.h5.org.cn` 零命中）。历史上曾用 `http://test2.h5.org.cn` 调 UI/测数据，现已清除，勿再回退。
- ⚠️ `debug_user_view`（`shezhi.js` / `pages/index/index.js`）：**用户有意保留**的身份调试开关（用户/商户视角切换）；上线前再移除。
- ✅ 开发版假用户兜底 `useDevUser()` 已删除（F-W1-01）：任何环境登录失败都如实失败、不产假用户；`isDevEnv()`（envVersion==='develop'）只门控开发调试入口，并在体验版/正式版清掉残留 dev-token 会话。**不得重新引入**假用户降级。
- 身份切换：商户视角默认，`debug_user_view='user'` 强制普通用户视角；改视角逻辑前读懂 index.js:7 / 41 / 376。

## 工作方式
- 改动最小且可追溯到当前任务；不顺手优化无关代码
- 每次给验证证据（模拟器截图），别只口头"做好了"
- 同一问题被纠正两次以上，停下来问

## 主题游戏与现有玩法组件

- 全游戏共用目录、内容采用和各自权威机制的完整工作包见 `../docs/delivery/architecture-day/COORDINATOR/全游戏资源库与主题复用完整施工方案-20261025.md`。不是只复用答题；不同机制保留不同提交合同。当前只完成必要源码和测试源码，全部齐后由 root 统一执行测试。

- 新主题先沿实际模板绑定和玩家入口找现成组件。选项答题优先核对 `pages/play/components/playkit-qa` 及现有提交链；不得因为主题或题目不同就复制一个新答题引擎。
- 工单注明复用组件、配置入口、实际 caller 和测试；用不同主题配置运行同一组件，验证运行状态与迟到响应仍隔离。不能把普通答题、偏好题组和剧情选择仅因外观相似就强行合并。
- 主题规则合同见 `../docs/delivery/architecture-day/COORDINATOR/主题游戏模块与模板复用施工-20261025.md`。服务端判定和原权限／UNKNOWN／回执恢复合同继续有效；客户端复用不得新增本地权威判题、自动重发或答案泄漏。
