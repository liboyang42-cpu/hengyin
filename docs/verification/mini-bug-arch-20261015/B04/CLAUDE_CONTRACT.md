# B04 排期读回合同

适用于共同基线 `d551c6babd018d41aec90dad2c0acaf1c534644d` 上的B04候选改动；Java/数据库验收未跑。全部接口保留现有登录及俱乐部 ACTIVITY_MANAGE 校验、AjaxResult包装，以下均为明确虚构测试fixture。

- `POST /api/club/event-ops/series/detail`：请求 `{"clubId":21,"seriesId":61}`；data示例 `{"id":61,"clubId":21,"topicId":31,"timezone":"Asia/Shanghai","startTime":"00:30","futureDates":["2027-01-17"],"version":0}`，其他原字段照旧。
- `POST /api/club/event-ops/series/list`：请求 `{"clubId":21}`；data为系列数组，新增相同含义的startTime；原列表futureDates仍为空，不新增分页/缓存语义。
- startTime表示既有未来有效场次共同的上海HH:mm。无未来场次、或场次分别为14:30/16:45时，为null（序列化若省略null，则缺字段同为未知）。不是持久化系列默认值，不代表过去场次。混合时刻不返回任意一场时刻。
- 编辑打开只将有效HH:mm写入当前表单；null/缺字段必须清空旧系列残留时刻，通过既有必填校验阻止保存，等待用户显式选择。禁止 `startTime || '09:00'`。创建态原默认不受此合同影响。
- `POST /api/club/event-ops/series/update-future` 请求原必填clubId/topicId/seriesId/expectedVersion/recurrenceType/customDates/startTime保留。startTime仅用于新增日期，保留的已有日期不改时刻。混合系列显式选择时刻不会重写历史场次。旧expectedVersion重放或并发冲突仍须重新读回，不能盲重试；不新增requestId合同。
- `POST /api/club/event-ops/series/occurrences` 请求同detail。未来ACTIVE场次成功报名0但有任意报名历史、结算或 WAITING/OFFERED/CLAIMED 候补时，data项 `{"activityId":71,"editable":false,"lockReason":"已有报名、候补或结算事实"}`。过去、取消、报名数未知沿用原原因。空场次仍可编辑；该读模型不取代写时保护/CAS。
- 缺少或无效startTime沿用400；状态/版本冲突沿用409 ServiceException业务码。前端保留服务错误和刷新路径，不把结果未知当成功。此次未新增错误协议、缓存、身份或产品政策。

客户端接线由协调者安排B01修改既有JS处理器/专属Node测试；本lane不改WXML/WXSS或页面。验收必须覆盖00:30重开追加日期、切换系列不残留时刻、null与混合不能默认保存，并由VPS/设备独立读回确认。来源及未跑项见README。
