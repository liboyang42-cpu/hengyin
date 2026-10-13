# CyMediaPreview：多图缩略与预览的 GitHub 一手调研（2026-07-22）

## 结论

`CyMediaPreview` 不解析业务页的媒体字段、不承担上传或视频播放。它只接收页面已经解析完成的图片 URL 数组，统一首图缩略、Element UI 灯箱预览、加载失败状态和多图数量提示。

活动与主题列表都是分号分隔的 `imgArr`，但字段的来源和格式仍由页面的 `getImageList` 负责；组件不把这个偶然格式扩张成全站媒体协议。

## 上游证据与边界

| 一手实现 | 可借鉴的事实 | 本仓落实 |
| --- | --- | --- |
| [Element UI Image 源码](https://github.com/ElemeFE/element/blob/dev/packages/image/src/main.vue) | `src`、`fit`、`preview-src-list` 是图片呈现/灯箱边界；`preview-src-list` 有值时由组件提供查看器。 | 在薄封装中继续使用既有 `el-image` 灯箱和错误 slot，不复制上游实现或另引图库。 |
| [Element UI MIT 许可证](https://github.com/ElemeFE/element/blob/dev/LICENSE) | 本仓已有 Vue 2 / Element UI 图片能力。 | 不新增媒体依赖，也不引入播放器或 CDN 逻辑。 |

## 验收边界

1. 页面必须传入已经解析的 `sources` 数组；空项在组件显示前剔除，首图作为缩略图。
2. 两张及以上图片显示 `+N`，点击仍进入已有 Element UI 灯箱查看全部图片。
3. 图片 URL 分隔、上传、视频、下载、权限与业务请求都不属于组件；没有第二个运行中消费者前不实现视频接口。
