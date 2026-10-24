# 新边界登记棘轮与 C7c8

Parent f35cf5cc49215017669061950e39071bbb5ce2fa。旧 f35 的实际 source CLI 在隔离 Git/event fixture 中对新未归类 @Service NewApplication 返回 PASS；未归类 class 原编译检查也只计 unscoped，非强制登记。

最小 source 棘轮沿用 tree/blob、可信 compare-base、六文件冻结和原 inventory；原 2ec SQL baseline 不改。范围仅 owners.json 明列的四个既有区域：System business service/mapper、Admin web service/api controller。存量以公开不可变 477696c8eb2fa24bd3d7febc896415bf0d1b67c0 的路径+primary type/role 冻结，不扩展为经济/权限批准；改动存量给原 SHA/hash review witness。新增 public Service/ServiceImpl/Controller/Application/Mapper，或标准角色注解的 primary type 必须登记，否则 NEW_UNOWNED_BOUNDARY。旧 helper 升格同样红；DTO、实体、无角色 package-private/nested helper 不要求 owner。

去注释/字符串复用已存在 ci/real_storage_it.py 的纯 helper，来自独立可信 checkout（不是 candidate），没有第二套 lexer。名称/primary 注解只是窄确定证据；多类型、Unicode/路径不符、元注解/Bean 工厂、运行时/XML/间接调用不能签全覆盖。无法识别的 primary role、未定角色 Component/secondary managed type 明确 UNKNOWN；方法级读写仍 UNKNOWN。仅其它目录的新类型也不因此被批准。

C7c8：唯一新增 owner C_REGISTRATION_REDEMPTION_APPLICATION / admin / RegistrationRedemptionApplication，Actor 是 restrictive companion，不另建 owner。原 14 字段/13 distinct ports 保留；唯一新增允许方向 Redemption→Query，源第987行共享谓词。Query→Redemption、Redemption→两个 Controller/BaseController 拒绝。该 owner 的 Mapper 包引用通过同一 raw/index 读取约束，仅原 CoopOrderMapper 精确例外；其它未归类/新 Mapper、CoopOrderMapper$Foreign 均红。只是技术依赖例外，不授予 Mapper 写权或同端口任意写方法。71 types /59 owners /69 directions；3私有Mapper、2facts保留，无 ASR/未来类型豁免。

Python 最终249/249、0skip；新增边界、两提交绕过、helper升格、删owner/清scope/改baseline、历史基线正常修改、缺raw UNKNOWN、C原ports与反向/任意Mapper均有控制。协议ZIP/raw为 NOT_COMPILED；旧 locking CLI fixture 仅补隔离 anchor mock，场景/flags断言不变。7c8纯helper探测0新阻断/2旧债review，不是真CI通过。Java/实际JAR/jdeps/DB/CI NOT_RUN。

六可信文件 hash 见清单；commands/2ec baseline/locking proposal逐字等于f35。root唯一双ref接线，工具另提交固定本次 verifier SHA；未改workflow/生产/依赖/测试跳过。
