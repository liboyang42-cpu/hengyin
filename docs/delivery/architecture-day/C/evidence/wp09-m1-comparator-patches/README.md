# M1 比较器两项修复提案（不含原始制品数据）

结论：原制品调查定位到冻结比较器的两个合同错误：SQL外键异JDBC类型被分桶、DATETIME(0)合法秒舍入端点被排除。这里只交**两份review.patch、公开合成模型负控与本地重放脚本**；冻结`1c138a000387583e224c9acb5369140fdf2dba18`和root CI三文件均未改。root审查后方可采纳新规则。

原账本、余额、完整trace、XML/JSON与逐文件核验记录保留在本地`/workspace/c-m1-run40061448735/`及本地review工作树。原数据不会出现在本公共提交。下列反例全部使用原公开比较器单测的synthetic模型，不是原CI记录。

## 1. member-fk-join.review.patch

公开DDL中会员PK是BIGINT UNSIGNED，账本member_id是有符号BIGINT。observer原样保留BigInteger与Long；旧比较器用含javaType的完整JSON作为外键关联键，同数值形成两桶，导致守恒误拒。

提案仅用Decimal精确数值完成这条SQL跨列外键关联，再累计至原typed PK桶。原initial/final typed PK集合、raw typed行/元数据、输入、金额、SQL、事务、锁序及canonical输出仍严格保留；不转float、不改原ID。增加孤儿member与数值重复身份拒绝。合成负控证明：异型PK/FK可关联，但跨source同列类型漂移、同case PK类型变化、金额偏差、丢ledger、错member、ROLLBACK和反向锁序仍拒绝。

## 2. ledger-rounding-endpoint.review.patch

独立合成反例：窗口[1000737,1000791]，同一已提交INSERT的绑定Date为1000770；DATETIME(0)按秒舍入保存1001000。原上界1000999错误排除该精确端点。提案**不整体扩大时间窗**，只在以下证明全部成立时接纳：

- 原已登记persisted字段`ums_member_points_detail.create_time`，原值恰为high+1，其他字段/参数范围不动；
- 原列DATETIME且scale0，sql_mode不含TIME_TRUNCATE_FRACTIONAL；
- 原trace有成功并已COMMIT的同会员/事件/前后余额/变动额/类型ledger INSERT；
- 其createTime绑定在实际case窗口内，加原codec差值、按秒舍入的精确结果必须等于原保存值。

无绑定/错误身份/ROLLBACK/未来绑定/不同精度/其他列/截断模式/端点再多1ms，均不能得到此权限。没有新增日期白名单、时钟容差、金额或原始行归一。

## 验证与root接手

- `test_member_fk_review.py`：新增13个合成控制PASS，输出`proposed-controls13.txt`；反例先证明旧规则拒绝，再验证提案及负控。临时应用patch，不改冻结源文件。
- 原16控制源码逐字不变，在临时提案模块下16/16 PASS，输出`proposed-original16.txt`。
- `replay_original.py`：root对已有原制品在本地复放；先执行冻结read_bundle的source/XML/CLEAN门禁，再临时应用两补丁、比较各阶段并检查原JSON对象未变，记录完整来源hash及实际行/trace。输出必须在公共仓库外；不生成假的业务回执。

```sh
PYTHONDONTWRITEBYTECODE=1 python3 docs/delivery/architecture-day/C/evidence/wp09-m1-comparator-patches/test_member_fk_review.py
PYTHONDONTWRITEBYTECODE=1 python3 docs/delivery/architecture-day/C/evidence/wp09-m1-comparator-patches/replay_original.py /path/to/extracted-run --out /tmp/m1-review
```

root先审外键关联，再审严格舍入端点；若批准，按此顺序把两patch应用到冻结比较器的独立受控副本，产生新工具SHA后再由root决定CI引用。**本报告提交仍含原冻结工具，直接将CI指向报告SHA不会自动应用patch。**

本地完成原数据核验、Python控制与离线复放；没有运行Java/数据库/CI。原CI FAIL不被重标，离线提案结果需root独立复核。仅C本目录有差异；未更改两侧候选、CI、生产或任何原金额/断言/原行。
