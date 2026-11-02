# 原请求回执的技术归属登记

本登记与产品修复分开提交并发布固定 verifier SHA，根协调者在回读增量后单独更新 CI 的两处固定引用。登记本身不修改 gate、collector、SQL/dependency baseline、业务写权限、私有 Mapper policy 或扫描范围。

核对依据：修复候选 a653ee8b721d2de169fa4d26d898c14dd1a529a7 中的 ApiAuthorWriteReceiptController、AuthorWriteReceiptService、AuthorWriteReceiptMapper。API 从当前会话取得 actor；Service 负责原号/输入校验、原子回执；Mapper 仅由该 Service 消费。原 Activity、Template、NPC Controller 可调用回执 Service；查询 Controller 只调用回执 Service。没有授权跨业务表写入。

只登记三个精确类和 API → Service → Storage 两条方向；Storage 禁止回依赖 Service，Service 禁止回依赖三个旧 Controller。未登记的同包类、Controller 直接消费 Storage、反向依赖及环仍须判红。原 M 系列登记的精确集合从 34 增至 37，既有成员、限制及方向不变。新增规则级正反控不代表真实编译或业务验收。

根协调者审阅范围：确认变动仅包含以上归属及对应精确测试，未增任何包级豁免、未删除旧限制；核对实际源依赖与登记一致。独立 verifier 固定引用随后由产品修复提交写入，实际 Java 和 MySQL 验证由配套 GitHub 托管 CI 提供。
