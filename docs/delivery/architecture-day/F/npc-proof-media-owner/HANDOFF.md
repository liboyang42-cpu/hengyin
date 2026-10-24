# def9 对应的最终边界增量

实现parent e5b5818f2230eb9e81d2a825960c0470ac1640ec；root可应用基线def951bef36a94417d90259906f6bda6abe6efb3。最终review.patch会以def9生成，包含尚未集成的f35→e5b棘轮/C和本片NPC/E/H，不重交生产文件。

NPC：实际def9/c7/385/D6f+b8b核对；复用20f voice provider，新增实际voice/ASR服务和唯一intent Mapper namespace、STT interface/adapter/Noop/factory。2原AI事实+2NPC intent事实，3原private端口不变。namespace writer与编译private consumer分开：voice storage只原service，ASR storage只service/NpcChatWriteService/DataRetentionServiceImpl；非预期caller（含unowned或$Nested）、provider→domain/controller、缺raw均有负控。新增两表不授权ASR额度或UmsMember/profile表写权。def9真实ASR构造器无UmsMemberMapper，未按旧描述臆造字段权限。

E9f无新生产类型，VerifiedDomainPaymentProofTest/Fixture不登记owner。原ClubMembershipMapper/OmsOrderMapper只登记共享技术端口，不登记CLM/OMS整表业务fact、不新增caller通配豁免；普通订单与特殊Commerce的拒绝边界仍由H实际业务修复/测试证明，class graph不证明方法级资金授权。H0465三原类型/原outbox namespace保持，新增精确单向约束；未删除旧限制、未改H运行代码或预算规则。

最终84类型/70owner/79方向，4facts/3private ports。原所有members/module、forbidden限制保留（只新增），原2ec SQL及locking基线逐字不动。去掉schema/consumer元数据不得变成放行。264项Python控制实际通过、0skip，29.124s；此前两轮只处理受控范围数量断言与缺policy错误分类，旧场景断言保留。Protocol ZIP/raw均NOT_COMPILED，Java/真实JAR/jdeps/DB/CI NOT_RUN。

静态MySQL声明：NpcVoiceEnrollment3、NpcAsrIntent7、ClmCommercePaymentProof6、ClubPostMediaReviewBudget8，共24，与def9 registry一致；相对def9父提交，修改的既有已注册类没有声明case数变化。只读扫描，不是测试执行/清库证明。纯source scope探测def9无新未登记阻断，不是真GitHub source gate PASS。

原普通/特殊订单P1由H实施，ASR额度答案由root/用户裁定，本片均不代签。root统一实际验证及双CI ref；后置工具另提交固定最终verifier，不使用旧分支整树覆盖。
