# Round 11, lane g4-play-walk

g1 fixed the play blockers by unit tests only, with no rig walk. See `integ/ROUND11-G1-RESULT.md`. Rules: `ROUND10.md` "Parallel" section and `ROUND11.md`. Work on `integ/launch-test-20261103`, `git pull --rebase` before every push, no force-push, never push to master, merge a PR or touch production. If you add or remove a `@Test` in a gated suite, update `EXPECTED` in `ci/funds_it_gate.py` and its fixture in the same commit.

1. **Walk every g1 fix in the rig with screenshots:** closed 预测 「不选，继续」 with no reward, with and without a shop; D20 三档难度 and 相册 played through from the editor's own config; photo check down, retake then go on; 「等队长放行」 and 返回地图 under the director runtime; a finished theme reopening on its ending; distance updating while walking; 「已结束」 with no giant 0; 确认到点's done state and the dark top cards. Fix what the walk shows.
2. **Finish j4 F8:** the fixed 继续 button covering the chapter heading, and the card title scrolling under the status bar, without changing the share card's 3:4 image.
3. **Remaining j3 findings:** F13, F14 (captions, same keep list), F15, F16, F17, F18.
4. **j3 F19:** the 手写 font loads from fonts.gstatic.com, which can't be relied on in China. Point it at our own static path through config, with the system font as a clean fallback when it's empty, and add the upload to the deploy checklist with its CORS need. Don't commit the font if it would touch the main package; check its licence and report.
5. **Editor:** stop offering 结果反馈 on a 预测 station when the theme has no shop, since that station could never close.
6. **Game types j3 couldn't play:** 找东西, 相册, 拍物成卡, 罗盘, 弹球, 安静挑战, 喊一嗓子, 计步, 九宫格. Play what the rig can, stubbing sensors, and list what only a phone proves.
7. **j4's unwalked list:** following a team from the rail, the author's 同场路线 off switch, swipe cards and fly-to, drag clouds, off-screen pins, visible rain, snow and fog with a stubbed weather reply. Fix what you find.
8. **Don't do** j3 F6 (presence proof): it's waiting for the owner.

Write `integ/ROUND11-G4-RESULT.md`, with screenshots you've viewed under `integ/fix-shots/`. Plain words, proof per item, CI on a commit containing your work, ending with `STATUS: COMPLETE` or `STATUS: PARTIAL - <reason>`.
