# E — M28 publication identity relocation (SOURCE)

Fixed public inputs: master `d4cc5c59f65dcc0196a88eb556de9a10b663c56d`; governance `a50f661e157bf56b1c40d3f590c8dcc1dd513003`; merge base `e52eab7e44835ff6440761eebcdd1420a6406384`. No newer master/private history is used.

Native three-way reconstruction has one CmsTopicServiceImpl conflict: master changed its old journeyStoryAvailableNpcIds collector, whereas governance had removed that collector from the facade. The resolved owned postimage equals governance exactly. It retains publication/content delegates, version-bound attribute freezing, registry/current-declaration guards and all other facade behavior; it does not restore dead npcProfileMapper or merchant directory fields.

## Required shared integration

The actual governance owner is `TopicPublicationContentGuard.journeyStoryAvailableNpcIds(Long,CurrentContent)`, not TopicRoutePublicationCompiler. The compiler has no NPC mapper, directory or NPC-ID collection. Changing its constructor would add an unused dependency. The existing guard helper is reached by block/chapter narrative publication and by journey/story reference validation (including the locked CurrentContent path). No route/compiler/runtime constructor or public interface needs changing.

**Root must apply `shared-m28.review.patch` to the two listed governance postimages.** It is intentionally NOT written into this donor's shared production/test paths. Without this hunk the conflict is textually resolved but M28 behavior is not integrated.

The guard receives the existing master `MerchantNpcIdentityDirectory` component and uses `availableMerchantIdForMember(node.getMerchantMemberId())` before querying scope 2 with the returned merchant row ID. Null/unavailable/ambiguous identity contributes no merchant fallback; directory/storage failures are not swallowed. Scope-4 node NPCs, existing current/ordinary node selection, original exceptions/diagnostics and publication ordering remain. No new SQL, permission policy, component or fallback is introduced.

The original TopicPublicationTestWiring.set dynamically locates relocated fields on the actual guard, so no shared helper change is required. The existing narrative test installs a real directory over its mapper double. Its original five tests/assertions remain. Five new test SOURCE methods exercise the real publish facade/guard and existing compiler wiring:
* merchantBlockNarrativeUsesMerchantRowIdInsteadOfOwnerMemberId
* ownerMemberNumericAliasCannotMakeAForeignMerchantNpcAvailable
* unavailableMerchantCannotContributeItsScopeTwoNpc
* merchantLookupFailurePropagatesWithoutPublishingOrNumericFallback
* journeyStoryMerchantSpeakerUsesTheSameResolvedScopeTwoIdentity

The tests use deliberately different member 210 / merchant row 700 IDs and a decoy scope-2 member-ID lookup, preserve no-publication assertions on refusal and cover the journey speaker entry. These are mapper-double publication units, not database/merchant-channel acceptance. The existing master MerchantNpcIdentityDirectory and its tests are unchanged.

The manifest provides exact before/after blobs and SHA256 for the owned postimage and both review postimages. `owned.patch` is master-to-resolved; `shared-m28.review.patch` is governance-to-proposed and cannot be treated as a master-based hunk. No deletion is proposed.

All parsing/import, tests, compilation, Java/DB/Redis/Docker/HTTP/WeChat and CI: **NOT_RUN**. Only source reading, native merge/diff/apply-check, hashes and a limited secret-pattern scan are performed. This isolated one-parent donor transports source; it is not the coordinator's actual two-parent merge and inherits no prior runtime acceptance.
