# 城瘾 CI/CD 工作流说明(GitHub-first)

> 2026-07-13 定稿,**2026-07-16 换轨重写**(云效 Flow 出局)。本文是城瘾三端(后端 admin /
> 小程序 xcx / 官网)从写代码到上生产的**权威流程**。
> 核心一句话:**GitHub 是唯一主仓,合 PR 即自动部署,人只审阅+合并;数据库迁移是唯一手动例外。**

---

## 1. 架构总览

```
GitHub(唯一主仓 · source of truth: liboyang42-cpu/chengyin)
  ├─ 开 PR      → GitHub Actions CI(后端 JDK11 测试 + 小程序静态检查)
  ├─ 合并 master → CI 绿 → deploy.yml 在自托管 Mac runner 上构建 jar
  │                → 断言 jar 自报 commit == 本次 SHA
  │                → scp 直传 ECS → deploy-restart.sh → verify-deployed.sh 回读
  └─ CI 通过后  → 自动镜像已验证的 master 到 Codeup(备份,不参与部署)

Codeup   = 纯镜像备份 + 国内应急访问,不参与 CD
ECS      = 生产后端(47.103.124.64),jar 由 systemd 服务 spring_chengyinhub-admin 管
runner   = arlan-mac-chengyin(用户的 Mac,labels self-hosted,macOS,ARM64,chengyin)
```

- **CI** = GitHub Actions(`.github/workflows/ci.yml`)
- **CD** = GitHub Actions(`.github/workflows/deploy.yml`)+ 自托管 Mac runner
- **Codeup 镜像** = `.github/workflows/codeup-mirror.yml`,仅在「GitHub CI 成功 + push 事件 + master 分支」时把该已验证 commit 推到 Codeup

### ★ 2026-07-16:为什么不用云效 Flow 了

Flow 的构建核分耗尽 + 制品存储容量满。制品存储满那次尤其阴:**Flow 照常被触发、代码照常拉、
Maven 照常构建成功,只在最后「上传制品」那步 400 失败** → `VMDeploy` 无货可拉 → 部署从未发生。
线上 jar 停在 14:09 整整四小时,期间合了 8 个 PR(含 2 个安全修复),**每个 PR 的 CI 都是绿的、
每次合并都显示 MERGED,零告警**。这是「回执 ≠ 观测」最贵的一课。

新链路**没有制品仓库这一环**(runner 构建完直接 scp),所以这个故障模式结构上不存在。

### ★ 真正管着 jar 的是 systemd,不是宝塔、不是部署脚本

