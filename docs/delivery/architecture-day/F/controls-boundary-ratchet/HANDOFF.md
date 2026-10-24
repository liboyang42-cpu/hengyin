# 后置工具固定新边界 verifier

Parent aaa3f113d4ddfa4da2b4734552d872119e268054。仅固定最终 e5b5818f2230eb9e81d2a825960c0470ac1640ec，旧 f35/中间 f864/浮动/dirty 均拒绝。原8工具控制实际通过、0skip；Java/JAR/jdeps/DB/CI NOT_RUN。

六文件 hash 同源最终 verifier。verifier 按 f35→e5b 组合 delta（含 f864）集成；仅取本工具提交 delta，勿整树覆盖旧 tool 分支 snapshot。双 workflow ref review.patch 以 root 已确认当前 f35 为前置，参考公开173f真实上下文，只供 root 串行接线；本片未改 workflow。
