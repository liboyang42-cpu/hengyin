# EXTRA e72eff7e：56项针对性复测通过

公共完整候选：`e72eff7e9ec6309262e55c7cdca56afdcdb86dc0`，[PR36](https://github.com/liboyang42-cpu/hengyin/pull/36)。原 `474e54ae` 的305项实跑有1失败、2错误；原作者在单条修复线补齐两个测试类的历史回执查询夹具，生产源码未改，保留原有断言与负控。

专用测试VPS使用212守卫实际复测两个原失败类：**56项通过，0失败、0错误、0跳过**。两份XML为本次开始后的新报告，类集合和原两类的数量一致，守卫退出0/completed。精确命令、时间、源包、日志和XML哈希见 [summary.json](summary.json)。

这是针对性复测PASS，不代表新SHA重新执行全部305项；其余32类未重复执行，23个真实数据库门禁类不在本记录内。真实MySQL/Redis、运行中后端HTTP、设备与业务验收、合并和部署仍未完成。后续新源码必须单独冻结验证。

资源：1536MiB内存且同额memory-swap无额外swap、1CPU、256PIDs、network none且无发布端口；共享java-build.lock持有到结束；准入至少2560MiB可用内存，运行低于768MiB仅终止本任务。精确容器已由守卫清理，证据保留在 `/home/ubuntu/previews/cy-vps-extra-e72eff7e-20261015/`。没有变更其他服务或预览。
