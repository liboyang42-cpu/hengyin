# GitHub self-hosted runner 部署迁移指南

> 目标：停用耗尽构建核分的云效 Flow 构建，把后端 CD 迁到私有 GitHub 仓库专用的 Mac runner。GitHub 仍是唯一主仓；Codeup 仍只是镜像备份。

## 最终链路

```text
PR -> GitHub CI 通过 -> 人工合并 master
   -> Deploy verified master to ECS
   -> Mac self-hosted runner (JDK11) 构建 jar
   -> 断言 jar 自报的 git.commit.id == 本次 SHA（构建产物 ↔ commit）
   -> SSH 上传 ECS -> 运行 ci/deploy-restart.sh -> 健康检查
   -> 运行 ci/verify-deployed.sh：向生产回读它到底在跑什么
```

「生产上跑的就是这个 commit」不是一条断言得来的，是两条相乘：

1. **构建产物 ↔ commit**：`git-commit-id-plugin` 把 commit 打进 jar 的 `BOOT-INF/classes/git.properties`，workflow 解出来比对本次 SHA。读不到或对不上即红。
2. **生产的 jar ↔ 构建产物**：`ci/verify-deployed.sh` 比 sha256 指纹，再查恰好一个进程在跑它、且该进程启动于本次部署之后。

第 2 条里的「启动时间」专治 `deploy-restart.sh` 看不出的假绿——它的健康检查只 `curl` 了一下 8080 且不带 `-f`，`pgrep` 失配导致旧进程没被杀时，新 jar 会因端口被占而退出，旧进程照样返回 401，健康检查照样绿。

**不要把「部署器自己写一个 SHA 文件再读回来」当成观测：那比的是自己刚写的值，永远不会红。** 制品必须能自报出身，观测必须问生产而不是问自己。

数据库迁移/数据 SQL 是唯一手动例外：必须在合并前预检并落库；不能把数据库密码或迁移执行步骤放进 GitHub Actions。

本次只迁移 **CD**，以立刻停止云效 Flow 的构建核分消耗。现有 GitHub CI 仍跑在 GitHub 托管 runner 上；等这条部署链路稳定后，再单独评估是否把 CI 也迁到 Mac，避免一次改动同时影响扫描、测试和生产部署。

## 一、先准备 Mac runner（不部署）

1. GitHub 仓库 -> `Settings` -> `Actions` -> `Runners` -> `New self-hosted runner`。
2. 选择 `macOS`、`ARM64`，在你的 Mac 终端逐条执行 GitHub 页面生成的命令。
3. 注册时增加自定义 label：`chengyin`。
4. 确认 Runner 显示为 `Idle`；Mac 必须保持开机、联网，才能接收部署任务。
5. 确保 Mac 有 Maven：`mvn -version`。JDK 11 由 workflow 的 `actions/setup-java` 准备。

Runner 只注册到这个私有仓库，不要注册为公共仓库或组织内“所有仓库可用”的 Runner。

## 二、配置生产部署 SSH（用户在服务器侧完成）

新建一把只用于 GitHub 部署的 SSH 密钥；私钥只存 GitHub Secret，公钥只放生产 ECS 的目标用户 `authorized_keys`。不要复用个人登录密钥，不要把私钥贴到聊天、代码或 workflow 文件。

GitHub 仓库 -> `Settings` -> `Secrets and variables` -> `Actions`，新增以下 Repository secrets：

| Secret | 值 |
|---|---|
| `ECS_SSH_HOST` | 生产 ECS 主机名或 IP |
| `ECS_SSH_USER` | 当前脚本需 root 等效权限；首轮使用专用 `root` 部署密钥 |
| `ECS_SSH_PRIVATE_KEY` | 新建部署密钥的完整私钥 |
| `ECS_SSH_KNOWN_HOSTS` | 生产机的固定 SSH host key 行 |

不要关闭 SSH host key 校验；workflow 使用 `StrictHostKeyChecking=yes`。

当前 `ci/deploy-restart.sh` 会写入 `/www/wwwroot/bin`、停止旧进程并以 `www` 用户启动 jar，因此普通用户不能直接运行它。首轮迁移请新建一把仅供 GitHub Actions 使用的 **root 部署密钥**，不要复用个人 root 密钥。以后若要降权，应先在服务器上另建受限部署用户和固定 sudo 包装脚本，再改 workflow；不要用关闭 SSH 校验或放宽目录权限来凑合。

