# B 原订单范围测试源码（NOT_RUN）

基线 `6fcd5ac009cec1dd82c438528d24da30c1f302f4`；生产合同对照 `33fa0bafb39d5ba647bbcd866e7a5973d38dd053`。仅两 owned unit 文件及本说明，无生产/SQL/CI 改动。

- `CoopSettlementTest` 原 29 个测试方法正文逐字保留，包括金额、自动欠保证金50元、写失败和UNKNOWN断言；只补完整计划header/lines及共享测试装配。
- `CoopSettlementPayeeDedupTest` 改为14个真实服务调用声明，移除复制Set算法。包含NONE先后顺序、独立受控范围、交叠/缺失、原claim归属、逐单审计ID、商家数不按单翻倍、精确重放和原不支持CLUB路径拒绝。保留5秒超时。
- 其中4个场景经过真实冻结lineup/reader/prepare/evidence service，mapper仍是明确的单元替身；不证明真实MySQL SQL、持久原分项或渠道调用。独立正向输入直接控制每原单候选集，未冒充生产扫码已具备原单归属。
- 已执行：原29方法正文文本相等控制、声明计数、git apply --check --whitespace=error。Java/unit/MySQL/CI全部NOT_RUN，无业务红绿证据。

仍需root/G收口：33fa范围守卫再次调用requireSupportedCashPath，和保留的CLUB组合正例冲突；本片保留真实CLUB角色及原正向断言，不伪装MERCHANT或改成异常。root/G决定范围守卫与真实prepare支持性检查的最小接缝，B未修改生产。

生产独立多单仍待原订单到扫码/履约/销售选集的不可变归属接线。当前真实同商家REGISTRATION_SCAN选集相交应拒绝；受控独立范围正向保持成功期望，不能把该剩余项改为失败代替用户要求。
