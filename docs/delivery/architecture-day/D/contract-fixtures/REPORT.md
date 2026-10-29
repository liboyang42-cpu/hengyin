# D：FULL CI 广播测试夹具增量修复

结论：只修测试，不变更生产实现/授权规则。缺失 operator 的 Mockito 输入应明确为 null；BL-80 不应截断完整统计方法；CL-20 应把被测页面放在当前页面栈，让联合候选的真实生命周期守卫执行。新候选独立于频控 db5db8，不覆盖已冻分支。

## 身份与范围

公共基线 2ec68899099a1e250df0949a8383bf85b5d3ffe0。增量唯一父提交为冻结 e71777f375fcbb19a17b46647c36391ec03ed3b4，父树 7a836874abeec1d9af3a23770dcc5d7e6868a4cf；独立分支 codex/architecture-day-d-contract-fixtures，工作树 /workspace/architecture-day-d-contracts。本地 HEAD 仍为公共基线，在独立工作树重建 e717 树后仅加本次测试增量；远端提交明确以 e717 为 parent。

协调者提供 FULL CI run40016642215，已读其 shard14 job120630506232 与 shard17 job120630506247 日志，确认 CL-20 两例和 BL-80 失败位置。后端 job120630505611 日志下载返回 Transport closed，后端错误以协调者给出的原 XML/NPE 信息结合源码核查，不宣称本地复现了 Java NPE。

联合源码观察身份 089790aaf3254f62ebbc79394b8aaf5fa66cb2b5。CL-20 的 director.js 通过公共 GitHub 按该 SHA 读取，测试仅临时在 Node 模块加载时使用这份原样源码，其他依赖未改；它们不在联合候选生产差异中。没有写入/暂存 C 的 director.js。

## 根因与最小修改

1. OfficialBroadcastSubmissionControllerTest.missingOperatorMappingStillRejectsWithoutPublishing 原来未 stub resolveAuthorizedOfficialMember。Mockito 对盒装 Long 默认返回 0L，不是实际实现中授权缺失返回的 null；controller 的 null 守卫因此未命中，并对未 stub 的 publish 结果取 getId，产生 NPE。真实 OfficialEventV2ServiceImpl 会验证后台账号、ACTIVE 映射和有效官方会员，任一不满足返回 null；controller 的原 null 检查存在。测试现在明确 thenReturn(null)，断言拒绝及“未绑定有效官方会员授权”文案，并 verify 事件读取、账号42授权解析，两个发布入口零调用；verifyNoMoreInteractions 防止走其他 V2 操作。没有把 saved mock 成成功来掩盖缺失授权，没有删测试或修改成功预期。Controller 直调 fixture 的零 service 调用是边界证据，不冒充真实数据库授权集成测试。
2. batch4-api-contract BL-80 原按 500 字符截取 reachStats；新增 channelReached/byChannel 后 clicked 超出截取范围。改为匹配完整方法，并保留 API 发布者/白名单和 V2 scope 断言；现在精确断言 reached→countInappReached、clicked→countClicked、channelReached→countReached、byChannel→channelStats，另检查人数/点击 SQL 只认 inapp SUCCESS。没有删 reached/clicked 或混算微信 UNKNOWN/失败。
3. CL-20 的 getCurrentPages 原恒为空，联合候选 C 的 _isDirectorContextCurrent 正确将其视为退栈页面，命令零提交、草稿不清空。只将 fixture 的 ctx 加入真实页面数组，不 stub/替换生命周期或授权守卫。原断网留草稿、重开带回、拒绝留草稿、确认成功才清空断言全保留；额外断言 UNKNOWN 保持锁定，重复点击不增加发送。该场景是玩法导演台广播，不是官方广播送达统计，不能因同名 BROADCAST 而改 C 的生产规则。

文件仅三份现有测试及本目录交付证据。生产 Controller/Service/小程序页面、schema、CI、鉴权、资金规则均无本次差异。没有新依赖或新测试执行框架。

## 实际验证和未验证

完整 Node 输出见 verification.txt：

- 修改前：BL-80 3/4（clicked 被固定窗口截掉）；CL-20 在 e717 页面为2/2，在原样 combined089790aaf director 模块为0/2，与 CI 的 0!==1 / 草稿未清空一致。
- 修改后：batch4-api-contract 4/4；CL-20 旧D模块2/2、combined模块2/2；official-broadcast-stats 4/4；official-pages-state-contract 7/7；管理端提交合同3/3；管理端枚举合同7/7。
- 上述共29项执行、27个不同用例，失败/跳过均0。git diff --check 通过。没有删除失败测试或增大 baseline。
- 未运行：本次 Java compile/JUnit、MySQL、新候选联合 FULL CI。未自行 dispatch/rerun CI，未在云 VM 起 Java/数据库，未执行 DDL。
- 协调者另报告 e717 已在 combined089790aaf 的真实 MySQL run40016650848 中 OfficialBroadcastDeliveryMySqlIT 7/7 且 CLEAN；这是其受控执行证据，不扩展为当前 fixture 修复或 db5db8 新并发 IT 已通过。

集成人将本增量单独 cherry-pick 到联合候选，保留原缺失授权用例与其余三个 Controller 用例，核对新鲜 Surefire 4/4、零 failures/errors/skips；Node 按正常现有 manifest/shard 运行，不增加跳过或修改 CI gates。db5db8 delta/新增第8项 IT 由协调者独立集成验证；其频控分支和源码身份不动。

## 迁移/回退/仍阻塞

无生产字段变化或迁移，无只读数据库查询或 DDL 执行。回退仅使测试 fixture/统计断言重新与联合候选脱节，不删除业务数据或回执。尚待受控 Java Controller 重跑和联合 FULL CI；其他 shard 中的 roam/revocation 等失败不属于本次独占范围，未擅改。

本次按用户已授权的 CI 候选例外提交/发布；不代表正式集成、合并或部署。最终 commit/tree 和逐文件 SHA256 随候选传递。WP08 的已有审计候选保持原样，完整落地清单继续单独交付。
