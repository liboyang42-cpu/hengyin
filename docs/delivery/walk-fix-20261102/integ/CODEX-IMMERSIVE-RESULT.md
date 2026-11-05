Immersive K10 result — work still in progress

Cloud K10 6de347d40 and K8 2a316fe1f were read and rebased. Their fixes are retained. I waited the requested 25 minutes before the cloud result arrived. No production connection, deployment, master push, PR merge, schema reset or game-authoring SQL/API shortcut was used.

Source delivered

- c09b51b87: k10-theme-check.py, k10-theme-check.md and k10-phone-script.md. The cloud lane's additions to the two documents are retained. The checker is static inspection, not a gameplay pass.
- 37131d5ca: an empty backpack may have capacity zero, matching the server. Actual items still require capacity.
- a2285b3dd: resume the original playing tracks on return. Cloud audio work is retained.
- 6f63e08d3: E20 small 添加序章/编辑序章 entry beside 添加章节, reusing openTopicOpening. No new captions.
- ac2ac8571: E16/E10 ordinary qaShot now proposes an upload-reference requirement. Advanced runtime verifies the original uploaded image and uploader before applying state/score. The existing generic task-fact transaction records completion. The compiler and capability fixture allow this real path. Guided objective-answer verification was not loosened.
- 87cb26eb8: E21 author-saved-detail is an owner/scope-checked read-only readback, available after the edit window closes. Existing edit/write gates remain. Publish readback still checks topic, original version, status and saved content. E17 error and explanation now scroll beneath the chapter cards instead of covering their buttons.
- 1a777186d: added the missing assertNotEquals static import after the actual testCompile failure. No Java tests had run on that failed attempt.
- 1f7c78af4: one station proof. Generic games return to existing task settlement, not the old photo/scan sheet. Linear photo/scan stations may use arrive completion only when their current server session has completed that game; photo ownership is reverified, using the uploader stored by the server. Without proof the original rejection remains. Already-published themes use their frozen current-run config; no theme or run snapshot is rewritten.
- Background audio source is locally committed and awaiting push with this report: app.json declares audio, active runs do not pause tracks on onHide, leaving/unloading/ending does pause, interruption handling is retained, and play contexts ignore the silent switch. This is not a phone lock-screen PASS.

E18: an explicit chapter ALL marker is deliberately retained because it clears previously compiled ANY_N/exclusive rules. It must not be deleted blindly. The qaShot proof path removes the save rejection that made this residue fatal. A regression covers a photo theme with an egg and then the restored ALL/empty-egg configuration. Its Java execution is still pending below; the UI save/reopen test is not yet complete.

Raw generic error text was already translated by the shared k7 work now on this branch. That implementation was retained. No second translation layer was added.

Validation so far

- 109 focused JS tests passed for matrix, server draft and editor flow after rebase. 25 audio/config/resume tests passed. The photo/scan continuation behavior test exercises both generic and linear UI paths.
- Action ledger regenerated and checked: 5727 controls, zero dynamic evidence results. UI integrity passed after moving two existing allowlist anchors; original call lines were compared byte for byte, counts/reasons unchanged.
- Main package preflight reports 2.98 MB under the 3 MB ratchet. Release preflight is BLOCKED by the existing action ledger dynamic-evidence requirement; this is not a release approval.
- One actual budget212 run used source 1f7c78af4, an isolated source/cache and these related classes only: AdvancedActionJudgementTest, AdvancedGameRuntimeServiceImplTest, GameplaySupportMatrixTest, GameplaySupportMatrixRuntimeTest, TopicRoutePublicationOutcomeScopeTest, ApiTopicSaveDraftContractTest, TopicAuthorEditDateWireTest, ApiPlayGpsGeofenceValidationTest.
- That run stopped at testCompile: ThemeRuntimeMySqlIT.java:101,319 has ambiguous Date (java.util.Date / java.sql.Date). No selected Java tests executed and no new JAR was produced. Root was notified because this is outside my owned test files. Budget lock is released. Earlier requests were refused with existing_java_build, not memory.
- The existing pre-commit hook unexpectedly started a non-budget remote compile. I stopped exactly its own cy-uifeat-hook-58771. Subsequent backend commits use a per-command hook copy retaining JS/JSON syntax, protected-file and DS checks while delegating compilation to budget212/CI. The shared hook was not changed.

