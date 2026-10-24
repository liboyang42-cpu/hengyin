# b626编译门禁定点修补

Parent `b626f425625ccae793aa84a44147fe557c8df6ef`；只改F gate/producer现有class-reference判定、policy、专属控制。无生产/Mapper/SQL/旧baseline/commands修改。

root已独立核实run40135082606/job120994889568的真实source/JAR/raw/index及11条findings；其中C City→Ticket常量耦合由C `e1f22fda55b0a26a4261eba49dcd048932cbbec6`消除，本片不授该方向。其余10条仅下述4向：

- G_CASH_CLAIM→B_VERIFIED_PAYMENT_FACT_DTO：原接口+实现各只向外层DTO/$Reference（4条），原公开入参。
- B_PAYMENT_ROUTER→同DTO：仅Router→外层DTO/$Reference（2条），原验证事实id/hash交原owner，不授$Kind。
- B_VERIFIED_PAYMENT_FACT_STORE→B_PAYMENT_ORDER_LOCK：仅FactService→WechatPaymentOrderOwner及$Snapshot/$MissingOrderException（3条），原业务行锁/缺失拒绝证据；不授Jdbc具体实现或其它companion。
- B_PAYMENT_INTENTS→B_VERIFIED_PAYMENT_FACT_STORE：仅IntentStore→FactService外层（1条），现有hasConfirmedExternalSuccess重发/关闭/重试前布尔守卫，不授内部类。

owners.exact_directions列明10个class对，gate强制这4向的约束存在且非空/无通配/无重复/owner匹配；producer仍从原raw/index逐边检查，超出合同即OWNER_REFERENCE_OUTSIDE_CONTRACT。原反向/God/SCC/3privateMapper/缺输入UNKNOWN/身份哈希校验保留。class粒度不证明方法级资金授权，实际API合同由B/G原源证据确认。没有新parser/框架/任意incoming grant或baseline增长。

实际8项定点Python控制PASS、0skip，1.169s（7新增+原全方向正控仅接精确witness选择，原断言保留）。均为明确隔离Git/ZIP协议fixture，不冒充真实编译。secret/diff静态检查后提交。

F日志读取Transport closed，artifact12576774110连接器下载后URL及gh下载均403；F不声称独立回读raw/JAR。真实制品证据采用root已核实结果：JAR SHA256 4a4a3fe4434b96aa4aa7971ab52b74b109fa8ec5d63a0e73d6d900479a251661。旧b626仍保留C方向1条阻断，禁止篡改原raw/index消音。

Java/DB/全套回归/新CI与真实compiled negative/restored均NOT_RUN；原run后者SKIPPED不能移称通过。root合C源码与本完整delta，唯一repin双ref/tool后统一实际执行。六可信SHA见trusted-files.json。
