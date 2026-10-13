# 城瘾 Codeup MR 验收清单

> 一个 MR 只承载一个可独立验收、可独立回滚的目标。

## 创建前

- [ ] 分支只包含本任务提交；未混入其他 Worktree 或本地遗留改动。
- [ ] MR 目标分支为当前集成基线 `master`。
- [ ] 标题使用 `feat/fix/test/docs(scope): 中文摘要`。
- [ ] 描述包含目标、修改边界、验证证据、风险与回滚提交。

## 必经检查

- [ ] `git diff --check` 通过。
- [ ] 相关模块构建或测试通过；未执行的检查说明原因。
- [ ] 涉及写接口时已验证身份、角色、owner/scope 校验。
- [ ] 涉及 migration 时仅新增 SQL 文件，未直连或执行生产 DDL。
- [ ] 不包含密钥、构建产物、工具缓存或无关格式化。
- [ ] UI 修改已完成对应端的预览/截图或真机验证。

## 独立审查

- [ ] 用 `scripts/worktree-manager.sh review <待审分支>` 创建 detached 审查 Worktree。
- [ ] 审查者核对任务边界、关键流程、失败路径和回归风险。
- [ ] 审查 Worktree 内重复关键验证；结果写入 MR。
- [ ] 冲突文件、公共配置和迁移按合并顺序处理，未并行硬合。

## 合并与收尾

- [ ] 你已查看 Changed Files 与验证证据。
- [ ] Codeup MR 合并后，集成分支再次跑受影响检查。
- [ ] 工作分支已推送，MR 已关联回滚提交 SHA。
- [ ] Worktree 干净后执行 `scripts/worktree-manager.sh cleanup <绝对路径>`。
