# Author save → read-back against the rig backend (base `integrate/questo-ui-20261110` @ 449fb80d)

Rig: cloud walk rig (`claude/cloud-walk-20261101-rig`, harness patches from `claude/cloud-walk-20261101-club`), MySQL 8 + Redis
fixtures, identity `e2e-openid-9002` (fixture merchant/creator). No production access.

## 1. Hangman + hint ladder, saved through the real editor page

Driven in `pages/publish/temp/index` (browser runtime, real page code): choose 逐字猜词, answer `ENDLESS`, prefill cells 1 and 7,
alias `NEVER ENDING`, enable 提示, 整局免费次数 1, layer 1 文字「看钟面」, layer 2 首字 20 积分, then 保存草稿.

- `POST /api/template/draft` → `200 {"data":"6005","writeReceipt":{"state":"COMPLETED",...}}`
- `POST /api/template/author-myinfo id=6005` → `advancedConfigJson` read back:

```json
{"hangman":{"answer":"ENDLESS","format":["E","_","_","_","_","_","S"],"aliases":["NEVER ENDING"],"enabled":true},
 "hintLadder":[{"type":"TEXT","level":1,"payload":"看钟面"},{"cost":{"points":20},"type":"LETTER","level":2}],
 "hintFreeUsesPerRun":1}
```

## 2. Image options + mirror clue + accusation (client serializer → same draft endpoint)

Built with the branch's own `node-game-catalog.applyToConfig('qaPick')` + `questo-author.beforeSave` + `advanced-game-config.serialize`
(the exact path the editor uses), posted to `/api/template/draft` → `200 {"data":6006}`; read back:

```json
{"options":[{"id":"A","alt":"戴眼镜的古董店老板","img":"/images/icon_cat.png","label":"古董店老板","correct":true,"fb":""},
            {"id":"B","label":"守夜人","correct":false,"fb":""}],
 "clueTransform":{"mode":"MIRROR"},
 "accusation":{"enabled":true,"suspects":[{"id":"A","name":"古董店老板","avatar":"/images/icon_cat.png","identity":"钟楼对面"},
   {"id":"B","name":"守夜人","avatar":"/images/group1.png"}],
   "winScreen":"钥匙在账本夹层里。","loseScreen":"真凶离开了这座城。","earlyAccuse":{"allowed":true,"recordAs":"速破"}}}
```

The backend's save validators (`HangmanGameplay.validate`, `validateHintLadder`, `QaOptionMediaSupport.validate`,
`ClueTransformSupport`, `AccusationTemplateSupport.validate`) accepted both shapes. Publishing, freezing and a live player session
were **not** run (no published theme with these nodes was built in the rig).
