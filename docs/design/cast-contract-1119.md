# 角色（Cast）功能契约 v1 · 2026-11-19

Owner approved A + B + C + D on 2026-11-19, to be built together in this round. Review prototype: https://claude.ai/artifact/KKGg7r6xEZxSwdKMPtVDxf (version 2). That link opens only with the owner's login, so this file is the spec.

The owner's words (2026-11-19):
> "A, B, C, and D are fine, do them together, finish them all. Also, the story flow interface is not good. Don't use the 'So-and-so said' tag; it looks bad. You can label the speaker's name on the first line at the edge of the screen, and make the name smaller and a lighter gray color. Optimize the UI overall. The chapter text should still use the previous story flow, not that message-sending page; it doesn't feel like reading a book."
> "The chapter text will remain the same as before. No speech bubbles or messages."

## Hard UI rules
- Player chapter text stays the existing book-style story flow (`pages/play` chfull, `.chfull__para`, centred hand-written paragraphs). No chat bubbles, no message cards, no avatars in the text, and no left/right alignment for the player.
- A dialogue line is an ordinary `.chfull__para` paragraph. The speaker's name is drawn absolutely in the left page margin, level with the first line of the paragraph. It is about 10px (20rpx), weight 400, light grey (`rgba(255,255,255,.36)` on dark). The paragraph gets about 26px (52rpx) of horizontal padding so the name never overlaps the text. There is no "某某：" prefix and no "某某说".
- Existing `type:'text'` blocks with a `who` field currently render as `who + '：' + content`. They must use the same margin-name style.
- Tapping the name plays that line's voice, if it has one. Nothing plays automatically.
- `measureChapter` aligns items to `.chfull__para` by index, so every item must still be exactly ONE `.chfull__para`.
- Editor: the theme editor is light, and the existing UI is the baseline. Improve elements one at a time and never redesign a page. No helper captions. Disabled controls must explain themselves the same way #871 does.

## Data
### Theme cast config
`topic.cast_config`: a JSON column added by a new migration `sql/migration_20261119_topic_cast_config.sql` (nullable; `NULL` means off).
```json
{
  "enabled": true,
  "playerName": true,
  "playerIdentity": false,
  "identities": [{"key": "i_ab12", "title": "记者", "sub": "来写一篇乐队重聚的稿子"}],
  "cast": [{
    "id": "c_ab12cd",
    "name": "苏禾",
    "identity": "主唱，三年前先走的人",
    "avatarUrl": "",
    "color": "#E8604C",
    "voice": {"mode": "preset", "preset": "bright_female"}
  }]
}
```
- `name`: 1–8 characters, unique within the theme.
- `identity`: 0–30 characters.
- `avatarUrl`: an https URL or `media-asset:N`. Empty means a coloured initial.
- `color`: one of `#E8604C #3D8BFD #2FB57A #B98AF0 #F2B33D #8E8E93`.
- `voice.mode`: `preset`, `record` or `none`.
- `voice.preset`: `bright_female`, `warm_female`, `deep_male`, `youth` or `elder`.
- `cast`: at most 12 entries. `identities`: at most 4 entries, used only when `playerIdentity` is true.
- Editor and player topic-info responses return it as `castConfig` (camelCase). Saving goes through the existing theme save path with an owner/scope check, and the server validates every limit above.

### Story block: dialogue line
`{"key": "...", "type": "line", "speaker": "c_ab12cd" | "player", "content": "…", "audioUrl": "", "cond": <existing cond shape, optional>}`
- `content` is 1–300 characters and may use the existing story vars. `{玩家}` is the player's chosen name, falling back to `你`. `{身份}` is the chosen identity title.
- `audioUrl`: optional. It is the author's recording of this line (`media-asset:N` or https). If it is empty and the speaker's `voice.mode` is `preset`, the client asks the TTS endpoint.
- If the speaker id no longer exists in `cast`, render the line as plain text with no name. Never crash.
- `speaker: "player"` shows the player's name, or 「你」 if there is none.

### Player profile per run
- `POST /api/play/run/profile {runId, name, identityKey}`. Only the run owner may call it. `name` is 1–12 characters and is filtered through the existing content-safety filter. `identityKey` must exist in `identities`.
- The profile is stored in the run's state JSON (no new table if the run state already has a JSON column; otherwise use a migration) and returned with the run's session/state so the client can fill `{玩家}` and `{身份}`.

### Voice (TTS)
- `GET /api/topic/cast/voice?topicId=&chapterId=&blockKey=` returns `{url}`.
- It reuses the existing DashScope TTS service that the merchant AI NPC uses, mapping each preset to a voice. The result is cached in OSS keyed by a hash of (preset, text), so each line is synthesised once.
- Published themes: any logged-in player may call it. Drafts: the theme owner only.
- Rate-limited per user. Returns a clear error and no URL when TTS isn't configured. The client then hides the play affordance and must not show an error toast for that case.

