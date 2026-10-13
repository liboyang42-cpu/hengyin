# 云端A 订单票务与原账修补

从 `liboyang42-cpu/hengyin` 的 `codex/mini-foundation-cloud-handoff-20261013` 开始，使用独立任务分支。先核 `docs/handoffs/mini-foundation-20261013/README.md`、COMMON、OWNERSHIP、EXECUTION的A1–A5和items。资料已在仓库，不要求用户上传本地附件。你不独占代码库，不回退其他改动。

按重要程度完成A1–A5中原规则下的确定修补，并查对应原ID的实际小程序调用方：

1. 保留已提交PAYING扫描；补unpaid稳定游标、固定本轮MAX、每页上限、单项失败后继续和原资源唯一释放；未知支付不误关。
2. 探店日/自由探索订单完整到期链：静态/动态出码、扫码解析、直接核销、原请求重放、玩法资格及自动维护（C拥有的玩法Controller由你给纯策略/查询合同，C接入口）。恰好结束拒绝新用、缺结束暂禁、补齐恢复；未用过期不核销。重读当前归属和支付状态，防退款后旧快照继续用；保留非探索票与历史原行为。
3. 原退款、候补、已付未出票的真实失败恢复与调度公平；不新增退款或补偿政策。
4. 已有回调重复/乱序、总容量/票种库存/既有限购并发、原来源奖励券恢复与核销幂等、订阅start/end现态。原限额不扩大，不猜普通票新限购数。
5. 已有分账规则快照、待结算/可提现/提现中原账一致性及业务审计触发；未确定的新银行流程或合作补偿单列后置。

本包没有票务补丁附件；基于当前公开源码实现，不引用旧临时包。先写合同和文件清单，再改本包文件。源奖励券Service/Mapper由你；ApiRoamController/RoamMapper及漫游触发点归C，给C必要方法合同，不改其文件。RegistrationCheckoutServiceImpl归你，报名原通知意图和触发由你补，消息投递层归B。PlayTeam/报名资格查询归你，NPC生产Controller归C；保留现行查询合同。

参考固定源码与兼容性见EXECUTION参考表、reference-rules及reference-items；优先城瘾已安装/已有实现，不整套引入Python或Java17框架。所有日期、权限、钱和库存场景按COMMON保原规则。

持续完成本包代码、全部现有调用方、测试及可执行验证。Java/MySQL/Redis用批准VPS或仓库既有GitHub CI，连接未具备时完成候选并明确未验，不在云端另搭DB、不问已定规则、不伪造通过。单测、真实数据库竞争/回滚、原资源独立回读分别报告。

只改所有权白名单及本包 `docs/verification/mini-foundation-cloud/A/`；列每个原ID的已做子范围、仍缺代码、未验链、UI/新政策后置。完成后提交并创建draft PR（base交接分支），不合并/部署；回报提交、文件、证据、跨包合同及精确剩余断点。不要仅交分析计划。
