---
name: editor-template-model-20261110
description: "Owner rules for the theme editor: 玩法 = templates only (public / mine, pre-configured), one location per game set in the template pop-up, image groups move as one unit; 星尘 = iOS glass, 末世 = ruin/decay"
metadata:
  node_type: memory
  type: project
  originSessionId: 356c3f1b-c006-42a4-8c1c-61c5eeb6a529
  modified: 2026-11-10T12:25:12.210Z
---

Owner 2026-11-10 on the editor prototype (https://claude.ai/artifact/4ExsEZDZHaxu4BETTYR2FF):
- "Gameplay" in the editor means TEMPLATES only — 公共模板 / 我的模板 (pre-configured) + 自己建一个. Never list the individual games inside templates.
- Locations are bound to templates: one location per game, set in the template's placement pop-up (the earlier pop-up design was right). No standalone 地点 block.
- Image block: images selected into a group whose count depends on the template (1, 2, …); dragging moves the whole group as one unit; follow the existing design spec.
- Looks: 星尘 should be translucent iOS-style glass with visible reflections; 末世 should feel ruined/decayed. 纸本 and 夜城 fine.

**Why:** owner's product model — authors place ready-made templates, not raw game types.
**How to apply:** every editor/picker/prototype/code change follows this. Related: [[questo-ui-wave-20261109]], [[gameplay-template-editor-redesign-20261022]].
