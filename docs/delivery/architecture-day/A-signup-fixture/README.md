# A：D 报名通知依赖的资金 IT 夹具适配

结论：仅补 `RegistrationFundsMybatisIntegrationTest` 的手工通知依赖，保留原92项测试及全部余额、ledger、退款、积分、库存断言。没有改生产通知guard、支付算法、通知方法、B回调或真实Mapper写侧。**Java编译及92项MySQL测试未执行；根统一运行，本会话未dispatch CI。**

- 唯一仓库：`https://github.com/liboyang42-cpu/hengyin`。
- 开工HEAD/parent：D `b6267613693d0c067040002c3df431ac39b35feb`。
- 工作树/分支：`/workspace/hengyin-a-signup-fixture` / `codex/architecture-day-a-signup-fixture`。`1364aee3`、`dee20`、A/P2/其他候选保持冻结，不重建覆盖。
- 沿用用户批准的隔离CI候选commit/push例外；不正式集成、合并、部署或执行DDL。

已读 D 的 `docs/delivery/architecture-day/D/signup-intent/integration-notes.md`、接口及生产接线。首次付款通知现在要求真实事务与同步、正数原事件身份、原已付接收人一致、有效intent回执。该资金suite直接new业务Service，没有注入 `systemNotificationIntents`、`imService`；且有主题的正控会新增读取 `cmsTopicMapper`。不能用生产null fallback、移除guard或把正控改成拒绝来适配。

唯一代码文件为 `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/RegistrationFundsMybatisIntegrationTest.java`：

1. `createDatabaseAndService` 在原真实事务管理器/notice装配之后调用新增私有 `wireSignupNoticeFixture`，不修改原代理、回调事务桩或真实资金Mapper。
2. `wireSignupNoticeFixture` 按D合同显式将 `findIntentId` 返回null（Mockito默认Long0不能表示“无记录”），`enqueue` 返回正数1；`imService.deliverSystemNotification` 返回正数1。这里只是通知替身的成功回执，不表示IM intent真的落库/投递/去重或回滚。
3. `cmsTopicMapper.selectCmsTopicById` 用JdbcTemplate参数化SELECT和既有BeanPropertyRowMapper，从本suite现有 `cms_topic` 行读取；没有行就返回null，没有硬造主题、扩表或引入依赖。没有装配生产全列TopicMapper，因为原资金fixture只含明确的窄表字段；不声称覆盖生产TopicMapper SQL。
4. 增加3个import；原 `forgedClubMarkerFromPublicCreateCannotSurviveIntoPaymentFreeze` 的一行“未装配TopicMapper”说明改为符合现有夹具的说明，测试逻辑与断言原样。

通知保存/投递使用Mock，因此本suite不证明通知真实原子性；该合同由D18 `SignupNoticeAfterCommitPersistenceMySqlIT` 独立验证。本补丁保留真实生产guard，保留首次CAS、freshPaymentClaimed、历史只唤醒原intent和原首位ID来源；通知两方法及B payCallback逐字未改。

实际执行的轻量检查见 `verification.txt`：静态归一化移除新增装配/import/helper及一行注释后与D基线逐字一致，因此原92项方法、所有assert、SQL fixture DDL、资金/ledger/库存写入和其他helper均未改；D producer静态测试6项通过；项目秘密文件与diff检查通过。**静态测试不是Java/MySQL通过数**，未执行任何数据库/通知链路。

无schema/迁移/数据影响，无新依赖或通用框架。回退仅移除测试依赖装配，会使D通知正控再次无法通过；生产业务无回退变动。新fixture源码SHA在 `candidate-files.json`；最终候选SHA/tree随交付消息提供。不要整文件覆盖根后续独立fixture修复，按本分支相对D基线的delta集成。
