# Round 5C: owner's correction, 2026-11-03

Commit and push after every finished item. Pull your branch first; another run may have just pushed. Merge, no force push. Draft only; never merge. Finish with a `Round 5C` section in RESULT.md, tests red on old code, UI-GATE-0:PASS, no CI failures, last line `STATUS: COMPLETE` or `STATUS: PARTIAL - reason`.

## l8-safety: moderation must be fully automatic
**Owner:** the system must detect sensitive words and images and remove content automatically, without manual intervention. This overrides every "hold for human review" in ROUND5B, including items 3 and 4. Rework anything already pushed that relies on a person.

- **Violating text:** refused at submit, as now.
- **Violating image at upload:** refused, as now.
- **`risky` from the async check, on every content type including node templates:** the content is removed from public view automatically and the author is told by the existing notification path, with no person involved.
- **`review` from the async check:** treat as `risky`.
- **Check unavailable, off, or no openid:** content stays visible only to its author, the system retries on a schedule with the existing retry or outbox mechanism, publishes automatically on pass, and removes automatically on risky. Retries are bounded; when exhausted it stays hidden and the author is told. Never public while unchecked. Say in RESULT what happens if WeChat is down for hours.
- **Existing `PRE_REVIEW` paths that hold for a person:** paid or commercial content (`STRUCTURED_REVIEW`, `MEDIA_PENDING`) is not automatic today. List each one with who approves it now. Text and image safety on them becomes automatic. Leave business approvals of price, location and sign-up alone and report them for the owner.
- **Reports:** do not build a report path. Skip item 7 of ROUND5B except for the list.
- **Bundled images:** drop item 8 of ROUND5B; the owner says images aren't the question. Restore any image change already pushed.

Also write in RESULT a one-page table: for each content type, how text is checked, how images are checked, what happens automatically on violation, and anything that still needs a person.

## l6-club: P-6 layer unreadable on dark
`shots/MERCHANT-r4-after.jpg`: in the dark club-host 招商 layer of `pages/topic/components/project-host` the summary numbers and all chapter and station names render dark on dark. This is a defect, not a design choice. Fix the colours through the existing dark theme tokens so the layer reads as the approved P-6 dark screens did. Change nothing else. Shoot it.

## l1-play: P-08 dots
`shots/P-08-r4-after.jpg`: the waiting dots in the middle of the screen are almost invisible. Make them clearly visible using the existing token. Change nothing else.
