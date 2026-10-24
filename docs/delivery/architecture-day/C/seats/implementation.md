# C：逐席位核销事实候选（主题团核销接线）

本文件记录首批冻结1fbd范围；后续活动后端实际增量见 [activity-backend.md](activity-backend.md)，G读取合同见 [G-handoff.md](G-handoff.md)。

已实现主题多人票的稳定席位扩展、现有动态码/团扫码入口、防重和实际事实读取；不计算费用。当前实际公共基线 `06a571b1247620a9bdcea068be0ded973671d17c`。独立 `codex/architecture-day-c-seat-verification`；仅交新增席位源及两个Controller的精确hunk，不覆盖root/A/G其他更改。

## 真实调用与范围差距

全仓没有 `group_member_ticket` 表/领域类；现有 `scan_group_member_ticket` 走 `scanQrCode("topic",...)→verifyAndSettleForGroupActivity`，只能形成一条报名级 `coop_group_member_verify`。`order_num=3` 的多人行没有三个真实席位。多人下单真源位于 A 的 `createRegistrationInternal/createMultiTicketRegistrationsInternal`，一行数量可以大于1；当前公开 `create` 对owner_type=1会转单张selfPlay，**当前新建多人订单主要是owner_type=2活动票**。因此本候选虽完成现有主题团入口逐席位扩展，不能冒称活动多人票全链已经完成。

已向协调方提出必要范围澄清：活动多人票是否同批按“原核销权限/原收款方/原整单账务保持，额外逐席位事实给G”接入。活动票与主题票原结算分支不同，不能擅自从团入口把活动票结给主理人。A的`CmsRegistrationServiceImpl/RefundServiceImpl`未改；G金额算法未改。该澄清到达后再交精确shared review.patch或实际入口delta，不以整单数量替代事实。

## 已实现合同

- 新 `cms_registration_seat` 只扩展既有报名：稳定唯一 `(registration_id,seat_no)`。签发只懒建被明确选择的一席status0，不循环展开数量，不表示到场；不另建订单/静态票码/密钥。
- 复用 `VerifyDynCodeUtil` 现有v1 HMAC，签名type=`topic_seat_<seatNo>`，regId仍是原报名ID，原nonce/TTL只控制输送，不释放席位唯一身份。`dyncode/issue`原路径增加可选seatNo，原单席Java调用/响应不变。多人主题票缺seatNo直接拒绝，不生成整单替代码。
- `scan_group_member_ticket`只有服务端验签取得席位号后才进入新服务；身份从当前登录取。其他动态码族不能混入。原topic整单码对多人票在原主理人/场次归属闸后拒绝，不能默认全员履约。原单人/探索票分支未改。
- 新service在REQUIRED可写事务内锁topic→activity→club→registration→seat/group；先复用原IClubMemberService的主题/承接俱乐部主理人权限，并锁实际club owner，后写seat。锁报名使用专用flushCache/current locking read，随后回读原订单，不复用锁前Java对象；所有新增FOR UPDATE均useCache=false/flushCache=true。
- 首席复用原 `verifyAndSettleForGroupActivity` REQUIRED服务：原报名已使用标记、原扫描台账、原团归属/审计、原成长入队仍由原实现完成，其原payee=null不变。之后写本席CAS0→1和追加席位审计，任何错误外抛同事务回滚。第二席只记本席，不再执行整单入账。原单据第一次核销即挡自动退款的语义保留，不推测部分席位退款金额。
- 相同席位+原团重扫回原seatId/verifiedAt，不新增事实/审计；跨团拒绝；主理人移交后旧主理人拒绝。未签发身份、人工退款冻结、未支付/退款、已结算后的新核销拒绝。旧整单已核销但没有本系统席位证据，不补造若干已核销席位。
- 新schema CHECK要求status1的topic/activity/verifier/time全部非空有效；无历史backfill、无清库、无资金/回执/审计删除。只编写迁移，未执行DDL。

## 给 G 的实际接口

`RegistrationSeatService.verifiedSeatsForGroup(Long topicId, Long activityId, Date exclusiveCutoff) -> List<RegistrationSeat>`。

该入口MANDATORY可写事务，调用者先按其原结算锁序持有topic/activity及范围内registration锁，再读seat；不是在readOnly中FOR UPDATE。返回实际status1席位的稳定seatId、registrationId、seatNo、原topic/activity、verifier和verifiedAt；严格`verifiedAt < cutoff`，不分页冒充全量，不用order_num乘费。签发/未核销席位不返回。为避免RR子查询旧快照，历史歧义检测用锁定LEFT JOIN，不用NOT EXISTS一致性子查询。

**G不可将原报名级group事实再与seat事实相加。** 多人行只按实际席位身份计一次；原单席如何沿用已有证据由G保留原合同。已有多人group事实却没有任何匹配verified seat时，接口409明确要求人工核验，不能输出0或推成order_num。该接口只提供不可变发生事实，退款后历史事实保留；支付/退款/原履约窗/分项冲回资格仍由G原冻结算法处理。切换G读口及主理人团码/商家接待的联合证明仍由G接线，本候选未改G文件。

## 测试与实际验证

编写 `RegistrationSeatServiceTest` 11条：签发不履约/稳定身份、缺席位/越界、切主体、同席重放与第二席、支付状态事后变化时原回执仍优先且新席拒绝、不展开旧事实、跨团/移交、人工退款、原台账失败、歧义reader拒绝、签名type解析。`RegistrationSeatEndpointTest` 6条：真实原HMAC往返、篡改/异族拒绝、真实actor传递、缺席位拒绝、异主体、响应丢失重放。

`RegistrationSeatMySqlIT` 4条使用原canonical注册/团/club DDL及本迁移、真实Mapper：一席只计一席、双连接并发等待同报名锁/回读原事实、台账与席位CAS原事务回滚、旧事实不展开及MySQL3819 CHECK。它证明Mapper/数据库事务，不证明A整单账务/HTTP JWT；不是H2/Mock充作真库。显式HOST/PORT/USER/PASSWORD/suffix、精确自有schema/pool清理均保留；root统一调度，不新增CI任务。

实际只执行Python/XML/源码轻量核对、diff检查及限定秘密扫描；**Java编译、21条测试、MySQL/Redis/HTTP/CI全未运行**。原Controller单票/探索票用例未删除，需随root集中回归。当前小程序没有逐席位选择UI接线，本批没有改小程序生产代码；应按新明确seatNo/seatStatus契约由owner接入，不能继续对多人票展示整单码。

## 迁移预检/回退

部署前只读统计主题/活动多人行及现有整单核销台账；人工区分有无逐席位原证据，禁止order_num或verification_status反推事实。先单独授权执行新表DDL并读回唯一键/CHECK，再启新入口及G读口。表未部署时DB错误外抛fail-closed，不跳过落事实。应用可撤回新增入口，但已有核销后必须保留新表及事实，不能drop/清空回退；root/G需先保证旧算法不会重新把多人整单推成人头计费。

下一项：活动多人票按原权限/收款方串行接线，以及当前小程序明确选席位/原席位UNKNOWN回读；两者未完成，不报整条席位主线已验收。
