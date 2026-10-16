# OWN-C06-30 同根因补充 — 积分本周收入

SOURCE_DELIVERED_UNVERIFIED；NOT_RUN。不新增独立 bug 计数。未执行 Java/Maven/MySQL/SQL、lint、语法或其他验证。原 OWN-C06-30 快照不修改，本快照在其后按顺序附加。

真实路径：ApiUmsMemberController.getPointsStatistics（约169行）→ UmsMemberServiceImpl.getPointsStatistics（约121行）→ pointsDetailService.getWeekPointsIncome → UmsMemberPointsDetailMapper.selectWeekPointsIncome。

积分投影与余额原 SQL 使用同一 DAYOFWEEK(CURDATE())-2 偏移，周日会从未来周一起查，漏掉本周已入账积分。仅将 selectWeekPointsIncome 两处同改 WEEKDAY(CURDATE())；原 member_id、change_type=1、del_flag=0、SUM/COALESCE、半开窗口及其余积分 ledger SQL 不变。不新定义周界或积分收入方向。

扩展现 OWN_C06_30Test：真实加载生产余额和积分两份 XML；新增积分最小投影表与周日/次周一两方法。积分周日预期10（当周一4+周日6），排除前周99、次周500、支出700、删除800和他人900；次周一预期500。全类现在5方法，均 NOT_RUN；原余额三个方法和断言保留。

沿原30专用MySQL门控、显式host/非空隔离suffix与同连接SET timestamp；测试创建删除隔离 <base>_c06_week<suffix> 库，不改全局/主机时间。未来日期fixture仅边界探针。编译、真实数据库与API/设备验证均延期，本项不宣称通过。
