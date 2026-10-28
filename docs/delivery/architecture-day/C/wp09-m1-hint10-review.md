# root-owned hint10 精确装配补丁（C只交review.patch）

`evidence/wp09-m1-implementation/PlayHintUnlockMySqlIT.review.patch` 只改变原controller(...)测试工厂。原10个@Test、两个普通wrapper及其后所有正文逐字未变；AfterAll精确schema清理逐字未变。实际测试文件没有修改。

actor提取断言改为无应用事务；原实际可写TX/真实JdbcTemplate CONNECTION_ID/callerEntered latch整段移到应用首次nodes.selectCmsTopicNodeById测试桩，且在真实CurrentPlayRegistration锁之前。真实nodes/template目录、会员/账本/当前报名Mapper同一实例，不替换授权锁/扣分SQL/唯一键/并发或回滚断言。

新增装配调用PlayNodeAccessTestFixtures.setField与NodeHintUnlockTestFixtures.wire：后者将**M1-B单一九参Puzzle实现**用原DataSourceTransactionManager/TransactionInterceptor代理，完整hint业务自己持有事务。该patch依赖C完整M1-B源和两个专属test fixture，不应拿只有A的中间树去编译/执行；root只需审后与B候选统一应用，毋须再次向C确认。

C实际仅执行git apply --check及字节比较，未应用patch、未运行Java/MySQL/CI。root继续独占该文件、其10项和harness登记；新source必须重新真实执行，不借旧14绿。
