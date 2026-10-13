# 小程序底层云端交接入口

仓库：`liboyang42-cpu/hengyin`。选择交接分支 `codex/mini-foundation-cloud-handoff-20261013`；本目录与附件均随该分支提交，不需要上传本地文件。

业务源码基线为 `0f0c32f1edc955f6117108daecbb492652212931`。交接提交只增加资料、合同、补丁附件和任务指令，不把候选应用到业务代码；不是旧私库历史迁移。三包分别从交接分支建立独立工作分支，不能直接往交接分支或master写业务修改。

## 你需要给云端什么

环境绑定本仓库，任务选交接分支；三包各建一个任务，分别粘贴[任务A](./tasks/A.md)、[任务B](./tasks/B.md)、[任务C](./tasks/C.md)。如果界面没有起点分支选项，在首条指令里要求从该交接分支检出；先核本目录存在，不能仅凭环境名称假设版本正确。

环境与仓库/任务起点不同；已有环境可复用。参考[OpenAI云端说明](https://learn.chatgpt.com/docs/cloud)与[环境分支检出说明](https://learn.chatgpt.com/docs/environments/cloud-environment#container-caching)。没有在本次检查中验证用户账号的环境设置、网络或VPS连接。

## 必读与附件

1. [共同规则](./COMMON.md)：已确定政策、文件归属、禁止扩大范围及验证规则。
2. [交接说明](./HANDOFF.md)、[执行单](./EXECUTION.md)、[原编号对照](./ITEMS.md)。
3. [255条全量映射](./items.json)：全部原ID保留；90条底层与另165条混合项不能遗漏。
4. [所有权](./OWNERSHIP.json)：本轮云端分包按实际文件所有权执行，覆盖原领域归类。
5. `checksums.sha256` 与 [文件清单](./checksums.json)：从本目录核SHA256；包括所有交付文件，清单自身不参与循环摘要。
6. [公开检查](./PUBLIC_REVIEW.md)：扫描、原ID、链接、基线与补丁范围；不等于业务验收。

参考代码的固定链接、规则、许可/兼容性和REA边界已保留于 `reference-*.json` 及EXECUTION；没有把第三方完整源码仓库、原始REA归档或私有日志打包进来。

## 候选怎么用

| 归属 | 附件 | 状态 |
|---|---|---|
| B | `candidates/B-ci/candidate.patch`、manifest | NPC旧静态断言修正；局部26项通过，原整轮CI仍失败；先核真实调用与SQL，再提交并重跑 |
| B | `candidates/B-route/candidate.patch`、manifest、CONTRACT | 路线旧卡/摘要投影候选；只做静态/基线检查，Java及真实读取未通过本次验证 |
| C | `candidates/C-ai/candidate.patch`、manifest、base-manifest、CONTRACT、两份Lua | 十个源/测试文件的原日配额候选；100项单测及15项真实Redis验证待跑 |
| C | `candidates/C-ai/durable-refund-contract.json` | 持久补偿设计，未实现；不能声称退款已具备崩溃保证 |
| A | items、audits及当前公开源码 | 本交接没有可用票务补丁附件；A从实际公开源码实现，不引用失效临时包 |

三个补丁在交接业务基线分别静态可应用。SHA和应用检查不是编译、并发、真实身份或设备通过。按本包任务先核所有调用方，再决定应用和修正；不要只复制表里的函数名。

当前基线[CI](https://github.com/liboyang42-cpu/hengyin/actions/runs/38436649302)失败，9/10门通过。193个不同后端测试是既有提交的分批局部证据；不证明这三包候选、90条机制或新合并版本全通过。

原始工作簿、旧私库提交/备份、执行归档、凭据、原始日志和真机资料不在附件中。公开副本已去除本机绝对路径及无用临时路径；原研究完整留在本地。缺失的运行环境证据要明确保留，不创造假身份、假回执或假测试通过。
