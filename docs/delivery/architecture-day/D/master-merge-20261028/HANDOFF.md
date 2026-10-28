# D frozen three-way SOURCE donor

Owned donor: e4011da72cc484f34dbaaf4e85fe3dbf7c26acfb
Parent: 62d4e0bbf47efa2f73aa7e604f1dfe092518d923
Tree: f8b7a1aa81587305d0af147dd2f8832598b82be5
Source compared: 1b71ae555a3141da948fa4e36ea68cb48a7b9b39
Mergebase: 51f05499c1523648a328f57bcd7927f6d9546003

The owned donor changes exactly the seven assigned source paths; no deletions. This transport commit changes only this D delivery directory. Root selects/assembles the final two-parent merge.

owned.review.patch is master62d4 -> all seven resolved postimages (88952 bytes, SHA256 a869476566c92d6e564113ba6e542a81786e6493ac44db45b2e283f3a7e6596c).
cross-owner.review.patch is a proposed ROOT-OWNED follow-up only, NOT applied to production/test paths (8807 bytes, SHA256 a010d6e514282295337151dd870a26676f86f96911d8e48b2d5eeab7a3d8d8df).
POSTIMAGES.sha256 lists all seven owned code postimages.

Required root follow-up:
1. OfficialBroadcastDeliveryService.java: preserve original constructor; inject existing UmsMemberMapper; use batch -> member -> delivery -> original IM intent/message -> terminal receipt in one short transaction; count the same 5/60-minute budget under the member fence. Preserve UNKNOWN/no blind remote retry. Require aggregate update rowcount. Root review preimage SHA256 71b714c0f2e96c0b4869fd0570edc5381b36a36d4b4bdb837460911827c6f033; proposed 5126003f195cdfddf02bd768b2fa977dca8e5c157b9d5c2305f9a65b8b8093cf.
2. business/service/OfficialBroadcastDeliveryMySqlIT.java directly constructs that owner. The proposed same-DataSource JDBC member-fact adapter is included; original cases/assertions/cleanup remain. This is not full member/IM integration. Preimage SHA256 6f1705d995a7a73ca09c2412e466a074be868580f372298857bbf3b9cfee3c3a; proposed bbbc2947b6268aa844e4a3fae01d2873f2ffa08e7261f58ec3de7d902db6de38.
3. SQL owner must resolve BOTH existing canonical migrations before runtime acceptance: 20261023_d and 20261026 duplicate delivery_state/message_id; state width is 16 vs 32. Both frozen-title and original delivery-title column families are required. Do not blindly execute governance backfill/postflight on already migrated master states: it would conflict with existing columns/known states. Preserve original identities and all receipt/audit history. Existing INAPP_STORED reached_time must come from retained actual update_time where proved, not an invented timestamp; future INAPP_STORED updates now record reached_time. The master MySQL fixture still selects its original two migrations: root must bind it to the approved combined migration sequence; this SQL decision is intentionally NOT guessed here.

Semantics retained: master creation key/hash/receipt/cancel/recover, UI original request journal, frozen audience and original durable IM identity; governance original typed-ledger recovery, member-fenced local atomic receipt, restore guard, channel validation and separate member/channel statistics. Persisted request/state evidence chooses the existing delivery protocol; exceptions never trigger a fallback or new identity. UNKNOWN stays unconfirmed. Mapper lockDelivery is master Map; lockDeliveryIntent is the original typed row lock with the same SQL scope. Existing governance MySQL IT does not call lockDelivery directly.

Source test inventory: Delivery 15 original governance + 7 original master nested + 2 new fence cases = 24; Contract 17 originals with governance bodies plus 5 preserved master durable-body variants = 22. Original assertions are retained. No cases disabled.

Git apply-check of owned patch succeeded; preimage blob hashes and changed-path scope inspected; added-line secret scan had zero matches. All project parsing/import/test/build/Java/DB/Redis/Docker/HTTP/WeChat/provider/CI execution NOT_RUN. Do not inherit source1b71's PASS onto this donor; all_complete remains false. Original workspaces remain untouched.
