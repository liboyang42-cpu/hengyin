Immersive K10 result — PARTIAL; shared database incident has stopped the walk

Cloud K10 6de347d40 and K8 2a316fe1f were read and rebased. Their fixes are retained. I waited the requested 25 minutes before the cloud result arrived. No production connection, deployment, master push, PR merge, schema reset or game-authoring SQL/API shortcut was used.

Source delivered

- c09b51b87: k10-theme-check.py, k10-theme-check.md and k10-phone-script.md. The cloud lane's additions to the two documents are retained. The checker is static inspection, not a gameplay pass.
- 37131d5ca: an empty backpack may have capacity zero, matching the server. Actual items still require capacity.
- a2285b3dd: resume the original playing tracks on return. Cloud audio work is retained.
- 6f63e08d3: E20 small 添加序章/编辑序章 entry beside 添加章节, reusing openTopicOpening. No new captions.
- ac2ac8571: E16/E10 ordinary qaShot now proposes an upload-reference requirement. Advanced runtime verifies the original uploaded image and uploader before applying state/score. The existing generic task-fact transaction records completion. The compiler and capability fixture allow this real path. Guided objective-answer verification was not loosened.
- 87cb26eb8: E21 author-saved-detail is an owner/scope-checked read-only readback, available after the edit window closes. Existing edit/write gates remain. Publish readback still checks topic, original version, status and saved content. E17 error and explanation now scroll beneath the chapter cards instead of covering their buttons.
- 1a777186d: added the missing assertNotEquals static import after the actual testCompile failure. No Java tests had run on that failed attempt.
- 1f7c78af4: one station proof. Generic games return to existing task settlement, not the old photo/scan sheet. Linear photo/scan stations may use arrive completion only when their current server session has completed that game; photo ownership was verified when the server stored the uploader fact; the expiry correction is described below. Without proof the original rejection remains. Already-published themes use their frozen current-run config; no theme or run snapshot is rewritten.
- d58f375ee: background audio source is pushed: app.json declares audio, active runs do not pause tracks on onHide, leaving/unloading/ending does pause, interruption handling is retained, and play contexts ignore the silent switch. This is not a phone lock-screen PASS.

E18: an explicit chapter ALL marker is deliberately retained because it clears previously compiled ANY_N/exclusive rules. It must not be deleted blindly. The qaShot proof path removes the save rejection that made this residue fatal. A regression covers a photo theme with an egg and then the restored ALL/empty-egg configuration. The actual shared test reached publication and exposed the separate generic egg guard. a8b142029 wires personal generic chapterTick into the existing chapter clock and authenticated-step synchronization; the compiler now allows that PLAYER path. TEAM eggs still fail closed pending m1 integration. The UI save/reopen test and focused rerun are not yet complete.

Raw generic error text was already translated by the shared k7 work now on this branch. That implementation was retained. No second translation layer was added.

Latest source additions

- a8b142029: correct the exact scoped-photo-key assertion and inject the real ObjectMapper into the readback fixture. Personal generic chapterTick now uses the existing chapter clock and authenticated step synchronization. PLAYER TIME/STEPS eggs can award once; TEAM eggs remain rejected pending m1 integration. These corrections still need a focused Java rerun. The prepared five-case test-only budget212 request was refused because another build held the lock; no own test process or lock remains. No new VPS JAR package was requested.
- 15e20dee7: a photo fact verified and stored by the server stays valid after its temporary URL expires. Old facts without the stored uploader marker still verify the original reference. Scan proof still requires the actual scanned state.
- cbc857d9da1d1d94b8e060f5f4219f9ed61e25cb: reconcile E17 with the pages worker: preserve 去修改/重试 and editRejectedContent, but put the error wrapper and explanation inside the chapter scroll. The banner no longer forms a fixed overlay on the chapter buttons.

Validation so far

