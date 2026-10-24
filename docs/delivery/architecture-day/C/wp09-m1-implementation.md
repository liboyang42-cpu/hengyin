# C M1 A+B：完整节点提示解锁归位候选

结论：已实现获批的完整unlockHint应用用例及共享准入归位，**代码候选完整，未通过新source受控验收，不能报WP09验收完成**。Controller只保留原HTTP/身份/结果包装和原签名投影桥；IPuzzle追加两API，单一九参Puzzle实现持有完整事务。14原准入算法只有一份，其他caller原顺序不改。无新UUID/expected/schema/依赖/@Lazy，余额两项P2保持独立，ledger→member锁序不变。

## 基线、分批和所有权

公共仓库hengyin，批准合同675a92aaa32260f57aa6ebdb2cb4b1f5c666714c；业务基线9a2dcf21aef4d7aec7a14065a2601bd86aaad805（合同涉及的63个源码/测试blob已与root6ec核同）。工作树 `/workspace/hengyin-c-w09-m1-contract`，分支 `codex/architecture-day-c-w09-m1-contract`。

- M1-A `8df8490d1f265012b5f39279d4c3fa2e5bb57c6c`：共享准入14算法、纯投影桥、必要fixture owner接线；不单独算WP09完成。
- `c5d6421286d13565e958149ab9302981a63ef5b9`：只交root hint10 review.patch与说明。此patch依赖完整B的test fixture；中间A树不用于验收。
- M1-B `c4606371361cce0ee09752de3f9b0723a6a93f65`：完整unlock/定价/事务、四原Puzzle构造接线、新direct应用测试及指纹/验证计划；与A合为完整候选。原675合同/B1冻结候选不重写。后续仅将指纹证据按symbol/file一行整理，并保留body/依赖hash，未改B的Java源；可用脚本重现或jq展开，不混入大段重复getter调用清单。

生产仅合同列六文件：ApiPlayProgressController、IPuzzlePlayService、PuzzlePlayServiceImpl、新PlayNodeAccessService、新NodeHintUnlockCommand/NodeHintUnlockResult。测试只改清单内必要owner装配及四个直接Puzzle构造，新增两个有限装配fixture和direct应用测试；源清单在source-proof中逐路径列明。D生产/复制业务、G资金、Theme/APPLIED、Photo共享Fixture、root ledger/UI/CI/架构baseline均未写。ChapterFlowWriteServiceTest只改原玩家isPrevDone表征片段的两条owner装配调用，保留其出版/拷贝正文，root集成须保留D同文件其他差异。

## 完整调用链与保持的合同

原小程序useHint→unlockHint POST原URL/参数和RateLimiter不动；currentMemberId可信身份→NodeHintUnlockCommand(memberId,nodeId,activityId,topicId)→IPuzzlePlayService.unlockNodeHint→节点/原scope解析→CurrentPlayRegistration→lead→route（原null actionId）→当前template→单一hintUnlockCost→普通余额预读→event14唯一流水INSERT→条件member扣分→原typed结果/Ajax包装。

Controller三参入口原事务移到公开应用方法：REQUIRED/DEFAULT/readOnly=false/rollbackFor=Exception，无新增retry/REQUIRES_NEW。应用不接受Controller/lambda，不反调Controller；不存在新RuntimeResolver或生产nullable fallback。原其他入口事务/GET无大TX/动作身份、版本和回执分流未改。nodes价格经同一Puzzle getter，nodes已解锁事实仍由原ledger读侧取得，保留原读取身份。

event14唯一身份仍member+14+node，跨activity/session/config不变；原已执行重复仍先授权/余额预查，再遇唯一冲突返回当前提示/cost0；不冒充首次immutable response receipt。无actionId/expected字段的原hint合同如实登记，未补随机ID。免费/停用、pointsNum/value、只hint2/null提示、原错误空格/逗号、当前template读取、insert返回值与RuntimeException捕获范围均保留。

新PlayNodeAccessService公开不可变NodeScope/ActivityWindow/Context/ProgressFact。内部私有领域算法逐条从修后B1移出（仅错误载体Ajax→Failure及私有progress符号机械适配）；Controller只做同值投影。Context保留报名id/purchaseKind，后续首次扫码CAS/当前读、权益核销判断不改。ProgressFact明确含**id/status/picUrl**，普通照片旧行machineApproveProgressId及原CAS/奖励一次性保护不动。lead原403原对象和依赖故障503/NOT_RUN继续抛出，故障不得当非带领；preference先lead的B1顺序保留，其他caller不统一改顺序。

原锁/副作用：activity锁→原报名候选/registration锁→原再次activity锁/时效→lead/原route→template/settings→普通余额读→**ledger唯一INSERT锁→member条件UPDATE**。新unlock不调用Puzzle旧lockMember，原六PuzzleAPI与三个私有辅助方法的9个原方法体保持。扣分抛RuntimeException仍对实际事务setRollbackOnly后回原“积分不足,请重试”，其他插入异常传播；绝不把返回失败当成可提交。

