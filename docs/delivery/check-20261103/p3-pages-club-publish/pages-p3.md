# p3 page list: club, publishing, theme-editing and template pages

Lane p1 did not run in this pass, so p3 built its own list from `chengyinhub-xcx/app.json` at commit
`8b78f5ca3c06f486631e827827e973cbb47e3094`. That app.json registers **157** pages, main package and all subpackages.

## Rule used

A page belongs to **p3** when one of these is true:

1. its route is under `pages/club/`
2. its route is under `pages/publish/`
3. its route is `pages/template/index` or under `pages/templatedetail/`
4. it is an author-side theme page: `pages/topic/pricing/**`, where the author confirms the final price, or
   `subpackageMember/mytemplate/mytemplate`, the author's own 节点玩法 list.

Two pages were left out on purpose:
- `pages/topic/merchantapply/index`: a merchant applying to a station is merchant-side work, so it goes to p2.
- `pages/crop/index`: a shared image-crop tool, so it goes to p1 under "if unsure, p1".

`pages/topic/index/index` is the player's theme detail page, so it goes to p1.

## The 32 p3 pages

| # | Page | Area |
|---|---|---|
| 1 | pages/club/apply/index | club |
| 2 | pages/club/badges/index | club |
| 3 | pages/club/create/index | club |
| 4 | pages/club/customer-detail/index | club |
| 5 | pages/club/customers/index | club |
| 6 | pages/club/detail/index | club |
| 7 | pages/club/edit/index | club |
| 8 | pages/club/enroll/index | club |
| 9 | pages/club/checkin-detail/index | club |
| 10 | pages/club/event-ops/index | club |
| 11 | pages/club/governance/index | club |
| 12 | pages/club/group-code/index | club |
| 13 | pages/club/join-requests/index | club |
| 14 | pages/club/notify/index | club |
| 15 | pages/club/roles/index | club |
| 16 | pages/club/settlement/index | club |
| 17 | pages/club/topic-detail/index | club |
| 18 | pages/club/topic-story/index | club |
| 19 | pages/club/workbench/index | club |
| 20 | pages/publish/fabu/index | publish / theme editor |
| 21 | pages/publish/simple/index | publish |
| 22 | pages/publish/temp/index | publish / 玩法模板 editor |
| 23 | pages/publish/activity/index | publish |
| 24 | pages/publish/topicadd/topicadd | publish / theme editor |
| 25 | pages/publish/template-intro/index | publish |
| 26 | pages/template/index | template (tab) |
| 27 | pages/templatedetail/templatedetail | template |
| 28 | pages/templatedetail/topic-preview/index | template |
| 29 | pages/templatedetail/topic-detail/index | template |
| 30 | pages/topic/pricing/index | author pricing |
| 31 | pages/topic/pricing/partner/index | author pricing |
| 32 | subpackageMember/mytemplate/mytemplate | author's templates |

The other **125** pages belong to p1 and p2.

Package placement at this commit: the main package holds only 5 pages, and `pages/template/index` is the only p3 page
among them. The rest sit in subpackages: `subpackageClub` (19), `subpackagePublish` (6), `subpackageTopic` (2),
`subpackageTemplateDetail` (3) and `subpackageMember` (1). The browser rig bundles every subpackage up front, so it
cannot show a subpackage downloading on first open. That check needs a phone.
