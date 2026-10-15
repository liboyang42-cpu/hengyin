# 小程序bug与共用后端架构 Cloud 开工清单

> 三方统一协作规则：[CY-COORD-20261015-v1](COORDINATION.md)。当前文件归属与候选见[OWNERSHIP.md](OWNERSHIP.md)；初始规划不等于已领单。最终提示词：[Codex](START_PROMPT.md)、[Claude](CLAUDE_START_PROMPT.md)、[iOS](IOS_START_PROMPT.md)。

2026年10月15日。**只做小程序相关的既有bug与共用后端底层；本Codex/小程序Claude任务不改原生iOS、Flutter。原生iOS由独立agent负责。** Codex Cloud负责修bug和后端架构，Claude负责UI设计、新功能及其专属后端。共用后端只维护一套合同和业务真源。

iOS独立agent入口：[IOS_HANDOFF.md](IOS_HANDOFF.md)、[后端变化记录](BACKEND_CHANGES.md)、[给iOS的提示词](IOS_START_PROMPT.md)。共用后端所有者需向该agent提供兼容变化，原有Codex任务数量和工期不因此增加iOS工作。

本控制分支只维护计划/协调文档；业务已在独立草稿PR18形成候选，实际SHA/文件锁见OWNERSHIP，发布层级及CI见BACKEND_CHANGES。控制分支不是运行包，也没有测试部署证据。

## 启动方式

1. 在Codex Cloud选择公共仓库 `liboyang42-cpu/hengyin`，任务基线选择 `codex/mini-bugs-backend-cloud-plan-20261015`；若界面不提供分支选择，在启动提示词中明确先取该分支并保留已有工作。
2. 粘贴 [START_PROMPT.md](START_PROMPT.md) 中的完整提示词。一个协调者先做C00；支持子代理时按 [TASKS.md](TASKS.md) 的文件所有权并行，默认最多4个worker。没有并行工具则如实执行并给出独立lane提示词，不声称已经并行。
3. 已有PR先复用和评审。固定共同候选SHA后，再派B01–B05及A02的已确认AI bug修复；bug优先级批次收口后派A01/A03并继续A02架构部分；最后C99集中验收。
4. 产出草稿PR及证据，不自动合并GitHub PR、部署或上传。本轮已由当前协调聊天执行并发布PR18；不假定Claude/iOS收到通知，接收回执另列。

机器可读任务源是 [tasks.json](tasks.json)；逐单步骤/验收是 [TASKS.md](TASKS.md)；双方边界见 [CLAUDE.md](CLAUDE.md)。变更任务时同步三份，不把旧研究ID当已确认缺陷。

## 优先级与并行顺序

先修已发现且仍成立的bug，再做服务现有业务的后端架构；需要小部分UI设计或新增产品流程的部分交Claude。已有功能因接口/数据bug不可用仍由Codex修根因，新功能专属接口属于Claude，不能因为代码在后端就全部划给Codex。

| 阶段 | 任务 | 可并行关系 |
|---|---|---|
| 0 | C00 基线/已有PR/文件与合同核对 | 单一协调者；可只读取证，不并发改公共文件 |
| 1 | B01 小程序逻辑；B02 作者保存；B03 资产账务；B04 排期；B05 隐私；A02前半AI bug | 互不写同文件，最多4个worker；测试资源串行 |
| 2 | A01 会话回执；A02后半用量/额度底层；A03 剧情/版本兼容 | bug批次收口后，3个独占后端lane并行 |
| 3 | C99 集成测试和Claude合同交接 | 集中执行，不由每个worker重跑全量 |

## 当前公共基线与已有进展

文档分支基于公共master `8b7eb814e3db00aa2543fee489fad44f9aa7aec3`。已有集成分支 `claude/mini-foundation-abc-integration-squash-20261014` 的查询SHA为 `0b301c9b8d1853a86ed689c701eaad40cb3b70d7`。两者不同；不把其中任意一个默认称为最新发布候选。C00必须复核父链，必要时在新的公共候选分支组合经评审的公共提交，并把本任务文档带上。

