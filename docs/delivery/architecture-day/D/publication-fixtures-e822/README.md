# Publication fixtures e822 — SOURCE only

Parent: `e82229ef85e58159078a42956560356074192541`.
Parent tree: `ed211d0215b206a682697d9470307c0fb0181d53`.

Changes are limited to the shared TopicPublicationTestWiring and the four assigned unit classes. Production, E/H fixtures, clients, schema, dependencies, CI and old delivery sources are unchanged.

- Shared signatures wire/set/get/owner/wireRelocatedCollaborators/transactional remain. At the last wire, the existing real reference collector and runtime receive the same current topic/chapter/node/template/declaration ports as the facade/publication owner. Keep the real runtime object and its other constructor ports; no compiler/freeze result is stubbed.
- Narrative NPC and route fixtures explicitly supply real ThemeStateKeyService and use the existing real Spring transaction-interceptor helper. StateKey fixture also uses this existing proxy. Removed only the old manual transaction-active toggles; no production transaction requirement was relaxed. These mapper-double unit fixtures are not database/rollback evidence.
- Three StateKey chapter rows retain their original blocks and receive the existing fixture chapter identity 88/topic 10. Current-content ownership guards still run; original ServiceException and zero-write assertions remain.
- DraftSubmittedOrder reads the actual inserted CmsTopic back under the existing strict parent lock. Original insert Answers populate one chapter/node fixture table; deletion and current reads observe it. Seeded old identities remain 11/101 within topic 700, with the old node explicitly active. Original chapter/node submitted-array order, server-generated ID and delete/draft/no-insert assertions remain.

Original case names/order are preserved: NarrativeNpc 5, StateKey 25, RoutePolicy 8, SubmittedOrder 2 (40 total). No cases or business assertions removed/disabled; no new cases. ORIGINAL-CASES.json records source method names, not executed results.

Source evidence only: exact five original Git blob IDs matched the parent; owned diff and added-line secret-pattern inspection performed; SHA256 postimages recorded. Field names/types, the existing ThemeStateKeyService constructor, existing helper signatures and runtime/reference-owner fields were read from e822 production/house-helper source. No parser, tests, compilation, Java/JUnit/DB/HTTP/provider or CI execution: NOT_RUN. Root owns combined review and runtime acceptance.
