# 后置控制固定引用

工具 parent：a03fff54667d239a9521fad2d370a3412902caab。compiled_controls.py 唯一执行变化：固定 verifier 由 5c 改为 beffa2023a68ffba2da5fce35f00e697252e72f4；原负控新增拒绝旧 5c，浮动/dirty 拒绝保留。

review.patch 仅建议 root 将 c8bb 的两个 workflow 固定 ref 同时改为该 SHA，本片未编辑 workflow。六可信文件 hash 与独立 verifier 提交同源；tool 分支旧 policy 快照不供集成，只取本提交 delta。

Python 工具 8 控制实际运行，0skip；本片未运行 Java/JAR/jdeps/DB/CI。无产物仍 UNKNOWN，真实编译边控制仍由 root 统一调度。