## root-owned hint10、其他fixtures与直接应用测试

root的PlayHintUnlockMySqlIT文件**未改**，交付 `evidence/wp09-m1-implementation/PlayHintUnlockMySqlIT.review.patch`。git apply --check通过；原10正文、两个普通wrapper、AfterAll清理逐字保持。仅factory把actor改为事务外，将原可写TX/真实CONNECTION_ID/callerEntered latch整段移到应用首次node读取桩（资格锁前），并用原DataSourceTransactionManager代理新应用。root单一应用该delta后才形成同源验收树，不能用本分支未适配的root-owned hint10运行结果当代码已通过。

C自身B1 IT4：hint工厂通过真实应用代理，actor事务外、应用node读取明确实际可写TX；answer的原Controller事务/claim回滚断言不动，preference仍无外层TX、真实route实现零进入及恢复建局断言不动。没有关闭assertTx或减少用例/清理。

PlayNodeAccessTestFixtures只在显式测试owner setField时重建真实access，后置换runtime/progress/mapper立即重接；若Controller当前接真实Puzzle应用，同步重接其access。没有在生产执行期间从Controller动态取依赖。普通no-lead是显式Mockito成功空行；明确null/throwing负控不被替掉。NodeHintUnlockTestFixtures把nodes价格委托真实定价，保留原Puzzle mock其他行为；完整hint测试调用真实应用，不能mock成功替代收费/rollback。其null transaction manager仅标识原plain unit，绝不当事务证明。

原PlayLeadAdmissionFailureTest的入口观察按迁移调整：允许调用新的准入应用入口，但保留原pricing/member/ledger/progress/reward/proof/terminal receipt零效果；Puzzle原六方法及pricing仍never，不把“应用入口被调用”误判成奖励效果。四个原Puzzle直接构造显式九参，无旧四参null兼容构造。

新增NodeHintUnlockApplicationTest **16个源码@Test，尚未运行**：不构造Controller，使用真实Puzzle/Access/当前资格算法和Spring TransactionInterceptor；叶子明确mock。覆盖活动/自玩/原scope回退、坏actor/node/topic/当前资格、集合/lead503与403、免费/null提示、价格getter无TX、当前正文重复、余额P2表征、插入异常、扣分写后抛错的真实Spring rollback-only、并发同身份一次逻辑扣分。BoundaryTransactions只证明Spring事务边界/完成状态，不模拟DB undo，不冒充MySQL锁/回滚。

## 指纹、实际执行和未验证

`evidence/wp09-m1-implementation/check-move.py` 可复跑，实际exit0。source-proof记录原Controller **106个方法、68个private实现/58个private名字**的旧→新owner/签名/body与依赖调用指纹，14算法转移/价格转移，其余旧Controller方法体token保持；原Puzzle 9个方法体保持；原经济片段除owner/typed返回映射外token一致，字符串字面值保留。构造调用均九参，59份旧源测试的已识别@Test等case名无删除，root hint10字节不变且patch可应用。改动/新增Java只做括号平衡和source hash，不是编译/AST/事务或业务等价证明。

实际执行：上述Python源检查、git diff --check、review.patch只读适用性检查、既有配置gitleaks秘密扫描。未执行任何Java/Maven/JUnit/MySQL/Redis/HTTP/Node/CI dispatch，无新依赖或Agent。源码测试数量不是Surefire执行数；源级等价不是受控T14实证。

根已核验的6ec旧32unit/full workflow SUCCESS、9a2原14真实MySQL与CLEAN只作为**修后旧基线**，不能借给M1新source。原94相关unit的根固定集合必须全留，新增direct16另加；source selectors仅帮助定位，不能用本源盘点缩减原94或完整回归。

## 新source验收、迁移与回退

精确计划见validation-plan.json：root应用hint10 patch后绑定完整集成SHA，统一编译、原94集合+全部受影响回归+direct16、真MySQL hint10+B1 IT4、startup/SCC与HTTP/RateLimiter/响应包装、root ledger/架构source owner回读。所有XML必须真实case名/0failure/error/skip/hash同源，hint/B1两组CLEAN。旧B1与新M1以独立scratch schema/同夹具比较响应、余额/event14身份金额笔数、报名/进度/奖励/回执、SQL/锁序/传播/查询数；只允许明确登记时间/生成ID归一，不忽略金额、权限、错误或状态。

余额预查导致已解锁后余额不足无法读回、并行其他消费的ledger before/after快照两项P2未修；继续由资金/root统一单列，不趁迁移反向锁序、改退款/唯一身份或缩小验收。

无生产schema/data/资金迁移；root-owned架构owner/命令参考登记、ledger重建另由root单一落地，不扩baseline/unknown handler豁免。机械回退只回已修B1，不恢复lead fail-open/preference先建局，不清账本/回执或回补余额。新source编译/回归/真库/启动/等价尚未执行前，交付状态为**完整可审候选，等待root同源验收**。
