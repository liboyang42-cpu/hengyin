# P2 Non-Homepage Experience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve P2 user experience without changing homepage, publish flows, publish rules, or business rules.

**Architecture:** Make small page-level improvements in existing Mini Program pages. Keep existing API contracts and navigation unchanged. Only add copy, empty/error states, style consistency, and first-time editable topic draft defaults.

**Tech Stack:** WeChat Mini Program WXML/WXSS/JS, existing `app.sendRequest`, existing route docs.

## Global Constraints

- Do not modify `chengyinhub-xcx/pages/index/*`.
- Do not modify publish topic/activity flow order, submission validation rules, or business rules.
- Allowed publish change: first-time empty topic publish page may prefill editable draft content only.
- Keep changes scoped and verifiable with `node --check` and JSON validation.
- Sync progress to `/Users/arlan/Downloads/chengyin/docs/城瘾小程序整改路线图.md` and `/Users/arlan/Documents/城瘾/小程序/城瘾小程序整改路线图.md`.

---

### Task 1: Template Market Copy And States

**Files:**
- Modify: `/Users/arlan/Downloads/chengyin/chengyinhub-xcx/pages/template/index.js`
- Modify: `/Users/arlan/Downloads/chengyin/chengyinhub-xcx/pages/template/index.wxml`
- Modify: `/Users/arlan/Downloads/chengyin/chengyinhub-xcx/pages/template/index.wxss`

**Interfaces:**
- Consumes: existing template list/category API data.
- Produces: page-level loading/error/empty copy using city exploration task language.

- [ ] Inspect current template page structure.
- [ ] Add loading/error state fields if missing.
- [ ] Replace template-market empty copy with city task/route/play copy.
- [ ] Run `node --check /Users/arlan/Downloads/chengyin/chengyinhub-xcx/pages/template/index.js`.

### Task 2: Role/Club/Merchant Concept Clarity

**Files:**
- Modify: `/Users/arlan/Downloads/chengyin/chengyinhub-xcx/pages/role/center/index.wxml`
- Modify: `/Users/arlan/Downloads/chengyin/chengyinhub-xcx/pages/role/center/index.js`
- Modify: `/Users/arlan/Downloads/chengyin/chengyinhub-xcx/pages/talent/list/index.wxml`
- Modify: `/Users/arlan/Downloads/chengyin/chengyinhub-xcx/pages/talent/list/index.wxss`

**Interfaces:**
- Consumes: existing role, club, merchant data.
- Produces: clearer role labels and empty-state guidance without changing permissions.

- [ ] Inspect current role and club/talent pages.
- [ ] Improve copy for player/club/merchant roles.
- [ ] Add empty-state guidance where club list/member list is empty.
- [ ] Run `node --check` for touched JS.

### Task 3: First-Time Topic Draft Prefill

**Files:**
- Modify: `/Users/arlan/Downloads/chengyin/chengyinhub-xcx/pages/publish/fabu/index.js`

**Interfaces:**
- Consumes: existing `initData()` and `formData`.
- Produces: editable first-time draft fields only when the form is empty and no template name is supplied.

- [ ] Inspect current `initData()` draft logic.
- [ ] Ensure prefill happens only when no existing user input is present.
- [ ] Prefill non-rule fields: name, subtitle, description, a default empty ticket label, chapter/node copy if already created by existing logic.
- [ ] Do not alter validation, submission payload, tab order, ticket requirements, or route rules.
- [ ] Run `node --check /Users/arlan/Downloads/chengyin/chengyinhub-xcx/pages/publish/fabu/index.js`.

### Task 4: Old Page Token And State Consistency

**Files:**
- Modify selected non-homepage WXSS/WXML files after inspection.

**Interfaces:**
- Consumes: existing CSS variables like `--cy-font-*`, existing states.
- Produces: more consistent empty/error/button text presentation.

- [ ] Identify two or three high-impact old pages outside homepage and publish flow.
- [ ] Apply small state/style consistency improvements only.
- [ ] Run relevant `node --check` where JS changes occur.

### Task 5: Route Docs Sync And Verification

**Files:**
- Modify: `/Users/arlan/Downloads/chengyin/docs/城瘾小程序整改路线图.md`
- Modify: `/Users/arlan/Documents/城瘾/小程序/城瘾小程序整改路线图.md`

**Interfaces:**
- Produces: current P2 progress and verification commands.

- [ ] Run all touched JS checks.
- [ ] Run JSON validation if app/project config changes.
- [ ] Confirm no homepage files changed.
- [ ] Sync route docs with file list and completion state.