| PR | 内容 | SHA | 当前状态 |
|---|---|---|---|
| [#11](https://github.com/liboyang42-cpu/hengyin/pull/11) | feat: 小程序底层A/B/C集成（CI全绿候选，draft） | 0b301c9b8d18 | OPEN草稿，未合并，检查返回0项 |
| [#12](https://github.com/liboyang42-cpu/hengyin/pull/12) | fix: 后端缺陷 B1 定位精度闸 / B4 主题点赞方向 / B5 俱乐部徽章泄漏 | a5af41f57fa3 | OPEN草稿，未合并，检查返回0项 |
| [#13](https://github.com/liboyang42-cpu/hengyin/pull/13) | fix(xcx): 游玩页底层机制(音频会话/退避轮询/定位精度/长按提示) | d6cd231e9354 | OPEN草稿，未合并，检查返回0项 |
| [#14](https://github.com/liboyang42-cpu/hengyin/pull/14) | fix: 任务2 外壳/商家/俱乐部修复（返回守卫、图片预览、定位兜底、打卡码文本、公告、门店认领） | 6a9647714286 | OPEN草稿，未合并，检查返回0项 |
| [#15](https://github.com/liboyang42-cpu/hengyin/pull/15) | feat: 任务3 漫游与功能入口（继续漫游/官方路线/收到的邮票/清除对话/举报队伍/积分明细/状态键） | 8884a678ea30 | OPEN草稿，未合并，检查返回0项 |

PR12–15目标是上述集成分支；PR11自身目标是 `codex/mini-foundation-cloud-handoff-20261013`。PR11标题含“CI全绿候选”，但本次查询没有检查记录，不能把标题当通过证据。

已有代码包括GPS精度、显式点赞方向、徽章查询范围、音频/常亮/退避、装修返回、图片预览、定位兜底、门店认领和部分功能入口。不可重复造。尤其保留以下缺口：

- PR14明确跳过退款申请授权B9，clubInterest/roamRecall模板仍未真实配置；group-code签名令牌没有明文降级，继续遵守既有约束。
- PR14的认领proofType/assetId语义待核；有局部字段非空不代表业务合同成立。
- PR15官方路线只有列表/详情，没有按路线开局；收到印章用POST，积分明细POST且后端一次全量返回；状态键仅已有主题run作用域。
- PR14作者报告仍有两项动作清单失败；PR15调整后只单文件复跑；PR13/15的门禁或清单变化需独立评审。作者测试报告不等于当前整合候选CI通过。

## 云端测试边界

代码编辑、静态核对和轻量Node测试在Cloud；开发/集成Java测试、MySQL/Redis和测试后端在专用VPS；额外CI使用公共GitHub托管runner。Mac不承担后端构建和测试。本批不新增/改workflow、runner或部署配置。

Cloud里的127.0.0.1不是Mac隧道，不能照抄本机转发端口。只使用环境中已授权配置的测试连接并验证实际服务/数据库目标；HTTP健康检查不能证明SSH/数据库原始TCP可达。访问不通时继续可做的源码工作与GitHub CI，把VPS/设备层明确标待验证，不转生产、不自建替代数据库，不上传SSH配置/凭据。

C00先复核当前GitHub workflow的触发条件；需要远程后端检查时可使用既有GitHub托管CI并如实记录。它不代替VPS联调或设备验收。后续新增矩阵若单独获准，max-parallel为20；20不是worker数量要求，VPS不跑Actions runner。

当前OpenAI官方说明要求Cloud环境配置项目所需服务访问，每项任务有自己的工作区；网络/服务凭据仍需实际配置与核验，不能由本任务文档推断已有连接。见 [Codex Cloud 官方说明](https://learn.chatgpt.com/docs/cloud)。

## 工期与关闭条件

本Codex包约34—57基础人日，统一25%预留后约43—72人日。按3开发＋1测试、每周15个有效人日，约3—5周；只是复现后可收敛的现有范围预算。先前“R1现有PR收尾8—14人日”只覆盖已写代码的集成验收，本清单额外包含剩余bug和三条后端架构线，两者重叠不可相加。Claude的UI/新功能、产品决策、VPS权限/外部模板/真机和发布等待另计。

每单关闭必须有：当前源码/环境与复现或反证、相关测试实际输出、独立业务读回、修改及兼容说明。已修/误报/裁决不做可以凭证据关闭调查，不能伪造一次代码改动。源码与测试、PR/CI、合并、迁移、部署、上传、设备和业务验收分层记录；未完成层保持待验证。

B05仅有用户明确批准的已删帖读取最小限制；不扩大鉴权/RBAC模型，不改Redis/JWT配置，不加依赖，不执行DDL或恢复小程序会费/群聊/群投票，不引入Boot3/新引擎/大规模重写。若具体修复撞到AGENTS要求的批准边界，先完成最小失败用例、影响范围、可审阅方案和回退说明，保持该变更待确认，继续其他独立工作。