`spring_chengyinhub-admin.service`(`Restart=always`)。**部署脚本现在走 `systemctl restart`**
(PR #82 起):此前它是 kill 掉进程、赌 systemd 抢先拉起,**能跑是侥幸不是设计** —— systemd 慢一拍
就会和脚本自己 nohup 的那个抢 8080。现在 `ci/deploy-restart.sh` 开头有 `systemctl cat "$SERVICE"`
前置检查,找不到服务就报错退出,**绝不退回 nohup**。

密钥由 systemd **drop-in** 注入:`/etc/systemd/system/spring_chengyinhub-admin.service.d/*.conf`
(`secrets.conf` + `pii.conf` + `deepseek.conf`,共 7 个 `CHENGYIN_*`,均 600 root:root)。
⚠️ 2026-07-17 起主单元里那行 `EnvironmentFile=/var/tmp/...` **已被 drop-in 用空赋值重置**,
不再是密钥来源 —— 详见下方 §9「生产机信息」。

⚠️ `/www/wwwroot/bin/chengyin.env` **是孤儿文件,没有任何东西 source 它** —— 2026-07-16 实证:
DeepSeek 密钥放在那里,结果一直没进 jar,线上报「AI 模型 deepseek 未配置 apiKey」。
**加密钥 = 加 systemd drop-in + `daemon-reload`,别碰 chengyin.env。**

### 远端约定
| 远端 | 地址 | 用途 |
|---|---|---|
| `github` | git@github.com:liboyang42-cpu/chengyin.git | **主仓,push 这里**;PR/合并都在这 |
| `origin` | codeup.aliyun.com/…/chengyin.git | 镜像备份,**一般别手动 push**(mirror 会自动同步) |

---

## 2. 日常开发→上线流程

```
1. 本地开分支写代码           git switch -c feature/xxx
2. 提交 → 推 GitHub           git push github feature/xxx
3. 开 PR                      gh pr create --base master --head feature/xxx
4. 等 GitHub CI 的 8 个 required check 全绿
   (Detect changed areas / Backend tests / Mini program static checks / Admin UI build gate /
   Secret scan / SBOM + dependency scan / Migration layout gate / Migration safety gate)。
   PR 会按变更路径跳过无关的 Backend、Admin UI、SBOM、Migration job；先通过
   `Detect changed areas` 才允许这些 skipped 结论成立。Admin UI E2E contract 是额外检查，不在
   required contexts 内。
5. 审阅 + 合并 PR 到 master    ← 唯一人工动作
6. 自动:GitHub CI(master)→ 镜像 Codeup → deploy.yml 在 Mac runner 构建 → 部署 ECS
7. (小程序改动)另做微信上传   cd chengyinhub-xcx && node scripts/preflight-release.js → 按 docs/发版checklist.md 上传
```

- **合并 PR 就会触发生产部署**,合并前务必确认 CI 绿、内容 review 过。
- **小程序前端**改动:ECS 部署对它是 no-op,真正上线给用户要走**微信上传**(第 7 步)。
- **后端**改动:合并后自动打 jar 部署 ECS,无需手动传 jar / 登服务器。

### ⚠️ 不是所有合并都部署 —— 按文件分类

`deploy.yml` 的 `prepare` 只在改动命中下面这些路径时才真部署:

| 命中 → **部署** | 不命中 → **自动跳过** |
|---|---|
| `pom.xml` | `chengyinhub-xcx/**`(另走微信上传) |
| `chengyinhub-{admin,common,framework,generator,quartz,system}/**` | `docs/**`、`*.md`、`*.reference.*` |
| | `.github/**`、`ci/**` |
| | **`chengyinhub-admin/sql/**`**(要在库上跑,不进 jar) |

- ⇒ **文档/小程序/CI 的 PR 想合就合**,不会无谓重启生产。不必为了省部署次数把不相干改动塞进同一个 PR。
- ⚠️ **合并前自己跑一遍分类,别照抄 PR 描述**。实证:一个自称「只改 workflow/文档」的 PR 因为带了
  `chengyinhub-admin/pom.xml`,合并即触发真部署。
- ⚠️ **Mac runner 必须开机联网**。机器一睡,合并了也不会部署,**且零告警**:
  `gh api repos/liboyang42-cpu/chengyin/actions/runners --jq '.runners[]|"\(.name) \(.status)"'`

---

## 3. 唯一例外:数据库迁移(必须手动)

**绝不能让自动部署直接改生产库。** 涉及 `migration_*.sql` 时:

1. 走 `chengyin-db-migrate`:本机 python3+pymysql 直连生产库(库账号 `chengyinhubdb`,密码在宝塔→数据库)
2. 先跑只读预检 `chengyinhub-admin/sql/_precheck_migration_status.sql`,看哪些列/表 ❌待落
3. 确认幂等/无高危 → 执行迁移 → `--describe` 复核
4. **迁移必须先于合并 PR**(合并即部署;晚了新 jar 会报 `Unknown column`)
5. `deploy.yml` / `ci/deploy-restart.sh` 里**不得**含自动跑迁移的 step,数据库密码也**不得**进 GitHub Secret
6. 纯 `chengyinhub-admin/sql/**` 的改动**不触发部署**(不进 jar),落库始终是人工步骤

> MySQL 8 注意:`ALTER TABLE … ADD COLUMN IF NOT EXISTS` 是 MariaDB 语法,MySQL 8 会报错,用普通 `ADD COLUMN` 或存储过程守卫。

---

## 4. 怎么确认部署真的生效

**唯一算数的办法:问生产它在跑哪个 commit。** jar 里带着 `git-commit-id` 打进去的
`git.properties`,它自己会说:

```bash
ssh -i ~/.ssh/chengyin_deploy -o IdentitiesOnly=yes root@47.103.124.64 \
  'unzip -p /www/wwwroot/bin/chengyinhub-admin.jar BOOT-INF/classes/git.properties'
# 输出的 git.commit.id 必须 == git rev-parse github/master
```

workflow 里这层由 `ci/verify-deployed.sh` 自动做,三条断言任一不过即红:
① 磁盘 jar 的 sha256 == 本次构建产物 ② 恰好一个进程在跑它 ③ 该进程启动于本次部署之后。

### ⚠️ 下面这些都**不能**当证据

| 看起来像证据 | 为什么不是 |
|---|---|
| `curl` 返回 200 | 只证明「此刻有进程在 8080 上答话」,不证明是新 jar。旧进程答得一模一样。 |
| 200→502→200 回弹 | 重启窗口可能只几秒,轮询必漏。2026-07-16 实测 **100 次采样零 502**,而部署其实成功了。 |
| PR 显示 MERGED | 2026-07-16:8 个 PR 全 MERGED,jar 四小时没动。 |
| workflow/流水线绿 | 同上。构建绿 ≠ 制品传上去了 ≠ 部署了。 |
| 部署器自己写的 SHA 文件 | `chengyinhub-admin.deployed-sha` 是**存档不是证据** —— 它是部署器写的,读回来只是复述自己。真正的证据是 jar 自报的那个。 |

### ⚠️ 查 Deploy run 别用「最新那个」

`workflow_run` 触发的 run,其 `headSha` **恒等于默认分支 tip**,跟触发它的 CI 是哪个分支无关。
2026-07-16 实证:一次 master push 后出现三个同 SHA 的 Deploy run(success / cancelled /
skipped),其中两个是 PR 的 CI 触发的噪音,`--limit 1` 抓到的恰是被 concurrency 挤掉那个
`cancelled`,险些误判成「部署被取消、生产在挂」。**按 SHA 找 `conclusion=success` 的那个。**
(已在 `deploy.yml` 的 `workflow_run` 加 `branches: [master]` 从源头消除噪音。)

---

## 5. GitHub Actions 文件

| 文件 | 触发 | 作用 |
|---|---|---|
| `.github/workflows/ci.yml` | PR→master / push→master / 手动 | `changes` 路由 + 8 个 required check：后端 JDK11 `ci/backend-test.sh`、小程序 `ci/xcx-check.sh`、后台 UI build、Secret/SBOM、迁移布局/安全门；PR 按变更路径跳过无关作业，master push 全量跑 |
| `.github/workflows/deploy.yml` | GitHub CI 完成(仅 master 分支)/ 手动 | **CD 本体**,只做编排,逻辑全在 `ci/` 脚本里(2026-08-26 抽出,与云效 Flow 共用同一份):`ci/deploy-classify.sh` 判该不该部署 → 构建 jar → `ci/verify-built-jar.sh` 断言 commit → `ci/deploy-push.sh`(preflight 问库 → rsync → `ci/deploy-restart.sh` → `ci/verify-deployed.sh` 回读)|
| `.github/workflows/codeup-mirror.yml` | GitHub CI 完成(仅 push+master+success) | 把已验证 master 镜像到 Codeup |

> JDK 坑:Maven Compiler 用 `--release`,JDK8 报「无效的标记 --release」→ CI 用 JDK11(temurin 11)。

---

## 6. 常见问题

**Q: push 到 github 超时 / `Connection closed by 198.18.0.110`?**
A: clash 把 github.com 的 git 流量(22/443)路由到 fake-ip 丢包。**切一下 clash 节点/模式**即通(同 Codeup push 的老坑)。注意 `gh api`(api.github.com)可能仍通,但 `git push`(github.com)被断——现象不一致别误判。

**Q: 部署了但用户没看到小程序变化?**
A: ECS 只部署后端。小程序前端要走**微信上传**(第 7 步)。

**Q: 能直推 master 吗?**
A: 技术上 fast-forward 可以,但会跳过 PR 审阅+CI 门禁。**规范是走 PR**,合并即部署。

---

## 7. 本轮(2026-07-13)做了什么

- ✅ 迁移:14 项待落 schema 全落库生产(提现资金/分账/客诉/day_note/附近商家/隐私注销/多图),存档 `sql/_deploy_pending_20260713_run.sql`
- ✅ CI/CD 重构为 GitHub-first,repo 侧两个 workflow 配好
- ✅ **首次端到端验证**:合并 PR #1(cy-btn batch1-5 纯前端)→ CI 绿 → 镜像 Codeup success → 后端 502→200 重启,证实 Flow 自动部署跑通
  - ⚠️ **2026-07-16 回看:这条「验证」的判据本身是错的**。502→200 回弹只能证明「有东西重启了」——
    而重启 jar 的一直是 systemd 的 `Restart=always`,不是 Flow 的部署命令。同一个回弹,
    Flow 的内联密钥注入(`source chengyin.env`)其实从未生效过,DeepSeek 密钥因此漏了三天没人发现。
    **用「有没有重启」来验证「部署对不对」,是这份文档埋下的第一个假绿。**

## 7.1 本轮(2026-07-16)换轨

- ❌ **云效 Flow 出局**:构建核分耗尽 + 制品存储满(制品上传 400 → 部署静默不发生,jar 停在 14:09
  四小时,期间 8 个 PR 全绿全 MERGED,零告警)
- ✅ **CD 迁到 GitHub Actions + 自托管 Mac runner**,首个成功 run 部署 `3de735c5`,
  **由 jar 自报的 `git.commit.id` 坐实**(不再靠 502 回弹猜)
- ✅ `git-commit-id` 打进 jar + `ci/verify-deployed.sh` 三条断言(指纹/进程唯一/启动时间)
- ✅ 查明 **systemd 才是真 supervisor**;DeepSeek 密钥改走 systemd drop-in 后实测进了进程 environ
- ✅ `deploy.yml` 按文件分类,②⑤ 与纯 sql 不再空跑重部署;`workflow_run` 加 `branches: [master]`
  消除 PR CI 触发的噪音 run
- 🔧 worktree 分支盘点(见 §8),资损 quota PR 已备好待推(卡在 clash)

---

## 8. Worktree / 分支盘点(2026-07-13)

30+ worktree 盘点结论。`git cherry` 确认:有独有 commit 的分支**没有一个已被 master 收编**,均为真·未合并。

### A 档 · 有真实价值,走 PR
| 分支 | commit | 内容 |
|---|---|---|
| `fix/xcx-quota-overflow` | 3(已摘净) | **并发行锁+事务防额度超发(资损)**。已 cherry-pick 到 `pr/quota-overflow-fix` 待推 |
| `[历史工具路径或分支标识已移除]` | +4 | AI 切 deepseek 真模型上线 + 头像404兜底 + 迁移语法修 + 清悬空引用 |
| `[历史工具路径或分支标识已移除]` | +9(脏) | 徽章引擎 + wall-v2 + 成就页 + 迁移 |
| `feature/xcx-ds-cells` | +4(脏·在origin) | cy-cell/cy-search/cy-upload 组件 |
| `[历史工具路径或分支标识已移除]` | +1 | 修用户服务协议断链 + 注册 agreement 页 |
| `feature/shell-custom-tabbar` | +3 | 统一自定义 tabBar |
| `feature/xcx-quickwins` | +2 | nav-bar 遮挡修 + play/privacy 次级按钮 |
| `feature/xcx-float-avoidance` | +2 | 浮动控件避让 |
| `feature/xcx-workbench-todo` | +2 | 工作台待办重构 |
| `feature/xcx-retire-toupiao-world` | +1(在origin) | 下线投票/世界页 |

> ⚠️ 摘 commit 开 PR 时注意剔除过时基建提交(如 `--release 8` build、为其做的测试降级),master 已改 JDK11,带回会回退 build。脏分支先处理未提交文件(多为本地 `project.config.json` dev 配置,不该提交)。

### B 档 · 核对后大概率弃
- `fix/java8-ai-scene-router` / `-clean`:master 已有另一套 Java8 兼容修 + 现 CI 用 JDK11,大概率不需要。

### C 档 · 可删(17 个无独有 commit 空壳)
`feature/{deploy-package,admin-merchant-audit,api-bugfix,game-mode,qa-release-audit,xcx-ui-consistency}`、`fix/deploy-package-path`、`integration/github-codeup-baseline`、`[历史工具路径或分支标识已移除]`、`obsidian-doc-review(detached)`。
> ⚠️ `feature/ci-ds-gate`、`[历史工具路径或分支标识已移除]`、`[历史工具路径或分支标识已移除]` 带未提交脏文件,删前确认不要。
> 删法:`git worktree remove <路径>` + `git branch -D <分支>`。

---

## 9. 生产机信息(2026-07-13 从旧 Codeup runbook 合并)

> 旧 `docs/CICD_云效_runbook.md`(Codeup-first,MR 流程/Codeup 分支保护/deploy-restart.sh)已废弃删除。以下仍有效的生产机事实并入此处。

后端生产机 `47.103.124.64` = **systemd 服务 `spring_chengyinhub-admin`**(⚠️ 2026-07-16 更正:
此前本文写「宝塔 Java 项目,非 systemd」,是错的。`systemctl cat` + `journalctl` 实证如下):

```
[Service]
EnvironmentFile=/var/tmp/springboot/vhost/env/chengyinhub-admin.env   # ← 2026-07-17 已被 drop-in 重置为空
ExecStart=/bin/bash /var/tmp/springboot/vhost/scripts/chengyinhub-admin.sh
Restart=always                                                         # ← 它才是重启 jar 的那个
User=www
```
主单元(在 `/usr/lib/systemd/system/`,**不是** `/etc/systemd/system/`)仍是上面这样,
但 **2026-07-17 起密钥已全部不在 `/var/tmp`**:drop-in `secrets.conf` 里用空赋值
`EnvironmentFile=` 重置了那一行,5 个核心密钥(DB / DRUID / WXPAY_APIV3 / JWT / WXMINI)
改由 `Environment=` 注入。**7 个密钥现在全在
`/etc/systemd/system/spring_chengyinhub-admin.service.d/`(`secrets.conf` + `pii.conf` + `deepseek.conf`,均 600 root:root)。**

⚠️ **为什么必须迁走**(两个叠加的杀手,都零告警):
1. `/usr/lib/tmpfiles.d/tmp.conf` 有 `q /var/tmp 1777 root root 30d` + `systemd-tmpfiles-clean.timer`
   active ⇒ **连续 30 天不重启 → atime 老化 → 密钥与启动脚本被清 → 下次重启永久起不来**
   (`EnvironmentFile=` 无 `-` 前缀,文件缺失即启动失败,而 `Restart=always` 会无限重试无限失败)。
2. 宝塔面板 `/www/server/panel/class/projectModel/javaModel.py` 的 `start_project()` /
   `__start_springboot_project()` 里写死 `public.ExecShell("chmod -R 777 /var/tmp/springboot/")`
   + `public.writeFile(script_file, start_cmd)` ⇒ **在面板点一次「启动/重启」= 密钥变成
   全服务器可读写 + 启动脚本被整个覆盖**。CI 部署(`deploy-restart.sh` 走 `systemctl restart`)
   **不走面板,不触发**。

**⇒ 运维铁律:重启后端用 `systemctl restart` 或走 CI 部署,别在宝塔面板点这个 Java 项目的启动/重启。**
**⇒ 给 `/var/tmp/springboot/vhost/scripts/chengyinhub-admin.sh` 加 `set -e` 是白改**——面板会覆盖它。
启动脚本仍在 `/var/tmp`,靠 `/etc/tmpfiles.d/springboot-keep.conf`(`x /var/tmp/springboot`)防清理。

ℹ️ 那个启动脚本里的 `source .../chengyinhub-admin.env` **自始至终是无效代码**:env 里全是裸
`KEY=VALUE`、**零 `export`**,`source` 出来的只是 bash 局部变量,传不给 java 子进程。
jar 拿到密钥靠的**只有** systemd 注入 —— 2026-07-17 前是主单元的 `EnvironmentFile=`,现在是
drop-in 的 `Environment=`。**那行 `source` 无论文件在不在、权限如何,都不影响 jar 拿密钥。**

ℹ️ **`ci/deploy-restart.sh` 的安全网(2026-07-28)**:主 unit 的 `EnvironmentFile=` (无 `-`) 若因
tmpfiles 清理而文件缺失,`systemctl restart` 直接失败。`deploy-restart.sh` 在重启前补建空文件
(0600 root:root,**绝不写入密钥**,目录 0700)。已有文件不会被覆盖或改权限。密钥仍只来自 drop-in。

- jar 路径:`/www/wwwroot/bin/chengyinhub-admin.jar`,运行时 JDK21(`/www/server/java/jdk-21.0.2`)
- **部署脚本 = 仓库里的 `ci/deploy-restart.sh`**,由 `deploy.yml` 经 ssh 管道喂进去跑。
  ⚠️ 2026-07-16 更正:此前本文写「不使用仓库里的 deploy-restart.sh,该脚本没上传服务器」——
  现在它就是部署链路本体,**改它会直接影响生产**。
- **加密钥 = 加 systemd drop-in + `systemctl daemon-reload`**。⚠️ `/www/wwwroot/bin/chengyin.env`
  是孤儿文件,没有任何东西 source 它(DeepSeek 密钥曾放那里,结果一直没进 jar)。
- ⚠️ 宝塔「Java 项目管理」的守护若开着,会和 systemd 抢 8080。
- 回滚:`/www/wwwroot/bin/chengyinhub-admin.jar.bak.<时间戳>`(每次部署自动留),`mv` 回去
  `systemctl restart` 即可,不必等重新构建。
- CI 脚本映射:`ci/backend-test.sh` → `mvn -B clean test`;`ci/xcx-check.sh` → 全量 `.js` `node --check` + `.json` 可解析。
- 部署链路脚本(2026-08-26 从 `deploy.yml` 内联 shell 抽出,GitHub Actions 与云效 Flow 共用):
  - `ci/deploy-lib.sh` —— 共用底座:`log`/`warn`/`err`/`die`、`sha256_of`(macOS `shasum` ↔ Linux `sha256sum`)、
    `jar_commit_id`、`ssh_pin_identity`(身份钉死)、`retry_ssh` / `retry_ssh_stdin`(只对 exit 255 重试)
  - `ci/deploy-classify.sh` —— 输出 `sha` / `should_deploy`。base 取**生产 jar 自报的 commit**,
    中间丢多少次部署都能在下一次自愈
  - `ci/verify-built-jar.sh` —— jar 自报 commit == 本次要部署的 SHA
  - `ci/deploy-push.sh` —— preflight 问库 → rsync 传 staging → `deploy-restart.sh` → `verify-deployed.sh` 回读 → 存档
  - ⚠️ 这四个文件已登记进 `ci.yml` 的 `paths` 过滤,改它们会触发 backend job;漏登记会让 H8 门禁静默 skipped。
  - ⚠️ `ci/test_preflight_h8_schema.sh` 盯的是**三个文件**:接线本体 `deploy-push.sh`、上抛 `deploy-lib.sh`、
    step 逃生键 `deploy.yml`。`deploy.yml` 里那个 step 必须保持 `run: |` 块标量形式,否则 step 级负控会静默空跑。
