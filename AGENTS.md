# AGENTS.md — 城瘾后端（chengyinhub / RuoYi）

你是一名资深 Java 后端工程师，维护城瘾的 RuoYi（若依）单体后端。
目标：在不破坏既有业务与安全的前提下，按任务做最小、可验证的改动。

> 注意：本仓库是 monorepo，根目录同时含**后端 Java 模块**、**小程序 `chengyinhub-xcx/`**、**前端 `chengyinhub-ui/`**、`docs/`。编辑小程序时以 `chengyinhub-xcx/AGENTS.md` 为准（就近覆盖）。

## 项目结构（带版本）
- Spring Boot 4.1.1 · Java 21 · Maven 多模块 · MySQL · Redis · Druid · jjwt(JWT) · Quartz；Bouncy Castle jdk18on 1.86（版本以 [pom.xml](pom.xml) 为准）
- 模块：chengyinhub-admin(入口/接口) · framework(配置/鉴权) · system(业务) · quartz · common · generator · ui(RuoYi-UI 后台 Vue2)
- 当前源码已迁移到 Java 21 / Spring Boot 4.1.1；源码版本不代表已部署版本。user_type → RBAC role 迁移仍按既有边界处理。
- 小程序端登录走 AppLoginService（JWT 自动续期，RBAC role 登录即落地为单一真源）

## 开发环境与公开边界
- 公开开发与 CI 在 [hengyin](https://github.com/liboyang42-cpu/hengyin) 进行；[主 CI](.github/workflows/ci.yml) 和 [资金集成测试](.github/workflows/funds-mysql-it.yml) 使用 GitHub 标准托管 `ubuntu-latest`，按现有路径规则触发，避免重复派发。
- 专用测试 VPS 用于后端构建、Java 测试、数据库和预览服务，不运行 GitHub Actions runner。Mac 保留编辑器、浏览器、微信开发者工具和轻量前端检查，不启动本地 Java 或数据库服务。
- 私有仓库保留现有源码、remote 和历史；公开仓库只接收经审阅与敏感扫描的源码补丁，不复制私有日志、备份、凭据或生产配置。

## 命令
以下后端命令在专用测试 VPS 或上述托管 CI 中执行。
- 构建：`mvn -q -DskipTests package`
- 跑测试：`mvn -q test`（改了哪个模块就只跑哪个：`mvn -q -pl chengyinhub-system test`）
- 提交前必须：编译通过 + 相关单测通过

## 代码风格
- 跟随现有 RuoYi 风格：Controller 薄、Service 写业务、Mapper 用 MyBatis XML
- 接口返回统一用项目现有的 AjaxResult / R 包装，别自创返回结构
- 新接口必须做权限与归属校验（见边界）

## Git
- 从 master 切 feature/<scope> 分支，别直接推 master
- commit 用中文 + 类型前缀（feat/fix/chore/docs），与现有 git log 一致
- **push ≠ 部署**：推送和 CI 通过不代表已上线；部署仍需用户授权。

## 边界（重要）
- ✅ Always：写操作必须校验 owner/scope（如活动操作要校验创建者归属）；新表结构改动写成 `sql/migration_*.sql`；改完跑相关单测
- ⚠️ Ask first：改鉴权/RBAC 校验逻辑、改 Redis/JWT 配置、引入新依赖、跨模块大改、任何对线上库的 DDL（走 chengyin-db-migrate skill 只读预检 + 用户确认）
- 🚫 Never：直接连/改生产数据库；在代码里留 mock/test 域名或假用户降级；删除既有 migration；提交任何密钥/证书；为"修构建"而压制错误

## 已知坑（别再犯 / 别误删）
- IDOR 已修：`/api/activity/delete`、`/api/activity/update_publish_status` 已有创建者归属校验（`ApiActivityController.java:415-421 / 461-463`）——任何**新增**写接口都要 WHERE 带 owner
- 手机号绑定根因已修：空号保护 + 稳定版 token（commit c5e491e）；`getPhoneNumber` 付费、只能真机测、错误码 80058
- 行为校验：`useDevUser()` 已被 `isDevEnv()` 网住——这是**正确状态**，别当成需要清理的 mock

## 工作方式（对所有任务）
- 不确定方案/改多文件时：先探索→给计划→再编码（explore→plan→code→commit）
- 改动要最小且可追溯到当前任务；不"顺手优化"无关代码、注释、格式
- 每次改动给得出验证证据（测试输出/接口回测），别只口头说"做好了"
- 同一问题被纠正两次以上，停下来问，别继续试错

> 强制约束（零例外）请用 hooks 固化（如编辑后自动 `mvn compile`、阻止写入已有 migration / 生产配置）。AGENTS.md 是建议，hooks 才是强制。
