# 离线界面检查

2026-10-13，在微信开发者工具独立临时项目截图。基线为 `69a262d`；隐私弹层候选为创作提交整合后的 `c5b6b5c`。

临时副本将 API fallback 指向 loopback，禁用 wx.request/uploadFile/downloadFile/connectSocket；没有测试后端、真实身份业务或写入结果。截图仅验证设置入口、半屏排版和敏感地点设置的可见说明，不能证明位置采集停止、服务端隐私保护或读屏通过。

- `w2-settings-baseline-correct.jpg`：基线设置页。
- `w2-privacy-before.jpg`：原隐私弹层。
- `w2-privacy-after-rendered.jpg`：沿正常设置入口打开后的新弹层，说明、按钮与底部内容完整可见。

首次 `simulator_open_page` 后黑屏且自动化超时；一次刷新后正常，再使用运行时页面导航完成点击。没有关闭或清除其他项目窗口/账号/缓存。
