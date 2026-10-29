# 固定 restore-application-startup 完整入口 SOURCE

已完成 shell/workflow/控制 SOURCE 的完整 review 增量；不含 C/root owned 文件，无假参数或 TODO。**全部 NOT_RUN**：未运行测试、bash-n、Python parse/import、编译、Java/DB/HTTP、容器或 CI。实际仅 Git 应用/差分检查、摘要和有限新增行秘密模式检查。

- parent `9496e7028ef3793ac19a1687f9391fff9ff33e72`；tree `ec60ba4c3f8c327ec393e1d4344d3f43ed6e2b40`。
- 分支 `codex/architecture-day-f-restore-http-entry`，独立 worktree，root 唯一集成/派发。
- C 实际源码 `c382451bddbfdaaaa1231a544605685f8863f7bf` + `bec4054ca7e95b2f44a104cd3f39588d700f4f65`。F 分支只存 owned 增量，必须由 root 组合 C/root 后才有可执行模式。root 未提交 CREATE USER `%%` 串行修补不在 F patch。

| owned 文件 | 调用/配置增量 |
|---|---|
| `ci/authenticated-redis-recovery.sh` | 原 prepare/strace 后、旧 app launch 前，仅新 flag 进入 target 创建→root restore-mysql-ready→C run；原 EXIT cleanup/private 清理后 C finalize，不带private argv，保原非零。旧正常/gameplay/capacity 分支保留 |
| `.github/workflows/isolated-authenticated-redis-recovery.yml` | 新手动choice；check/execution精确flag；C controls与F controls各一次；一次原package；只新模式55min，旧模式25；新模式仅三JSON，旧上传互斥。原contents:read/profile/网络/资源不变 |
| `ci/test_restore_application_http_entry.py` | 四个直接SOURCE控制：真实入口合同；固定模式/预算/门控/白名单负控；实际EXIT函数的shell-stub首错误/清理/finalize次序；实际run分支shell-stub只执行一次、readiness前置、失败/PR不执行。所有外部动作替换固定函数，不证明真实服务/清理通过 |
| 既有F handoff/README/摘要 | 同步C实际固定合同和NOT_RUN，不改COORDINATOR、不重审旧入口 |

完整参数/env/输出/限制见 `../final-acceptance-entry-wiring-90e/C-RESTORE-APPLICATION-HANDOFF.md`。C当前26控制不硬编码到执行条件，调用原脚本一次保原25+无参query控制。未删原case/断言/负控、七cohort/M1、registry或业务owner/金额/权限/UNKNOWN/恢复围栏。

`review.patch.b64` 是全部三执行/控制文件的完整diff；base64只运输合法diff context空白，decode后hash见`PATCH.sha256`。作者全部后像见`POSTIMAGES.sha256`，manifest不含自身；root只应用一次，不重复cherry-pick再apply。

失败规则：run非零不被cleanup/finalize成功掩盖；部分三JSON只是失败证据，缺失不造PASS。`if-no-files-found=error`只保证至少有artifact输入，通过仍须原C run/finalize、source/run/JAR/UUID/业务断言及root回读。55min是总上限，不是容量/恢复SLO或费用承诺。无生产派发/新审批/schema/资源升级。
