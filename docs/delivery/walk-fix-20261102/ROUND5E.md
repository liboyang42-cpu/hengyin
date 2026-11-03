# Round 5E: finish before integration, 2026-11-03

Pull your branch first; another run may have pushed. Merge, no force push. Commit and push after every finished item. Draft only; never merge. Never touch production. Your run isn't done until CI on your PR has no failures: check it yourself with `gh`, wait, and fix. Finish with a `Round 5E` section in RESULT.md and a last line `STATUS: COMPLETE` or `STATUS: PARTIAL - reason`.

## l8-safety (#721)
1. **CI.** `Fast gates (CWD / API existence / silentError)` and `Migration safety gate` fail at 689c7a6e. Fix the cause; don't weaken a gate. No baseline or allow-list entry without a stated reason.
2. **Rig proof, real HTTP and database read-back** (`claude/cloud-walk-20261101-rig`, `up.sh` pinned to this branch, `LANG=C.UTF-8 LC_ALL=C.UTF-8` for Maven, WeChat stubbed since the rig can't reach it):
   - library word hit refuses a comment, a post and a theme, using a made-up token you import through the real admin endpoint;
   - allow-listed phrase passes; word split by symbols is caught;
   - comment with an external image URL refused;
   - check unavailable: content visible to its author only, not on public lists; auto recheck passes and publishes; a violation removes it and the author is notified; retries exhausted leaves it hidden;
   - known-bad image refused at upload, including a near copy;
   - rescan job in report-only mode writes hits and removes nothing; with removal on, it removes and is idempotent.
3. **Mapper SQL on real MySQL**, which can't be proven by unit tests, including the Druid wall path (`CAST(x AS BINARY)` rather than `BINARY a = BINARY b`).
4. **False-positive tool.** Include proof it runs, with made-up tokens only. No word list in the repo.

## l6-club (#716)
The other club-host dark drawers may share the P-6 contrast defect. Check every dark layer of `project-host`, `project-drawer` and `merchantinfo?scope=CLUB` in the rig, list each with a shot, and fix any dark-on-dark text through the same theme tokens. Change nothing else.
