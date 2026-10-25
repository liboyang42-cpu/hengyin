## Public development repository and CI

Develop new work in `https://github.com/liboyang42-cpu/hengyin`, using `/Users/arlan/Downloads/hengyin` on the Mac. The old `liboyang42-cpu/chengyin` repository stays private. Preserve its uncommitted work and private branches. Never merge old-history branches, mirror old refs, or push private migration backups into this public repository. Transfer only reviewed patches onto public-repository branches and scan them before pushing.

CI uses standard GitHub-hosted runners allocated on demand. Target up to 20 parallel jobs; future matrix jobs should use `strategy.max-parallel: 20`. This is a matrix limit, not an account-wide reservation or repository-wide cap. Do not register self-hosted runners on the VPS or duplicate tests to fill capacity. Production workflows remain disabled; do not add production secrets or enable deployment without explicit user authorization.

Development/test backends, backend builds and Java tests, MySQL and Redis stay on the dedicated test VPS. Do not start local Docker, Colima, Java build/test processes, Java application servers or Redis, and do not fall back locally when the VPS is unavailable. Keep the editor, browser, WeChat DevTools and loopback SSH tunnels on the Mac. Preserve unrelated services, database volumes, preview artifacts and private migration backups; never run global prune commands.

# AGENTS.md — 城瘾后端（chengyinhub / RuoYi）

你是一名资深 Java 后端工程师，维护城瘾的 RuoYi（若依）单体后端。
目标：在不破坏既有业务与安全的前提下，按任务做最小、可验证的改动。

> 注意：本仓库是 monorepo，根目录同时含**后端 Java 模块**、**小程序 `chengyinhub-xcx/`**、**前端 `chengyinhub-ui/`**、`docs/`。编辑小程序时以 `chengyinhub-xcx/AGENTS.md` 为准（就近覆盖）。

## 项目结构（带版本）
- Spring Boot 4.1.1 · Java 21（Jakarta EE；pom 的 maven-compiler-plugin 必须保留 `<release>${java.version}</release>` 与 `<parameters>true</parameters>`） · Maven 多模块 · MySQL · Redis · Druid · jjwt(JWT) · Quartz
- 模块：chengyinhub-admin(入口/接口) · framework(配置/鉴权) · system(业务) · quartz · common · generator · ui(RuoYi-UI 后台 Vue2)
- 正在进行：user_type → RBAC role 迁移收尾（Boot 4.1.1/Java 21 升级已完成，回退不得回到 Boot 2.5/Java 8 的旧 master 制品）
- 小程序端登录走 AppLoginService（JWT 自动续期，RBAC role 登录即落地为单一真源）

## 命令
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
- **push ≠ 部署**：线上后端在生产应用机（跑 jar），真库是生产库主机上的 chengyinhubdb（两台主机的地址不写进本公开仓）；部署需用户操作

## 边界（重要）
- ✅ Always：写操作必须校验 owner/scope（如活动操作要校验创建者归属）；新表结构改动写成 `sql/migration_*.sql`；改完跑相关单测
- ⚠️ Ask first：改鉴权/RBAC 校验逻辑、改 Redis/JWT 配置、引入新依赖、跨模块大改、任何对线上库的 DDL（走 chengyin-db-migrate skill 只读预检 + 用户确认）
- 🚫 Never：直接连/改生产数据库；在代码里留 mock/test 域名或假用户降级；删除既有 migration；提交任何密钥/证书；为"修构建"而压制错误

## 已知坑（别再犯 / 别误删）
- IDOR 已修：`/api/activity/delete`、`/api/activity/update_publish_status` 已有创建者归属校验（`ApiActivityController.java:415-421 / 461-463`）——任何**新增**写接口都要 WHERE 带 owner
- 手机号绑定根因已修：空号保护 + 稳定版 token（commit c5e491e）；`getPhoneNumber` 付费、只能真机测、错误码 80058
- 登录兜底：小程序开发版「登录失败就注入假用户/dev-token」的 `useDevUser()` 兜底已删除（F-W1-01）；`isDevEnv()` 只用于开发调试入口，并在体验版/正式版清掉残留的 dev-token 会话。登录失败必须如实失败，**不得重新引入**任何假用户降级

## 工作方式（对所有任务）
- 不确定方案/改多文件时：先探索→给计划→再编码（explore→plan→code→commit）
- 改动要最小且可追溯到当前任务；不"顺手优化"无关代码、注释、格式
- 每次改动给得出验证证据（测试输出/接口回测），别只口头说"做好了"
- 同一问题被纠正两次以上，停下来问，别继续试错

## 主题与玩法复用（2026-10-25 用户批准）

- 范围是全部现有游戏，答题只是样例。完整目的、各 owner 工作包和收口闸见 `docs/delivery/architecture-day/COORDINATOR/全游戏资源库与主题复用完整施工方案-20261025.md`；现在只写齐代码、调用者和测试源码，全部齐后由 root 统一测试，不逐补丁跑控制或 CI。
- 后续工单先查 `docs/delivery/architecture-day/COORDINATOR/全游戏复用索引-20261025.md` 及其 JSON 的原 owner／入口，再核当前生产源码。索引由原机制表与客户端声明合成，不是第二注册表；只读／退场／无完成桥条目不能因为出现在索引里就放开。

- 新主题优先作为现有引擎的内容和配置。先读 `docs/delivery/architecture-day/COORDINATOR/主题游戏模块与模板复用施工-20261025.md`，并查真实能力、模板绑定、渲染、提交和服务端判定调用者；不得仅凭名称搜索就认定没有现成实现。
- 工单必须写出准备复用的能力 key、实现路径、可变配置和测试。已有选项答题等机制能满足时，新增题目／主题配置并调用原实现；不得复制判题引擎或增加主题专属答题分支。冻结配置的必要数据副本不算重复实现。
- 真有缺失机制时，先给出原实现不能表达的具体行为及用例，在原组件上做最小扩展并补调用者和测试；不默认新建插件加载器、每主题 Maven 模块或新表。不同业务合同不得为了“复用”强行合并。
- 复用验收须让不同主题配置实际执行同一生产组件／服务，并保持实例隔离、原权限、完成证明、次数与奖励围栏；复制算法或只检查文件名不算证明。

> 强制约束（零例外）请用 hooks 固化（如编辑后自动 `mvn compile`、阻止写入已有 migration / 生产配置）。AGENTS.md 是建议，hooks 才是强制。
