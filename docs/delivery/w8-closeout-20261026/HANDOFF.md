# 草稿 PR #625：不可合并或部署

十二条线既有源码已汇总上传；这不代表业务验收完成。用户批准本次收尾的提交前检查例外。新增功能开发已停止，仅处理已发现的编译、测试和 CI 缺陷。

- 草稿 PR：https://github.com/liboyang42-cpu/hengyin/pull/625
- 公开分支：feature/cy26-w8-closeout
- 已测试源码提交：2d7c5dfd99339ed24d43a97819c59e850e2fa275
- 首轮 CI：https://github.com/liboyang42-cpu/hengyin/actions/runs/40374590684
- 后续文档提交不代表新的源码验证；复验必须绑定下一次实际源码 SHA。

## 已核实结果

完整编译 PASS。测试 VPS 的 admin 定向测试运行 2360 项，17 失败、240 错误、0 跳过。
GitHub 后端测试运行 14413 项，27 失败、241 错误、638 跳过；不能将跳过项视为通过。
CI 最终 FAILURE。密钥扫描、依赖扫描、迁移目录检查和后台 E2E 合同门通过，其余失败见清单。

## 接手入口

- 交接总表.md：十二条线与既有证据。
- source-files.json：627 个源码路径及哈希。
- verification/test-targets.json：259 个目标测试文件及哈希；228 类离线单测、31 类 MySQL 测试分开列出。
- vps-unit-failures.json：VPS 单测去重诊断。
- ci-node-failures.json：前端失败测试、文件和位置；201 条记录不等于201个独立业务缺陷。
- github-backend-failures.json：GitHub 后端去重诊断。
- compile-errors.txt：首次测试编译失败历史；对应7文件修复已经纳入，后续完整编译通过。

## 下一步与边界

优先修共享应用层测试夹具：旧测试仍向已迁移的 Controller 注入字段。保留断言，不能给生产添加空字段或删测试。
然后处理真实行为失败、动态API路径、静默错误/危险操作登记和迁移依赖声明；不得放宽门禁制造通过。
只复验受影响项，完整稳定候选再跑必要CI，不重复已通过且源码未变的检查。

新商家商城功能暂停；已有源码保留。广播仅人工恢复，自动重试规则暂缓。
恢复模式B补丁未合入，仍NOT_READY；声音授权撤回和样本留存等未决政策不得自行决定。
发票未配置主体和处理范围时不可处理；守卫申诉通过仅允许仍有效原局现场重核，不直接完成或发奖。
未执行生产迁移、部署、真实支付或通知。MySQL、真实角色、设备和渠道仍需独立验收。
后端使用已批准VPS或GitHub-hosted CI，不在本机运行Java。VPS构建保持1536MiB/1CPU/预算锁，临时Maven堆1024MiB。

## 第一轮 CI 修复（仍为草稿，未全绿）

接收 6 个 Java 测试夹具文件和 9 个 CI/前端文件修复，逐包 SHA256 和基线内容已核验。227 项 Java 定向复测：33 失败、3 错误、0 跳过；编译通过。云端六文件 JavaScript 定向结果 75 通过，复用原证据，未重复跑。API 门禁仍缺 sensor/result 映射，其他失败继续处理。

repair-round-1.json 给出本轮增量文件哈希；vps-fixture-failures.json 给出定向失败。source-files.json 和 verification/test-targets.json 保留首轮 2d7c5df 源码清单，不能把其旧哈希当作修复后的哈希。首轮 CI 结果也仅绑定 2d7c5df；本轮尚未重跑完整 CI。

## 第二轮修复：仍为草稿，等待新 SHA 验证

接收17文件：真实审计入口延迟注入以解除启动环、资金计数20/98同步、sensor-result真实路径和Java/前端夹具。原资格/回执验证保留，未开启循环依赖开关。资金Python门禁45项全部通过；云端JS24项通过。Java及实际启动待新CI，照片审核与旧快照合同冲突仍保留原断言。

repair-round-2.json 是本轮增量证据；current-source-files.json 绑定父提交8bfe4f76的633路径，结合第二轮增量读取。additional-ci-diagnostics.json、backend-repair-round-1-failures.txt、static-repair-round-1-failures.txt 记录上轮未解问题，不等于本轮结果。