### D2 · Trust and endings
- A `branch` block option may carry `trust: [{castId, delta}]`, with `delta` an integer from −2 to 2. The server applies it when the player picks that option on the existing branch-choice path and keeps `trust` (castId → int) in the run state. Clients never send trust values.
- An ending chapter may set `endingFor: castId`. When the run reaches the ending, the server chooses the ending chapter whose `endingFor` has the highest trust. Ties and the no-trust case use the existing default ending, so behaviour is unchanged when no `endingFor` is configured.
- The run result returns `trust` for display. The completion page shows a 「他们怎么看你」 section: avatar, name and a bar per cast member, using the cast colour only on the bar and avatar.

### D1 · Arrival scene
No new mechanism. A station's chapter text may contain `line` blocks, optionally with `audioUrl`. After 我已到达 the existing story flow shows them in book style. A line with a voice gets a small 「▶︎ 0:08」 control under it, centred, light grey; tapping the speaker's name also plays it.

## Player start: naming and identity (C)
Show this only when `castConfig.enabled && (playerName || playerIdentity)` and the run has no profile yet. It is a full-screen dark step before chapter 1, one question per screen:
1. 「你叫什么？」 with a large input, prefilled with the WeChat nickname or empty. 「出发」 works with an empty name, which falls back to 你.
2. If `playerIdentity`, 「今晚你以什么身份到场」 with the identities as selectable cards.

It must work solo, skip on resume, and never block an existing theme that has no cast.

## Editor (A, B, D2)
- **Theme home** (`pages/publish/fabu`): add a 「角色」 card with a switch, a row of stacked faces and 「N 位」. Tapping the card opens the cast list. Turning the switch off keeps the data and hides the 对白 button.
- **Cast list:** 玩家 rows with the 玩家起名 and 玩家身份 switches (with an identities editor when it's on), then character rows (face, name, identity, voice) and a 「添加角色」 dashed row.
- **Edit-character sheet** (iOS-style sheet as in #860/overlay spec): avatar with a camera badge, 名字, 一句话身份, voice chips with a 试听 action (calls TTS with a sample line), 「用自己的声音录」, and the six colour dots.
- **Story editor add bar:** add 「对白」 next to 「段落」, only when the cast switch is on. Tapping it opens the 「谁在说话」 sheet (the characters, 旁白 = a normal paragraph, 玩家). The new block then shows as a paragraph with the speaker name small and grey at its start. Tapping the name re-picks the speaker. Each line can have an optional per-line recording.
- **Branch block:** with the cast switch on, each option gets a 「信任」 row with a ±stepper per cast member, 0 by default and hidden when 0.
- **Ending chapter settings:** add 「归属角色」 to choose the character this ending belongs to, or none.

## Out of scope
No payments, no prod access, and no copying private story text into the public repo. Use fictional sample text in tests only.

## E · Better presentation (owner 2026-11-19: "take a look at how others do it… Optimize the game theme further… make one yourself")
References: Disco Elysium (one reading column, numbered choices inside the text), inkle 80 Days / Heaven's Vault (2–3 short choices), Pentiment (cast list), Telltale ("X will remember that"), Citizen Sleeper (a small progress clock on the character card).
- **E1 登场人物 (playbill):** a full-screen page shown once after naming and before chapter 1 when cast is enabled. It has a small route name, the title 「登场人物」, one row per character (name, dotted leader, identity), the player last after a hairline (chosen name + identity, or 「你」), and a 「开场」 button. It can be reopened from the play page ⋯ menu as 「人物表」.
- **E2 Chapter opener:** inside the existing chapter-start presentation, a small 「第N章」 with letter-spacing, a large title and a 28px hairline. The first paragraph of each chapter gets a drop cap: the first character floats left at about 3 lines tall. A running footer shows 「章名 · 第x站 / 共y站」 at 11px, faded, at the bottom.
- **E3 Inline choices:** the player-side branch choice renders inline at the end of the text, in the reading column, instead of a pop-up. Choices are numbered 1–3, separated by hairlines, at 16px. After a choice, the chosen text stays in the story as a `line` spoken by the player (margin name = player name) and the unchosen options disappear. If the option changed trust, a centred 12px light-grey line 「{name}会记住这件事」 follows, with no numbers shown. Each choice is still exactly one `.chfull__para` item; keep `measureChapter` alignment.
- **E4 Character card:** tapping a margin name opens a half sheet on the dark surface. It shows the avatar (56px), name, identity, a play-voice button, 「她对你/他对你」 with five ticks filled by trust (cast colour), and 「你知道的」 with that character's unlocked clues. Locked clues show only 「还有 N 条，走到下一站再看」.
  - Data: `cast[].clues: [{text 1–40, chapterId|null}]`, at most 6 per character.
  - A clue is unlocked when the run has reached that chapter, or always when `chapterId` is null.
  - The server must not send the text of locked clues to players; it sends `lockedCount` instead. This is a backend follow-up.
- **E5 Route detail:** when cast is enabled, the route detail page gets a 「人物」 section with a horizontal row of avatar (54px), name and the first part of the identity, placed before 故事.
- **E6 Ending:** the run-completion page gets a book-style 「他们怎么看你」 section. Each character with trust ≠ 0 gets a closing line, with an optional `cast[].endingLine` per trust band, and the name in the margin. Five trust ticks sit under each line. A footer reads 「这条线有 N 种结局。这一次，是X的结局。」.
  - Data: `cast[].endingLines: {low, mid, high}`, each 0–60 characters. Fallback: nothing.
