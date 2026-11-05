# Round 12, lanes k10-immersive and k11-platform-clawback

Rules and owner decisions: `ROUND12.md`. Private local branch, `pull --rebase` and `git show --stat` before each push, no force-push, never push to master, merge a PR or touch production. If you add or remove a `@Test` in a gated suite, update `EXPECTED` in `ci/funds_it_gate.py` and its fixture in the same commit. Try to start Docker for the rig (`integ/map-shots/m2-rig-notes.md`); if it can't start, don't work around the refusal: do code and config checks, and mark each item plainly as not walked. Never report unit tests as walks.

## Lane k10-immersive

**The owner's words:** "Another key point is game optimization. I want an immersive experience. There will be music/videos at the beginning, followed by background music and narration. At specific locations, players can take photos to check in or scan QR codes to complete tasks. The game has different tasks or solutions, and some easter eggs. Finally, the game ends with music and videos. What I need is a complete, immersive game that can be played in a city with headphones. I remember I've configured everything, but I haven't actually played it all the way through yet. When you test it, you need to configure it from a user's perspective, from the professional theme configuration to playing it through, to confirm that every function is usable. The game's UI should match the colors and style we designed previously." Map styles are on hold.

k8-play owns j3's listed game bugs at the same time: pull often and don't redo them. Report anything of theirs you find still broken.

1. **Author it as a user, on screen.** Using only the professional theme editor (`pages/publish/fabu` and its screens), never database or API shortcuts, build one full paid-or-free solo game in any city with: opening music and video; background music; narration per station; photo check-in stations; QR scan stations; at least one branch with different solutions; easter eggs; ending music and video. Fictional content and small test media you generate yourself. Every control the editor offers for these. If the editor can't configure one of them, that's a finding. Note every confusing step, with the owner's rule in mind: no helper captions, fix the layout.
2. **Play it start to finish as a player, headphones in a city:** intro, each station, each branch, easter eggs found and missed, ending, finish card, reopen.
   Check the audio state machine at every transition by reading real audio context calls: what's playing, what fades, what's ducked under narration. Nothing silent by accident between stations, no two tracks at once, no restart on a tap, music resumes after a video, narration, a photo, a scan, a sheet, going to background and coming back, an interrupted call (simulate `onAudioInterruptionBegin/End`). Check `requiredBackgroundModes` and how audio behaves when the screen locks: code path, and what a phone must prove. Video: no black flash, tap to skip, fails safe when it can't load, slow network, no autoplay trap. Media size and preload: a city player on mobile data must not wait. Weak network at each media step.
3. **All four looks:** the same game in 星尘, 纸本, 末世 and 夜城. UI matches the approved designs. Compare with `docs/design/` prototypes and `pages/play/utils/theme-look.js`. Find anything off-palette, unreadable or that breaks the immersion: stray light cards, system toasts, developer words, jarring jumps.
4. **Fix** every bug, gap and immersion breaker you find, in the existing look; no redesigns. If a missing feature needs a design choice, list it for me with a recommendation.
5. **The owner's configured games:** you can't read production. Write a read-only SQL and a small checker that takes a theme's stored config and lists everything that wouldn't play: missing media, unreachable branch, station with no way on, missing ending. Put them at `integ/k10-theme-check.md` (not a `.sql` file outside `chengyinhub-admin/sql`, which fails the inventory gate). I'll run it on production for 990068, 990069 and other public themes.
6. **Write a phone script for the owner:** `integ/k10-phone-script.md`, under 20 steps in plain words, for what only a phone with headphones proves: locked screen, background audio, music and narration balance, real GPS, camera, QR.

Write `integ/ROUND12-K10-RESULT.md` with screenshots you've viewed under `integ/fix-shots/`. Plain words. Per item: status, files, proof, unverified. Say plainly what you couldn't hear. End with `STATUS: COMPLETE` or `STATUS: PARTIAL - <reason>`.

## Lane k11-platform-clawback

**Owner's money decisions, 2026-11-05, in the terminal:**
- **On a refund after settlement the platform pays back its share too,** so all parts return in equal proportion. Today only a ledger line is written and the platform's balance isn't reduced (`integ/ROUND12-K5-RESULT.md`, `-1.17`).
- **Percentage terms on club themes stay as they are:** the API may accept them, and a session with one refuses settlement with an alarm. Don't add an invite block.

Make every refund-after-settlement path debit the platform's balance for its share through the existing clawback, with the same debt rule when the balance is short. Parts equal the refund to the fen. No double debit on retry. Check activity, club session with and without a merchant fee, theme and co-op paths. Screens or admin that show platform income agree. Rehearse on real MySQL: the ¥39 refund from k5's table gives platform −1.17, 主理人 −29.83, merchant −8.00 in balances. Funds MySQL IT green.

Write `integ/ROUND12-K11-RESULT.md`. Plain words, per item: status, files, proof, unverified. End with `STATUS: COMPLETE` or `STATUS: PARTIAL - <reason>`.
