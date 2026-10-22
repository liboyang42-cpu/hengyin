# 发布步骤（Lane R1，对应 Doc 1 §10）

> 本文件只是步骤说明，**本 lane 没有执行其中任何一步**：没有部署，没有连生产，没有执行 DDL，也没有上传制品。
> 所有涉及生产的动作都由 owner 操作。每一步都要留一份**独立回执**（命令输出、截图或哈希），不能拿后一步的结果冒充前一步。
> 主机地址、appid、上传位置、凭据都**不写进本公开仓**。

## 1 生成发布清单（manifest）

```bash
python3 ci/release_manifest.py manifest --commit <最终公开 SHA> --output release-manifest.json
# 有 gh 权限时可以加 --lookup-ci，按 SHA 回读 CI 运行记录（查不到会记成 LOOKUP_FAILED，不等于绿）
```

- 核对 `public.commit` / `public.tree`、`backend.modules`、`ci.lookupCommand`。目标 SHA 上的 CI 必须全部是绿的。
- `migrations.items[*].applied` 一律是 `UNKNOWN`，脚本永远不会判定是否已经执行。
- 清单是发布回执，**不提交进本公开仓**。如果要回填 appid、API 目标、备份 ID 这类信息，回填后的版本放在私有位置。

## 2 迁移差异

```bash
python3 ci/release_manifest.py diff --base <生产参考基线> --head <最终公开 SHA>
```

- 当前结果见 `migration-ledger.md`（基线 `19408d49`：到 master 有 3 个，到 batch 2 有 6 个，**全部需要 owner 批准**）。
- 对每一个迁移：先走 chengyin-db-migrate **只读预检**，回填 applied?，再由 owner 逐个确认。
- `20261013_admin_role_page_grants` 必须先做 checksum 对账（两个候选值见台账第 1 节）。

## 3 备份

- 执行任何 DDL/DML 之前，先做一次全库备份，记下备份 ID 和 sha256，回填到清单的 `backup`。
- 能做还原演练就做，结果填进 `backup.restoreTested`；没做就保持 `UNKNOWN`，不要写成通过。
- 同时记下当前线上 jar 的 sha256 和它内嵌的 git id，回填到 `rollback`，作为 JAR 回滚的目标。

## 4 私有 JAR 上传（deployed:false）

- 用 `.github/workflows/backend-jar.yml` 按最终 SHA 构建，制品名是 `backend-jar-<SHA>`，里面附带 `.sha256`。
- 下载后核对 sha256，并读出 `BOOT-INF/classes/git.properties` 里的 `git.commit.id`。这个值**必须等于** manifest 里的 `jar.embeddedGitCommitIdMustEqual`。
- 把 jar 上传到私有位置，此时清单里写 `deployed: false`。**上传不等于部署。**

## 5 部署顺序

1. 只读预检通过，owner 也确认过之后，按台账第 3 节的依赖顺序执行迁移。**会被新 jar 读写的表和列必须先落库**：`member_profile_version_20261022`、`roam_share_version_20261022`。
2. 替换 jar 并重启（owner 操作，走 `deploy.yml`，它会断言 jar 内嵌的 commit 等于目标 SHA）。
3. 部署后做健康检查，并回读接口；结果单独留一份回执。

## 6 小程序干净导出

- 从**最终公开树**干净检出：`git -C <新目录> checkout <最终 SHA>`，核对 `git rev-parse <SHA>:chengyinhub-xcx` 等于清单里的 `miniProgram.treeHash`。
- **不要复用旧的导出目录，也不要用旧的 `app.json`**。核对 `app.json` 的 blob 等于 `miniProgram.appJsonBlob`。
- appid 和 API 目标由 owner 在私有环境里配置，不提交到仓库。
- 主包上限是 2 MB。以开发者工具上传结果里的编译后主包字节数为准（`chengyinhub-xcx/scripts/wechat-release-contract.js` 负责回读），回填到 `miniProgram.mainPackageCompiledBytes`。`mainpkg-size-report.js` 量的是源码字节，只能作参考。

## 7 体验版 → 提审 → 发布（三份独立回执）

| 步骤 | 回执内容 |
|---|---|
| 体验版 | 上传版本号、对应的公开 SHA 和 tree hash、体验版二维码截图、真机冒烟结果 |
| 提审 | 提审单号和提交时间，版本号必须和体验版一致 |
| 发布 | 审核通过记录和发布时间，确认线上版本号 |

不能用一份回执顶替三步，也不能拿体验版通过当作已发布。

## 8 回滚

- **JAR 回滚不会撤销 DDL。** 回滚到 `rollback.previousJarSha256` 之后，新加的表和列还留在库里。所以本批迁移都要保证“旧 jar 加新 schema”能正常运行。
- 迁移注释里写的 `DROP TABLE` / `DROP COLUMN` 回滚语句**不会自动执行**。真要撤销 schema，只能另写前向迁移，并经 owner 批准后执行。
- 小程序回滚要在微信后台操作“回退到上一版本”，同样单独留回执。
