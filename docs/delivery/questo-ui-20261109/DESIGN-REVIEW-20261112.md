# Lead design review of round-5 DevTools screenshots (2026-11-12)

Owner: "many UI pages are still not well-designed… e.g. the QR code on the Play page is too large; it should be in the More section". Rules: existing UI is baseline (polish, don't redesign), no helper captions, never show empty headings or placeholder words to users, player dark / merchant light, in-game screens follow look colours, Apple-grade spacing.

| id | page (shot in scratchpad acc5/shots) | problem | fix |
|---|---|---|---|
| D1 | play page 入场码 (round-1 player-45-play-entry-ticket) | entry QR shown as a huge card/sheet on the play surface | remove from the main play surface; reachable only from the ⋯ tools menu row 「入场码」 (already exists), opening a normal half sheet with a moderately sized QR (≈ 200px), brightness boost kept |
| D2 | 通关卡 finale (player-113-*, 140, 146-149, 150) | pixel logo overlaps the stats card, card narrow, ghost title text behind | follow approved proto/game finale: logo above the card with clear space, card full content width, nothing overlapping; title text not duplicated behind |
| D3 | topic detail hero, topic/template cards (player-21/24/29/30/70-73, 04, author-06) | no-cover fallback = giant black/white dashed route + pin filling the hero; cards print 「封面暂不可用」 | fallback = the theme's look gradient (or neutral dark gradient) with the small B-v2 route illustration, never the words 「封面暂不可用」 |
| D4 | play page after finishing (player-147/148/149 closeFinish) | white 「输入地址」 search bar on the dark play page | dark-styled bar matching play chrome; hide it after the run is finished |
| D5 | home (player-01-*) | 「编辑精选 / 暂时没有精选主题」 empty section shown | hide any home section with no content (heading too) |
| D6 | 通行证 page (player-10-passes) | rows 「兑换码 暂不可兑换」「付费模板 暂不可购买」 for unavailable features | hide unavailable rows |
| D7 | merchant 口碑 (merchant-21-reviews) | one line of text at the top, rest blank | standard empty state (same component/illustration as other merchant empty pages) |
| D8 | free-explore station detail (player-174-fx-station-3) | big empty map placeholder; 「到店三步」 heading with nothing | smaller placeholder (or none when no image); hide heading when no steps (P-5 spacing handled by x-fix7) |
| D9 | club leader play intro (club-11) | topic title in 4 huge wrapped lines | clamp to 2 lines with ellipsis, size like other intro titles |
| D10 | merchant workbench (merchant-05/27/29) | large empty gap between ¥0.00 and the action row; greeting truncated | tighten vertical rhythm to match store-info page spacing; greeting wraps to 2 lines max |
| D11 | author 票务设置 (author-03-*) | helper captions under every row (选择开始日期…) + 「选择 >」 pills | remove helper captions; row shows value or 「未设置」 on the right like other forms |
| D12 | club director / 导演台 (club-20/26) | tall empty dark area with 「封面暂不可用」 placeholder above the title | collapse cover area when there is no cover |
| D13 | checkout 结算 (player-182/75) | 「暂无海报」 grey box | use the same theme fallback art as D3 |
| D14 | finish sheet (player-146/147-3-closeFinale) | 4 stacked buttons 保存图片 / 分享 / 查看结算 / 继续 | one row 保存图片 · 分享, one primary 查看结算; 继续 as a text button |
| D15 | wallet 票夹 (player-09) | ticket card mostly empty placeholder illustration | use theme fallback art (D3) and tighten card height |
| D16 | free-explore pack (player-170/171) | pack alone with no title, opening animation cards overlap mid-transition | title above pack (x-fix7 P-6), and stagger so cards don't overlap |
