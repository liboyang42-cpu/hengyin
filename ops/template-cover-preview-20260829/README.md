# 临时主题封面上传包（2026-08-29）

这 7 张图是用户确认的 Pinterest 方向预览，只用于上线前临时替换；它们不是已经完成授权采购的正式素材，后续必须用无水印、已授权原图再次覆盖同名文件。

| 文件 | 主题 | 来源状态 |
|---|---|---|
| `t01-hidden-menu.webp` | 隐藏菜单 | Pinterest 预览，原始落点 PNGTree |
| `t02-in-store-treasure-hunt.webp` | 店内寻宝 | Pinterest 用户上传，原作者未确认 |
| `t05-three-step-puzzle.webp` | 三步解谜 | Pinterest 预览，原始落点 Wicked Uncle 商品页 |
| `t08-stamp-tour.webp` | 集章巡店 | Pinterest 预览，原始落点 Dreamstime |
| `t10-three-blind-boxes.webp` | 三个盲盒 | Pinterest 预览，原始落点 Creative Market |
| `t12-menu-tongue-twister.webp` | 菜单顺口溜 | Pinterest 预览，原始落点 Dreamstime |
| `t24-night-owl-city.webp` | 夜猫子城市生活 | Pinterest 预览，原始落点 Adobe Stock |

人工上传任务只接受已进入 `master` 且主干 CI 成功的完整 commit SHA。它会先把生产现有文件备份到 `/www/wwwroot/template-cover-backups/<run-id>/`，再覆盖 `/www/wwwroot/uploadPath/template-covers/` 下的同名文件，并断言服务器 SHA-256 与公网 URL 回读均和上传包逐字节一致；任一步失败会自动恢复这 7 张旧封面并再次回读。服务器独立看门狗在 12 分钟内收不到成功确认时也会自动恢复，覆盖任务被取消、超时或 runner 掉线时仍有兜底。
