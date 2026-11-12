# State machines — where they live and how to change them

Full list and risk ranking: [INVENTORY.md](INVENTORY.md).

## Backend

| Aggregate | Machine | Guarded by | Test / lint |
|---|---|---|---|
| Topic lifecycle | `domain/enums/TopicLifecycleState` | `TopicLifecycleServiceImpl.transition()` + CAS | `TopicLifecycleServiceImplTest` |
| Audit task | `domain/enums/AuditTaskState` | `canReviewTransition`, `isOpenCode`, `OPEN_CODES_SQL` | `statemachine/AuditTaskStateMachineTest` (+ characterization) |
| Seat invitation | `domain/enums/SeatInvitationState` | `RegistrationSeatInvitationServiceImpl.transitionGuarded` | `statemachine/SeatInvitationStateMachineTest` |

Add a state or transition:

1. Add the value to the enum (append a new code; never renumber stored codes).
2. Add the edge to the enum's transition table. Unknown and null codes must stay fail-closed.
3. Write the SQL as a CAS (`WHERE status = #{from}`), and register the statement id in the machine test's writer list. The lint test fails if a mapper statement writes the column without being registered.
4. Run `mvn -q -pl chengyinhub-admin test -Dtest='*StateMachine*Test,*StateCharacterization*Test'`.

Money aggregates (registration payment, refund, settlement, withdraw, coupon, deposit): don't change outcomes. Add only guards that reject transitions the code already treats as impossible, with tests, and ask the owner first.

## Mini-program

- Helper: `chengyinhub-xcx/utils/state-machine.js` `createStatusMachine({ name, states, transitions })` gives `label(code, fallback)`, `keyOf` (unknown → `'unknown'`), `isTerminal`, `can(from, to)`, `toArray(field)`.
- Machine: `utils/coop-invite-status.js` (coop invite 0–5). `utils/coop-invite-view.js` and `pages/club/detail` derive their labels and badges from it.
- Also explicit: `utils/checkout/checkout-workflow.js`, `utils/order-status.js`, `utils/activity-status.js`.

Add a client machine: define it once in `utils/<aggregate>-status.js` with the helper, derive any legacy array exports with `toArray`, and add a lint in `tests/unit/qx-sm-state-machine.test.js` that forbids re-typing the table elsewhere. The server stays the source of truth: never show an unknown code as a known state.

Run `node --test tests/unit/qx-sm-*.test.js` and `node tests/lint/ui-integrity.js --warn-as-error` (must print `UI-GATE-0:PASS`).