## 三、切换顺序

1. 本文档和 `.github/workflows/deploy.yml` 所在 PR 先通过 GitHub CI；此时不要直接合并。
2. Runner 已 `Idle`、四个 Secret 已配置、部署 SSH 公钥已验证后，在云效 Flow 关闭生产流水线的代码源触发，防止同一 `master` push 同时烧 Flow 核分。
3. ⚠️ **合并本 PR 会立刻触发一次真实部署**，不是空跑：它含 `chengyinhub-admin/pom.xml`（git-commit-id 插件），命中后端模块分类。合并那一刻就是这条链路的首次生产部署，人必须在场盯着。届时 `master` 已积压 8 个未部署 PR（含 #68/#72 两个安全修复），这一次会把它们一起送上线。
4. 若第 3 步的自动部署因故没触发（例如 GitHub CI 未跑），再走 `Actions` -> `Deploy verified master to ECS` -> `Run workflow`，选择 `master`、保持 `force_deploy=true` 手动补一次。
   出事时的回滚：服务器上 `deploy-restart.sh` 已把上一版备份为 `/www/wwwroot/bin/chengyinhub-admin.jar.bak.<时间戳>`，直接 `mv` 回去再重启即可，不必等重新构建。
5. 验收：workflow 的 build、上传、健康检查均为绿，且 `ci/verify-deployed.sh` 的三条断言全过（指纹 / 单一进程 / 启动晚于本次部署），最后一行打印 `Production verified: <SHA> (jar sha256 ...)`。

## 四、日常规则

- 后端模块或根 `pom.xml` 改动：合并 `master` 后自动部署。
- 纯小程序、文档、非后端 UI 改动：workflow 自动跳过 ECS 部署；小程序仍须微信开发者工具上传。
- **只改 `chengyinhub-admin/sql/**` 也跳过部署**：这些 SQL 要在库上跑，不在 `src` 下、不进 jar，部署它只会构建出与上一版逐字节等价的 jar 再重启一次生产。落库是合并前的人工步骤，不靠这条流水线。若同一次提交里既有 SQL 又有后端代码，照常部署。
- `migration_*.sql`、seed、`sys_job`、菜单/回填等任何要落库的 SQL：合并前先手动执行和复核。`sys_job` 若依赖新 Task 类，按迁移说明在 jar 成功部署后再注册。
- 回滚：先在 GitHub 找到上一个已验证 SHA，再用 `Run workflow` 指定该 SHA，且保持 `force_deploy=true`。不要回滚 Codeup 来影响生产。

## 五、故障判断

| 现象 | 含义 / 处理 |
|---|---|
| Runner 一直 `Queued` | Mac 未开机、Runner 未运行，或缺少 `chengyin` label。 |
| `Permission denied (publickey)` | 部署私钥、服务器公钥、SSH 用户不匹配。不要改 workflow 绕过校验。 |
| `Host key verification failed` | `ECS_SSH_KNOWN_HOSTS` 错误或主机 key 变化；先核验服务器身份。 |
| 健康检查失败 | workflow 已保留旧 jar 备份；先看生产日志与宝塔守护是否抢占 8080。 |
| `jar 指纹不符` | 生产上那个 jar 不是本次构建产物；换 jar 那步没生效，别重跑碰运气，先看 `deploy-restart.sh` 的输出。 |
| `期望恰好 1 个进程，实际 0` | 新 jar 没起来，或 `deploy-restart.sh:27` 的 pgrep 模式与实际启动命令不匹配。⚠️ 此时旧进程很可能仍在 8080 上答话——健康检查会绿，但生产跑的是旧代码。 |
| `期望恰好 1 个进程，实际 >1` | 旧进程没杀干净，正与新进程抢 8080；先确认宝塔「Java 项目管理」的守护/自动重启已关。 |
| `启动早于本次部署起点` | 新 jar 从未接管，生产跑的仍是旧进程。视为部署失败，不能因前面步骤全绿而宣布上线。 |
