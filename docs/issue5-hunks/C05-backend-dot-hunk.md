# C05-backend → D3/dot 最小 hunk

Issue：https://github.com/liboyang42-cpu/chengyin-remote-ops/issues/5 领取组 **C05-be**  
仓库：`github.com/liboyang42-cpu/hengyin`  
Base：`origin/master` `2ec68899099a1e250df0949a8383bf85b5d3ffe0`  
分支：`cursor/c05-offline-pack-e504`

未改 PlayExperience 原会话、PlayNode、20 个原暂停操作、TopicRouteRuntime*、公共 Controller/Mapper。**Do not edit those files from this PR.**

不是 100%。qmod final ops / vol12 resume writer / T41 scoring writer **WAITING_SOURCE**（present=false）。DB / HTTP / App / CI = **NOT_RUN**。

iOS exclusive already on chengyin-ios#67. This is the matching isolation producer.

## 白名单

- `.../service/offline/OfflinePackOperation.java`
- `.../service/offline/NavigationDestinationConsent.java`
- `.../service/offline/PresenceProofDistinctFromFog.java`
- `.../service/offline/SideQuestAcceptHold.java`
- matching isolation tests
- this file

## D3/dot 接线

离线包 operationId 接到既有 resume writer（源未到则保持 WAITING_SOURCE）。导航同意不得并入位置分享。迷雾不得写入到场证明。
