# E Photo IT 受控 CI 夹具适配

结论：仅修正真实 MySQL IT 的连接配置读取。父候选 `254355c84370229318dc061937c68704957e37c4` 原实现、source tree、报告和 ref 保留；本次独立子提交只改变 `PhotoCheckIntentMySqlIT.java` 与本报告。16 单元、10 MySQL 用例的行为和数量不变，Java/MySQL 尚未执行。

核对实际 `ci/real_storage_it.py:class_env`：受控 GitHub-hosted runner 向每个 MySQL 类显式提供 `CHENGYIN_AI_MYSQL_IT_PORT`、`CHENGYIN_AI_MYSQL_IT_USER=root`、`CHENGYIN_AI_MYSQL_IT_PASSWORD`（显式空），并清掉未登记的继承 CHENGYIN_*。父候选使用 Photo 自有变量，且统一拒绝 blank 会错误拒绝这个已批准的 scratch MySQL。因此本次直接复用现有 AI MySQL 三变量；PASSWORD 必须显式存在，允许其值为空；PORT/USER 仍必须非空，端口必须 1..65535。没有默认连接、凭据查找、生产地址、条件 skip、连接失败吞掉或备用数据库。

仍严格要求 localhost、真实 MySQL8+，每个类自建随机 `chengyin_photo_intent_<uuid>` scratch schema，真实生产 Mapper/XML、Spring 事务和独立 JDBC 回读。只清理自身创建的随机库，没有清理共享数据库/Redis。全部 10 个 @Test 均保留。

根的共享 registry/CI 文件由根单一修改，E 未改。建议注册内容：

```json
"com.chengyinhub.business.service.ai.PhotoCheckIntentMySqlIT": {
  "tests": 10,
  "storage": ["mysql"],
  "gates": {}
}
```

将该类加入根所选批次，registered_source 绑定实际待测完整 SHA。当前 runner 的 class_env 已提供所有三个所需变量，不需新环境变量映射。单元测试选择 `com.chengyinhub.business.service.ai.PhotoCheckIntentServiceTest`，期望 16；真实 MySQL 类期望 10，skipped/failures/errors 必须均为 0。根适配成功不替代这 26 个用例结果。

实际只运行：Python 确认补丁仅含变量名替换和显式密码空值策略、注解用例仍 10、16 单元/服务/Mapper/迁移逐字不变；secret-file gate；git diff --check。没有 Java、本机 MySQL/Redis 或 provider。缺完整 gitleaks，待根对该候选差异复扫。

父候选 E/PHOTO/source-manifest.json 与 check_static.py 描述并校验父提交原 7 文件，不冒称覆盖本子提交有意修改的测试夹具；本次精确差异以子提交和本报告为准。无 schema 改动，无运行门槛降低。回退只撤销本夹具适配，不删除任何 intent、回执、quota 或审计。
