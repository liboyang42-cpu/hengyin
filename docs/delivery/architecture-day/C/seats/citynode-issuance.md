# 据点签发归位（机械片）

父提交：322875cf84cce61161876e12fd37bfb68b568a1e；独立工作树/分支沿现有 C seat-verification。

`ApiVerifyController.issueCityNodeCode` 完整准入→签名→本地 PNG→原 Ajax 迁入既有 `CityNodeRedemptionApplication.issueCityNodeCode(long,Long)`，Controller 只取 actor/委托。复用既有 Roam、密钥，新增已有 Qrcode bean 依赖；五分钟 TTL 继续唯一引用 RegistrationTicketApplication。无第三服务、无签发事务。核销 REQUIRED/原校验及副作用顺序不动。收益是同一据点签发/核销合同变更只落一个 owner。

5 处既有构造装配迁移；移除的 Controller 字段不保留 nullable fallback。原 47 Java case 名与业务断言保留，新增 1 个真实 Spring 代理签发不启事务的测试（未运行）。两个相关 Node source 文件先实际 11/11；新增签发 source case 随最终候选重新检查。git diff --check 通过。Java/编译/MySQL/启动/HTTP 未运行，由 root 联合验证。

API/参数/签名 type/TTL/错误文字/权限规则/数据库结构均不变；回退此机械片须连同构造 fixture 一起回退。回读及客户端 UNKNOWN 缺口另独立增量，不借本片声称已完成。
