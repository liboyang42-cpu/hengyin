# TopicPublicationBindingMySqlIT 类型装配修补

Parent：`fb871607e4c4a5a038776201ff0b785f999f9ab1`。对应 root 提供的 run40219238315 / job121247109763 testCompile:218。

实际第 218 行是 `TransactionTemplate.execute` 的泛型结果，并非 JDBC `queryForObject`：`TopicPublicationService.persistCreatedRouteSnapshot(Long)` 与 `CmsTopicMapper.publishRouteSnapshot(...)` 均返回 `int` 更新行数。先用 `Integer publishedRows` 明确该泛型，再以 `Integer.valueOf(1)` 比较，消除 JUnit 数值重载反向参与泛型推断的歧义。没有 cast、mock、额外转换或修改列类型；相邻 `queryForObject(..., Long.class / Integer.class)` 保持原样。

唯一测试源码改动为原一行拆成两行。全部 9 个原 @Test、普通与无模板两轮原交易、期望写入 1 行、原内容/ID/版本/金额/发布冻结/回滚/真库清理及其他断言均保留；生产与共享 fixture 无改动。

仅源码读取、补丁与 SHA 交付。测试、parse、编译、Java、DB、HTTP、CI 全部 NOT_RUN；未 dispatch。root 同源集成后统一验证。