- Latest E17/source checkpoint: 111 focused JS tests passed (k10-immersive-audio, audio-session, fabu-server-draft, pro-editor-fabu-flow). Results outside repo: /private/tmp/codex-k10-recovery-20261105/source-checks/k10-final-focused.log. Earlier 109 focused JS tests passed for matrix, server draft and editor flow after rebase. 25 audio/config/resume tests passed. The photo/scan continuation behavior test exercises both generic and linear UI paths.
- Latest action ledger regenerated and checked on a0540a3d4 plus E17: 5743 controls, zero dynamic evidence results. UI integrity is NOT green: club/apply dynamic setData anchors 244/255 have moved to 258/269 (four violations). No new allowlist entries or weakened gate were added. Root owns that cross-lane correction. Earlier checkpoints passed, which does not override this latest failure. Logs are preserved in recovery/source-checks/. The final normal rebase also incorporated 5af3871c4; no broad suite was rerun.
- Main package preflight reports 2.98 MB under the 3 MB ratchet. Release preflight is BLOCKED by the existing action ledger dynamic-evidence requirement; this is not a release approval.
- One actual budget212 run used source 1f7c78af4, an isolated source/cache and these related classes only: AdvancedActionJudgementTest, AdvancedGameRuntimeServiceImplTest, GameplaySupportMatrixTest, GameplaySupportMatrixRuntimeTest, TopicRoutePublicationOutcomeScopeTest, ApiTopicSaveDraftContractTest, TopicAuthorEditDateWireTest, ApiPlayGpsGeofenceValidationTest.
- That run stopped at testCompile: ThemeRuntimeMySqlIT.java:101,319 has ambiguous Date (java.util.Date / java.sql.Date). No selected Java tests executed and no new JAR was produced. Root was notified because this is outside my owned test files. Budget lock is released. Earlier requests were refused with existing_java_build, not memory.
- Play owner subsequently ran 285 related tests on source35b3dc6df. Two failed and one errored: my new action assertion expected the client key rather than the scoped hash, my readback fixture lacked the injected ObjectMapper, and the generic compiler still rejected eggs. a8b142029 corrects the first two fixtures and connects the actual personal generic egg tick; a new runtime test covers TIME reward, initial step baseline, step delta reward and replay without duplicate reward. That correction has not yet been executed. Actual shared log: /home/ubuntu/previews/codex-play-map-followup-20261105/source/.play-map-rig/out/combined-tests.log.
- The existing pre-commit hook unexpectedly started a non-budget remote compile. I stopped exactly its own cy-uifeat-hook-58771. Subsequent backend commits use a per-command hook copy retaining JS/JSON syntax, protected-file and DS checks while delegating compilation to budget212/CI. The shared hook was not changed.

Shared rig incident and preservation

At 2026-11-05 10:00:17Z the shared MySQL cy-chain-play-map-20261105-1-mysql reportedly exited 137/OOMKilled. Restart reported Unknown database cy_e2e_51. Play stopped the app. This lane has made no DB creation, restart, restore, schema reset, asset-12 approval or shared HTTP/SQL writes in response. There is no claim that the former UUID, data or runtime is present or healthy.

My own browser was put offline before reading/exporting drafts, then page JavaScript was disabled to freeze autosave/polling. The browser and IDE remain open. My frontend listeners 127.0.0.1:18098 and 127.0.0.1:18102 were stopped with the approved helper and both ports were checked closed. Do not use a former endpoint as a current healthy entry. App restart ownership remains solely with play.

Owned recovery directory outside the repository: /private/tmp/codex-k10-recovery-20261105/ (directory 0700, exported files 0600). Exact owned paths reported to the coordinator:
- browser-drafts-offline.json: only the surviving pro_editor draft key and allowlisted author UI/save state. Current snapshot has two chapters and zero formal nodes. No login storage or app globals.
- business-readbacks-offline.json: three allowlisted business network entries, no headers or login responses; rig truncation is marked. These are historical readbacks, not current DB evidence.
- author-ui-offline.jpg and screenshots-preincident/ (25 screenshots).
- historical-readonly-facts/theme-990016.json, SHARED-BACKEND-PROOF.json and HARNESS-SHA256.json. These were captured before the incident; they are not a DB dump or current health/UUID proof.
- README.txt and MANIFEST.json with file sizes/SHA256. All original 32 files were hash/size-verified; source-checks/ adds three local test/gate logs and the JAR VERIFIED.json, bringing the manifest to 36 files.

The harness wx storage is in process memory and resets on reload; localStorage is empty. Earlier one-photo-node authoring is preserved in screenshots but is not part of the surviving two-chapter/zero-node draft. Do not call this a complete recovered fixture. No credentials, environment files, private keys or login storage were exported. Private recovery material is not committed.

