# 固定资源容量与混合故障 child — SOURCE / NOT_RUN

parent：fa5c263440573992701263d6bee60830844a49b8。

本片不创建 schema、网络、容器、账户权限或应用启动器。复用
authenticated-redis-recovery.sh 原 prepare、真实登录、mysql_relay、
只读 bind mount、内部网络和 exact-owned cleanup。

当前执行器 unavailable，源码以聊天补丁交付；没有新提交或文件后像
SHA。Python/Node/Java/DB/HTTP/故障/CI/编译全部 NOT_RUN。控制使用的
替身只验证协议和计算，不能当成实际 HTTP、资源采样或清理证明。

## 有界工作负载

原 auth 资源配置不变：app/MySQL 各 1 CPU、1 GiB、无额外 swap；
Redis 64 MiB，保留其原 CPU 配置。原 SQL pool=3/maxWait=5000/
socketTimeout=5000、Redis timeout=5s 不变。

数据规模为每个 dictionary 的 1/16/64 行，每规模12个 task-owned
key，累计972行；并发1/3/6，每阶段24个请求，9阶段216个请求。
每阶段12次 dictionary 内容读回与12次原actor读回。后续阶段可能
命中已填充缓存，不声称全部 cold；不通过全局清缓存造 cold。

客户端每阶段最多 concurrency 个 outstanding futures，无额外
预提交等待队列。它不是服务端实际并发数的证明。

请求成功、明确认证拒绝、系统失败、UNKNOWN、transport timeout、
transport error和内容合同不符分别计数。P50/P95/P99包含失败和
超时的已发送尝试；throughput分别给出完整响应和实际成功数量。
不定义RPS/SLO。

## 混合故障与业务证据

只在本task的sys_dict_data表持有写锁；以processlist中同schema/
startup_probe用户的3个真实SQL等待证明饱和，不能以线程未完成证明。
随后pause精确owned Redis CID，并在派发原lease释放前再次确认两种
故障同时存在。

原refund_application手工lease夹具只占用/释放lease，永不派发付款。
claim回包中的原lease须与数据库actor/token/live expiry一致；
故障释放须明确401且整行不变。恢复后原lease只释放一次，按原列级
合同读回，重放拒绝且整行不变。generic500仍单列SYSTEM_FAILURE，
不被扩展成通用“合法拒绝”。

原资金、权益、角色、菜单和凭据投影前后摘要必须相等。保护表摘要
不公开原值。未确定的命令先停止精确owned应用，再恢复依赖，禁止
自动重试、换号或重新登录掩盖问题。

## 采样与身份

每阶段记录source/tree/run/attempt、profile、容器/网络ID、Java PID、
容器PID和MySQL UUID；JAR校验原git.properties及host/mounted前后
字节摘要。没有独立下载的大JAR字节验证，不借用其他run的制品。

每阶段定期采集Docker CPU、cgroup内存usage/limit、PID数量和独立
MySQL processlist连接/阻塞数。Docker PID含curl/tracer，内存不是
JVM heap。应用executor queue、native pool waiter、heap/cache
bytes没有可靠采样点时明确UNKNOWN，不能据此签全资源有界。

## 终态与限制

observe成功仅为OBSERVED_SCOPED_CURVE_AND_MIXED_RECOVERY。
原cleanup后必须调用finalize，再独立验证3个容器和网络的原ID/name
全部不存在，才能得到PASS_SCOPED_CAPACITY_MIXED_OBSERVATION。
失败首因、原exit和部分曲线保留；cleanup未知不能晋升。

provider/AI/地图/通知、支付回调隔离、路线缓存淘汰、大列表业务、
实例接管、全业务恢复、容量上限和Q4/Q8完整验收均未覆盖。