Rig and ownership

- Browser entry owned by this lane: http://127.0.0.1:18098 . Shared backend forward: http://127.0.0.1:18099 . 8132 is down and is not used.
- Running app source was independently read from its actual classpath app-a2285b3dd: a2285b3dd558d40357462a87cd43a70b8d538548. It does not contain the new generic-photo bridge yet. Play retains sole ownership of restarting cy-play-map-followup-20261105-app.
- Shared task DB: VPS loopback 127.0.0.1:23316, cy_e2e_51, UUID 76fd666f-d8ef-11f1-b090-d26bfff2310e. Shared task resources are cy-play-map-followup-20261105-app, cy-chain-play-map-20261105-1-mysql and cy-chain-play-map-20261105-1-redis. No second backend or database was created.
- User-delegated external standins: cy-k10-external-standins-20261105, task=codex-k10-immersive-20261105, 96 MB / 0.25 CPU. It binds VPS loopback only: WeChat 127.77.10.1:443 and OSS 127.77.10.2:443. No host DNS, proxy or production credentials were changed. Keep it running for all shared walks as requested by play.
- Remote directory /home/ubuntu/previews/codex-k10-immersive-20261105/external contains standins.env (synthetic test settings), cert.pem, truststore.jks, and proof.json. App-only hosts mapping: api.weixin.qq.com -> 127.77.10.1; oss-cn-shanghai.aliyuncs.com and rig-offline.oss-cn-shanghai.aliyuncs.com -> 127.77.10.2. Truststore is mounted read-only at /k10-external/truststore.jks. Only the cloud wxfake bind was adapted; fakeoss is unchanged.
- TLS hostname validation, token path, image allow/reject marker, unknown path 404, and OSS PUT/GET/HEAD with metadata were actually checked. Play wired only the app container and confirmed a real upload returned 200/APPROVED. My browser's cover upload also now succeeds.
- Reusable older JAR: /home/ubuntu/previews/codex-k10-immersive-20261105/source/chengyinhub-admin/target/chengyinhub-admin.jar, source a2285b3dd558d40357462a87cd43a70b8d538548, SHA256 eee1b054731722f2b100dfffaac9785318b46617aa2eb790e65aef46e33f15a9. Do not label it as a build of the new proof path.

Actual editor walk and limits

Author 9003 created draft 990016 K10-CODEX-1105夜航 through the professional editor. It is reserved for this lane. A chapter/story, a TIME text egg, empty backpack capacity zero and saved draft were independently read back in the task DB. No test-game authoring data was inserted through SQL or API. The new 序章 entry was clicked, text entered and returned to the chapter list. Cover upload previously showed the actual synchronous-WeChat 503; after the test standins it succeeds.

The generic photo + branch/egg save, full publish/reopen, player proof/reward/branch/egg run and final ending have NOT yet been proven on the updated backend. The source/JAR mismatch must be resolved before claiming those. A screenshot, save toast or JAR alone is not acceptance.

Cross-lane work

Video worker owns opening/ending video and the theme music row. Cloud m1 owns other NO_BRIDGE mechanisms; qaShot bridge and one-proof changes above are already on integ and must be preserved. Cloud m2 owns the caption/polish list and cloud m3 owns weak-network/media failure/four-look walks. I am not starting those. Full acceptance remains one UI-authored game combining opening/ending media, theme music, narration, photo/QR, branches and text/reward TIME/STEPS eggs; no authoring DB/API shortcuts.

Phone/review limit: audio background declaration may require WeChat review before release. The browser rig cannot prove iOS/Android lock screen playback, simultaneous real music/narration, the physical silent switch, call interruption, headphones, actual camera or scanner behavior. Those remain phone checks. No claim of phone or production acceptance is made.

Final source SHA: pending completion of this lane. Current delivered backend source 1f7c78af4; local audio commit 44eefd890.

STATUS: PARTIAL - related Java tests are blocked at another test's ambiguous Date; updated-backend UI-authoring and end-to-end photo/branch/egg walk still required; phone audio checks remain unverified.