External standins remain preserved as requested: cy-k10-external-standins-20261105, task=codex-k10-immersive-20261105, 96 MB / 0.25 CPU, VPS loopback WeChat 127.77.10.1:443 and OSS 127.77.10.2:443. No second app/DB or host DNS/proxy changes. App-only hosts mappings are api.weixin.qq.com -> 127.77.10.1 and oss-cn-shanghai.aliyuncs.com / rig-offline.oss-cn-shanghai.aliyuncs.com -> 127.77.10.2. Test settings and truststore remain in /home/ubuntu/previews/codex-k10-immersive-20261105/external; read-only app truststore path /k10-external/truststore.jks. TLS, token seam, image allow/reject, unknown 404 and OSS PUT/GET/HEAD were tested before the incident. Historical upload 200/APPROVED does not prove present runtime health.

Verified GitHub JAR, distinct from runtime/data recovery

Manual backend-jar.yml run 42006455345 produced source da9b30f1dd770ce0c4cc55b278e9d08e1ad99a7f. No new workflow was started by this lane. Local checksum, embedded git commit and remote copied checksum were verified. Size 119642548 bytes, SHA256 f4c1fe89441cf2cba7feb03addf3774c17aed23fe6bff17e6d5d1a02c681f429. Local evidence: /private/tmp/codex-k10-rig-20261105/github-jar-da9b/VERIFIED.json. Remote staged JAR: /home/ubuntu/previews/codex-k10-immersive-20261105/external/gh-jar-da9b/chengyinhub-admin.jar. Play independently staged it; app is stopped. This is only artifact evidence, not a deployment, data recovery or health check. No video schema migration was applied by this lane.

Actual editor walk and limits

Author 9003 created draft 990016 K10-CODEX-1105夜航 through the professional editor. It is reserved for this lane. Before the incident, a chapter/story, a TIME text egg, empty backpack capacity zero and saved draft were independently read back in the then-running task DB; the exported JSON is historical only. No test-game authoring data was inserted through SQL or API. The new 序章 entry was clicked, text entered and returned to the chapter list. Cover upload previously showed the actual synchronous-WeChat 503; after the test standins a cover upload returned 200 before the incident. A photo game was created and bound through the UI, but the older backend could not complete the new save/readback path. Theme music upload returned HOLD; asset12 was not approved. Full media configuration was not completed.

The generic photo + branch/egg save, full publish/reopen, player proof/reward/branch/egg run and final ending have NOT yet been proven on the updated backend. A recovered, explicitly verified task backend/database is required before resuming those checks. A screenshot, save toast or JAR alone is not acceptance.

Cross-lane work

Video worker owns opening/ending video and the theme music row. Cloud m1 owns other NO_BRIDGE mechanisms; qaShot bridge and one-proof changes above are already on integ and must be preserved. Cloud m2 owns the caption/polish list and cloud m3 owns weak-network/media failure/four-look walks. I am not starting those. Full acceptance remains one UI-authored game combining opening/ending media, theme music, narration, photo/QR, branches and text/reward TIME/STEPS eggs; no authoring DB/API shortcuts.

Phone/review limit: audio background declaration may require WeChat review before release. The browser rig cannot prove iOS/Android lock screen playback, simultaneous real music/narration, the physical silent switch, call interruption, headphones, actual camera or scanner behavior. Those remain phone checks. No claim of phone or production acceptance is made.

Final code SHA: cbc857d9da1d1d94b8e060f5f4219f9ed61e25cb. GitHub artifact source is da9b30f1dd770ce0c4cc55b278e9d08e1ad99a7f, a different checkpoint. All changes are source/test work on integ, with no production deployment or WeChat release.

My list is NOT finished. Remaining: execute the corrected focused Java tests; verify E16/E18/E21 through actual author-UI save/reopen/publish on a verified recovered rig; complete one ordinary UI-authored game with photo, QR, branches, TIME/STEPS text-and-reward eggs and all approved media, then play its completion/rewards/ending. Coordinate the TEAM egg boundary with m1. Real-phone background/lock-screen/silent-switch/interruption checks remain. No authoring DB/API shortcuts may replace these walks.

STATUS: PARTIAL - shared database incident halted all writes and walks; snapshots preserved, source fixes delivered, corrected Java tests and full UI-authored end-to-end/phone acceptance remain unverified.
