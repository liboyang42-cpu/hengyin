# GitHub + Codeup 双仓与发布体系（2026-07-13）

## 目标与边界

- **GitHub**：唯一开发主仓，承载 PR、GitHub Actions、编码助手、Codex 与 Cursor。
- **Codeup**：受控镜像和阿里云云效的代码源；不再接受日常开发直接 push。
- **生产发布**：仍由用户在云效 Flow `5122099` 手动运行。GitHub/Codeup 同步不等于发布。
- **默认分支**：两端均为 `master`，避免既有 Flow、MR 和本地分支迁移风险。

## 流程

```text
feature/* → GitHub PR → GitHub CI 通过 → 合入 GitHub master
  → GitHub CI 通过 → Actions 镜像同一 commit 到 Codeup master
  → 用户在云效 Flow 5122099 手动发布 ECS
```

## 自动化文件

- `.github/workflows/ci.yml`：`master` 的 push 和 PR 均执行后端测试、小程序 JS/JSON 静态检查。
- `.github/workflows/codeup-mirror.yml`：仅当 GitHub `master` 的 push 所触发 CI 成功后，才写入 Codeup `master`。

镜像工作流使用专用的 Codeup 账户级 SSH 公钥（读写权限），并强制校验固定的 Codeup SSH host key。私钥和 host key 都仅存于 GitHub Actions Secrets：

- `CODEUP_MIRROR_SSH_KEY`
- `CODEUP_KNOWN_HOSTS`

## 一次性人工配置

1. 在 Codeup「个人设置 → SSH 公钥」新增本次提供的公钥，名称为 `github-actions-codeup-mirror`，范围设为**读写**，并设置到期日以便定期轮换。Codeup 仓库级“部署密钥”仅只读，不能用于镜像写入。
2. 在 GitHub 仓库设置中确认 Actions 已启用。
3. 在 GitHub `master` 分支保护中启用：禁止直接 push、要求 PR、要求 `Backend tests` 和 `Mini program static checks` 均通过。管理员临时绕过仅用于紧急回滚。
4. Codeup 的 `master` 同样保持分支保护；云效继续只从 Codeup `master` 构建，发布仍手动触发。

## 日常操作

1. 从 GitHub `master` 创建 `feature/<scope>` 分支并开发。
2. 推送 GitHub，创建 PR，等待 GitHub CI 通过并完成审查。
3. 合并 GitHub `master` 后，等待 `Mirror verified master to Codeup` 成功。
4. 需要上线时，在 Codeup/云效确认同步 commit，再手动运行 Flow `5122099`。

## 回滚

- 代码回滚：在 GitHub 对目标 commit 创建 revert PR；CI 通过后会自动同步到 Codeup。
- 生产回滚：在云效 Flow 选择上一成功制品重新部署。不要通过直接修改 Codeup 来绕过 GitHub。

## 验收标准

- GitHub 与 Codeup 的 `master` 指向同一 commit。
- GitHub PR 无法在 CI 失败时合并。
- Codeup 仅收到 CI 已通过的 GitHub `master` commit。
- 云效未被 Git push 自动触发生产发布。
