# 漫游护照实景照片（V3-36）

外网图库（Unsplash / Pexels / Openverse / Wikimedia）在施工环境的网络策略下不可达，以下三张取自本仓已入库、已登记许可的 CC0 照片，只做裁切与 webp 压缩（quality 72）。

| 文件 | 用途 | 来源（仓内原图） | 许可 |
|---|---|---|---|
| history.webp | 漫游记录方卡 | `pages/templatedetail/art/covers/game/city-camera.jpg`（街市） | Openverse CC0（见 `pages/templatedetail/shelf/art.js` 头注；仓内未记单张原链接） |
| stamp.webp | 集邮册方卡 | `pages/templatedetail/art/covers/topic/treasure-hunter.jpg` | Flickr「Shanghai Bund」CC0（同上头注） |
| event.webp | 官方活动大卡（无活动封面时） | `pages/templatedetail/art/covers/game/team-race.jpg`（上海夜景） | Openverse CC0（同上；仓内未记单张原链接） |

## 2026-11-10 r2-visual：档案三卡改 3D 图标

业主「这里面的图片要干净 或者换成动画的 官方活动可以不改 上面的漫游记录 集邮册 探索目标 感觉需要统一一点的样式？」。
漫游记录 / 集邮册 / 探索目标三张方卡没有自己的图时，不再用实景照片，改成同一底色上居中的 3D 图标，轻微上下浮动（减少动态效果时静止）。
官方活动大卡仍用 `event.webp`。`history.webp`、`stamp.webp` 不再被引用，已删除。

| 文件 | 用途 | 来源 | 许可 |
|---|---|---|---|
| `../icons/history.png` | 漫游记录 | Microsoft Fluent Emoji「World map」3D | MIT，© Microsoft Corporation |
| `../icons/stamp.png` | 集邮册 | Fluent Emoji「Cityscape」3D 放进齿孔邮票框（同 `subpackageMember/images/album/stamp-3d.png`） | MIT，© Microsoft Corporation |
| `../icons/goals.png` | 探索目标 | Fluent Emoji「Bullseye」3D | MIT，© Microsoft Corporation |
