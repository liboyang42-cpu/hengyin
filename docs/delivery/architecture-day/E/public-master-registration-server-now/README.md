# E：master51f0 报名 serverNow 最小接线

输入冻结：architecture `ff2b0ea1cf0ca3f097de446b5eda85beb3fad1a0`；master旧基线 `892efe6f765a5748065569fd886ee9b00ef71d4b`；master新输入 `51f05499c1523648a328f57bcd7927f6d9546003`。不追后续master。

GitHub compare确认：controller 在两master间只新增12行、零删除。将这12行逐字移至现有 RegistrationQueryApplication 的对应读业务：

- getRegistrationDetail：原权限与enrich完成后、成功返回前 setServerNow。
- myRegistrationList：原退款事实/筛选/投影完成后，原list返回controller包装TableDataInfo前 stampServerNow；不复制/更换list或增加startPage。
- myJoined：原结束时间过滤与排序后 stampServerNow。
- helper与master一致：每次列表调用只取一次epoch毫秒，null列表直接返回，null行跳过。原Date/退款截止/排序/分页语义不变。

薄 ApiRegistrationController 与ff2b逐字一致，仍负责原认证member和真实getDataTable边界；root解决真实controller冲突时可使用本提交该文件的完整blob，而非恢复master胖controller。Query除了对应12行没有其它变化。

原master ApiRegistrationServerNowTest三例及全部时间窗口断言保留，装配迁为真实controller→真实QueryApplication，协作者仍是原unit doubles；新增同批时间一致和分页total断言，另补一例helper null列表/null行/同批一致性。最终4个@Test；未删/跳过原测试。新test在ff2b不存在；root若已合入master51f0版本，可用 server-now-test.patch 原位迁移，或采用此候选完整test blob，不要重复应用。

必要依赖：CmsRegistration.serverNow及getter/setter由root从master51f0自动合入。本片不改domain/ApiActivityInfo/资金/前端/CI。因此不宣称ff2b加此片单独可编译。

owned.patch为相对ff2b的完整Query+test源码补丁。仅应用本提交增量；parent ff2b不携带root后续B/G/A/E资金修补，禁止整树覆盖root组合。初次shell无可用环境，公共connector先读；恢复后只创建独立 /workspace/hengyin-e-registration-server-now 工作树，未重置旧工作区。

检查：精确12行移植与块外字节一致、原三测试名保留、git diff --check、master测试patch可应用、有限新增行秘密模式。Java/JUnit/MySQL/Redis/微信/CI/完整gitleaks均NOT_RUN，由root一次统一验收。

## 完整source Git blob身份

- `chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiRegistrationController.java`：`744c64bc6c7ad519ac47d1289a5cf1013973ba0d`
- `chengyinhub-admin/src/main/java/com/chengyinhub/web/service/registration/RegistrationQueryApplication.java`：`25c569bddeedf1a59bd654397c9d7c11067f4a87`
- `chengyinhub-admin/src/test/java/com/chengyinhub/web/controller/api/ApiRegistrationServerNowTest.java`：`6882cee4ff32a696b4366d10967b814e1ff27e46`

## root 交付位置

本集成保留上述完整源码及说明；两份重复原始补丁保留在[供体 owned.patch](https://github.com/liboyang42-cpu/hengyin/blob/c372dda2058a2ac3fe7ff0607bb011d99eed05ee/docs/delivery/architecture-day/E/public-master-registration-server-now/owned.patch)与[供体测试迁移 patch](https://github.com/liboyang42-cpu/hengyin/blob/c372dda2058a2ac3fe7ff0607bb011d99eed05ee/docs/delivery/architecture-day/E/public-master-registration-server-now/server-now-test.patch)，无需在集成树重复存储。
