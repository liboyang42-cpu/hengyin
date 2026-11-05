# Owner 真机耳机走查（16 步）

使用已确认的开发/测试体验包、测试账号和虚构免费游戏。开始前记录包版本、后端源码 SHA、测试库标识、手机型号/系统、微信版本和耳机型号。不要对生产主题或真实收费订单试错。每一步记通过/失败/未测、时间、录屏位置和状态读回；没有听到的不要记通过。

1. 从专业主题编辑器打开测试游戏，重看保存后的开局、各站、分支、彩蛋与结局。确认不是只存在于本地未保存草稿。
2. 戴耳机从正常玩家入口开始。确认开场音乐与视频能播；第一次播放没有必须反复点击才能继续的陷阱。
3. 开场视频点击跳过。确认不会黑屏卡住，背景音乐接上且只播一首；重新开一局再完整看完视频。
4. 步行到第一站。确认真实定位和距离合理，未到点不能误完成；已完成的站重开不用走回去。
5. 播旁白时听清人声。确认背景音乐压低，暂停/结束后恢复，反复点同一站不从头叠播。
6. 在两站之间边走边听。确认没有无故静音，没有主题曲与站点曲同时响。
7. 锁屏约一分钟再解锁，记录锁屏时音乐是否继续、回前台是否自动恢复、站点和进度是否保留。当前代码未声明后台 audio，不能预先当作可锁屏连续播放。
8. 切到其它应用约一分钟再回来。确认只恢复先前正在播放的音频，手动暂停的旁白不会自己响。
9. 用来电或系统音频打断一次再结束，确认播放/暂停状态正确，主题曲、站点曲和旁白不会同时抢播。
10. 从游戏入口拍照打卡，分别试相机拒绝、取消拍摄和正常上传。确认可重试，成功状态读回后才算完成；返回游戏音乐恢复。
11. 扫描测试站点的真实二维码，再试错误二维码和取消扫码。确认不会误完成，成功只记录一次，回来后仍有音乐。
12. 从新的一局选分支 A，完成并读回对应结果；再用独立测试局选分支 B，确认走向不同且都能收尾。
13. 分别跑找到彩蛋和没找到彩蛋的两局，确认结果不同，没找到也有合理收尾。
14. 在媒体开始加载时切弱网/断网，再恢复。确认开场、旁白和结尾均能退出或重试，不能一直黑屏、无按钮或误报完成。
15. 完整播放结尾音乐与视频，确认结束后不残留旧音轨；查看完成卡，退出重开后完成进度一致，奖励/记录没有重复。
16. 同一游戏分别使用星尘、纸本、末世、夜城，检查正文、按钮、分支、旁白控件和完成卡：不遮挡、可读、配色符合原型；截图留证。最后离开游戏确认音频按预期停止。

若编辑器配不出任一必需项，记录具体入口和缺失控件，先交回修复，不用接口或数据库代填。真机结果应和 rig、单测分别列出。

STATUS: PARTIAL - 脚本已提供，尚无本轮真机执行或听感证据。


---

## 第二份(同一轮 k10 的云端 rig 版,另一个工作者;上面一份保留不动)

### k10 phone script: what only a phone with headphones can prove

For the owner, on a real phone, with wired or Bluetooth headphones, outdoors.
Use a theme with an opening chapter that has 章节音频, a station whose 玩法模板 has 语音讲解 and 背景音乐, a QR station, and a 结局 chapter with 章节音频 (the test theme 「磁带雨夜」 in the rig has exactly this; build the same in production or use 990068/990069).
Write down, for each step, what you hear and what you see. "OK" or one line of what went wrong is enough.

1. Turn the phone's silent switch ON (iPhone) and set volume to about half. Open the theme, tap 开始游戏, then 开始游戏 again. You should hear the chapter narration through the headphones. Note whether the narration is silent with the silent switch on.
2. Let the narration finish. Note what you hear while you walk to the first station: silence, or music.
3. At the first station tap 开始任务. You should hear the station's voice (语音讲解) start by itself, with the station music underneath and clearly quieter than the voice. When the voice ends, the music should come back up a little. Note if the music is too loud or too quiet under the voice, and if the silent switch mutes the music but not the voice.
4. Take the in-story photo (拍一张). The music must keep playing and must not start again from the beginning.
5. On the second sheet (拍照上传) take the photo with the real camera, crop, tap 选取. When you come back, the music must carry on from where it was, and the station must count as done. If the sheet stays empty, write that down: that is the bug fixed in this round.
6. Lock the screen for 30 seconds with the phone in your pocket. Note what you hear: today the code pauses all game audio when the screen locks (no background audio mode). Unlock: the music and any unfinished narration should continue from where they stopped.
7. Switch to WeChat chats for 20 seconds, then back. Same as step 6.
8. Ask someone to call you during the station voice. During the call nothing from the game should play. After hanging up the voice should continue from the same point.
9. Walk to the QR station. Check that the station is detected by real GPS (the distance at the top goes down as you walk, and the station opens when you arrive). Note how many metres away it opened.
10. Scan the printed station QR with the real camera in the in-story scan. Then scan it again on the second sheet if one appears. Note whether you had to scan twice.
11. While you scan, note whether the station music or voice stops and comes back when the camera closes.
12. Finish the last station. The ending chapter should open as a story page with its ending music. Note if you only see a card with no music (the bug fixed in this round).
13. On the finish card, tap 保存图片 and check the picture is in the photo album. Tap 分享 and check the share sheet opens.
14. Close the game and open it again from the order. The finished state and the ending should show again without errors.
15. Turn 背景音乐 off in the game menu (… → the music switch). All music must stop at once and must not come back at the next station. Turn it on again and check it returns at the next station.
16. Play with Bluetooth headphones, then pull them out (or switch them off) mid-narration. Note whether the sound jumps to the phone speaker in public.
17. Overall: write one line on the balance between music and voice, one line on any moment of silence you did not expect, and one line on anything that looked out of style for the theme's look (星尘 / 纸本 / 末世 / 夜城).

What this script does not cover: the editor (done in the rig) and money.
