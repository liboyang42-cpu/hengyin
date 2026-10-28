# 发布草稿 / 开售审核夹具收口（SOURCE）

直接 parent: 2b68c327d0c6f247e76af108700ca626b6fb7197（D 的已转出 WIP）。
共同 helper: bc2b738fbd58dea6c8eabdb9d00eea22b21f364c；原公共失败基线: 347bcd0226c3bed71616e38ea7c1391fc76efe5c。
仅两个 owned unit 的最终差分，不修改 D helper、其它7类WIP、生产、SQL、CI。root 只取此 owned delta，不用本分支整树覆盖联合候选。

- 已逐字取得 WIP 两文件，以其实际新增 transactional(svc)、template ID、writtenChapters/writtenNodes 和原插入回填逻辑为输入；自己的先行草稿单独保留，没有丢弃现场。
- CmsTopicPublishDraftTest：同一 topic 的显式章节、地点、原输入题模板由两种读端口消费同一行；模板采用既有 TopicPublicationContentSnapshotTest 使用的 validationMethod=1/QuestionAnswer 普通题合同，不新建空高级配置来跳校验。原 ai_simple 确认坐标和坏 key 例分别保其实际节点/template，当前读跟随该原行。更新 Answer 应用真实 status/userStatus/checkStatus/reason patch，“第二次 read 已发布”的串行假时点改为实际更新后读回，避免正式多次锁读提前跳到已发布行。原金额、配额、媒体owner、显式actor拒绝、留置、审核状态、建壳/幂等断言不变。
- TopicSaleReviewDecouplingTest：保留 WIP 真实 insert 回填/记录；普通和 current 读均按 topic 过滤这些已写行。原存储作者的 productType/publishMode 明确为原 DTO 已指定的 ORIENTEERING/pro，更新读回反映同一 patch，避免空旧类型导致无价格变化也误判售卖条款变化。原5元/8元输入、canonical invalidate 次数与 pending sale task 刷新断言不变。
- 两类继续用 D 实际 public transactional(T)：真实 Spring TransactionInterceptor + DataSourceTransactionManager，controlled Connection 禁止 SQL。正式 key lock 回调新增 actual transaction 与原 DataSource 绑定断言；AfterEach 检查原资源释放/调用前事务状态恢复。没有直接设置 setActualTransactionActive，没有另建 TM/proxy 或 H2/数据库。
- 原 simple 成功例新增完整 runtimeSnapshotVersion=1、真实非 mock runtime owner 检查，原正式 freeze 不 stub；guard/compiler 真实执行取决于统一验收，不能把本 SOURCE 当执行证据。
- 347b 原全部 @Test/方法名及 assert/verify 保留；WIP 已写增量保留，仅补缺失前提。原14个发布失败 case 与2个开售失败 case 未删除、改名或 disable；不减金额/授权/UNKNOWN/错误类型/清理断言。

source-manifest.json 逐项给出347b、WIP前像与最终后像 blob/SHA256，便于精确采用。
owned.patch.b64 解码为仅两 owned 文件相对 WIP 的完整差分，SHA256: 540a3fb00b84b0dabdc6848da95fc8a35707b5eb5601521db6464c8c746369c8。文档不包含在该代码 patch hash 内。
执行仅源码读取/编辑、git diff/apply --check 和身份哈希；没有执行 Node、Java、解析/导入检查、编译、JUnit、H2、MySQL、HTTP、CI。全部 NOT_RUN。
真实 Spring 代理使用 controlled JDBC 资源不代表 SQL 原子性/回滚、MySQL RR 或线上审批/额度/渠道验收；原 client10767/HTTP36/64类708不重跑、不挪用其结果。
