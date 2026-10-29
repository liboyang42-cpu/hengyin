# 原消费关联的旧路径拒退保护（源码候选）

Parent: `99d84ce23afe8852d642ba21d791662ad06a9ebd`。仅两行生产 XML：`insertComponent` 在存在 A 原付款消费关联时返回零行；精确关联由既有 `insertConsumedPaymentComponent` 写入。无关联仍须满足原 PAYMENT/DONE、业务号、TX、金额、冻结 scope 全部条件。A leaf、B fact owner、G 构造器/金额/副作用均未修改。

新增一个普通 `@Test`：`consumedAssociationDisablesLegacyDoneFallbackAndExactConsumedBranchStillWrites`。同一真实历史 DONE 下，分别覆盖无关联（只准旧分支）与有同事务 A 关联（只准 exact-consumed 分支）；临时 head/component/关联整笔回滚，余额/原头额/两原分项/债务不变。临时第三头只作存储约束探针，不代表冻结分摊方案或奖励/资金执行。删除新两行时，有关联分支的零行断言应失败；尚未执行此反例。

原11条 case 与整个旧夹具逐字保留，新源声明数12，无参数化测试。静态已执行：diff --check、XML解析与其余statement逐字比对、移除新增方法后全测试文件与parent逐字比对、有限补丁秘密模式检查。Java/MySQL/CI 均 **NOT_RUN**；没有性能/资金验收声明。

root 已组合 de7→6df875→24b570→8ca8f49 的 paid-half/旧批准退款/family recovery，及 A admission 接缝；本提交不重新覆盖。4d6f4a producer 仍为独立候选，不在这两行里重写。累计退款舍入、跨订单尾差、负毛利责任仍 NOT_ENABLED。无DDL。回退这两行会恢复旧 mapper 对已有关联的 fallback 能力；不能作为已消费资金数据的回滚方案。
