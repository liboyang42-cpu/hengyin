# Mini Program main-package source reduction

Source baseline: 11c8055e179930cd63a1d6e296b8285b0586dd16.

The existing release preflight counts uncompressed source bytes using
`chengyinhub-xcx/project.config.json` file exclusions and top-level folder
exclusions, while skipping hidden directories and all registered subpackage roots.
The existing 3 MiB BLOCK threshold is unchanged.

| Source bytes | Baseline | Candidate |
| --- | ---: | ---: |
| Main package | 3,257,791 | 3,131,243 |
| Existing ceiling | 3,145,728 | 3,145,728 |
| Margin | -112,063 | 14,485 |

These counts were calculated from the complete Git tree's blob sizes using the
existing preflight's inclusion rules. No edited consumer remains in the main
package: every runtime consumer edit is within its existing subpackage.
The already excluded guide-progress helper follows its policy dependency into
pages/publish/utils/publish. Its original blob is unchanged and its existing
exact-file exclusion is repointed to that new location; it remains outside the package.

## Change and reference evidence

34 files (126,548 baseline bytes) move from main-package directories into their
existing exclusive consumer's subpackage. The audit manifest is
`chengyinhub-xcx/scripts/baselines/mainpkg-leaves.json`; it records each original
blob SHA and destination. No page registrations, routes, preload rules, package
roots, dependencies, release thresholds or dynamic action evidence change.

- Three exploration-tool clients and their common stylesheet belong to subpackageTools.
- Editor policy and chapter-title WXS belong to pages/publish.
- Other leaf utilities belong to their single existing consumer package.
- The cooperation invite sheet belongs to subpackageMember; its shared dependencies
  continue to resolve to the main package.
- Fourteen images move with their only consuming package. Their Git blob SHAs
  are reused unchanged, including the development-only club mock image.

All 1,853 runtime JS/WXS/JSON/WXML/WXSS files at the source baseline were read,
including main and subpackages. Static require/usingComponents/import references
were checked before moving files. The only dynamic image filename constructor is
the existing interaction-template path; its seven covers and license remain
packaged. The relocated images have explicit references. Existing business
tests are repointed to the relocated implementations, with their assertions
retained. The existing path-sensitive controls stay unchanged: predict-entry, capsule-client
and exploration-goals stay in main so their existing silent-error and danger-action
checks retain their original scope and registrations.

The new package ownership contract checks that reviewed leaves remain included,
old paths stay absent, consumers use the same package, and moved-file dependencies
resolve. Negative controls cover stale require/WXS/component paths, dynamic image
prefixes, a missing stylesheet and a cross-package require/image.

## Validation

After the cloud workspace became available, Node 24.19.0 actually ran:

- Changed unit contracts: 434 passed, zero failures/skips, including the reference
  and package ownership contract and its negative controls.
- UI lint: PASS. Scope lint: PASS (711 production JS files; zero unresolved
  identifiers or missing exports). RequestBody lint: zero errors, two existing warnings.
- Danger-action gate and its negative-control selftest: PASS. The existing
  silent-error gate: PASS, with its allowlist and ratchets unchanged.
- Source-only WeChat release contract: privacy PASS; compiled size WAIT-EXTERNAL.
- Release preflight: main source size passes at 2.99 MiB; exit 1 remains because
  dynamic action evidence is pending=5103, nonPassed=0.

The generated static Action Ledger inventory is refreshed for the moved invite
sheet. All 5103 complete control contracts are identical after inverse path
normalization, every embedded status remains pending, and action-evidence.json's
SHA-256 is unchanged. This fixes static inventory drift without claiming dynamic
acceptance. The action-ledger regression suite passes all 46 tests, including
the original inventory freshness failure and its negative control.

Full Git-tree/worktree byte calculation, unchanged image/app/preflight
blob checks, edited-JS/JSON parsing, and an introduced-line credential/private-path
scan also completed. PR #590's first CI run is
https://github.com/liboyang42-cpu/hengyin/actions/runs/40062603587 ; it exposed the
path-sensitive gate and test-harness issues corrected in this candidate. The final
head must pass the existing PR CI before integration. No workflow was manually
dispatched.

The release preflight's separate dynamic action evidence BLOCK is owned by the
coordinator and is deliberately preserved. There is no WeChat compiler receipt;
compiled main/subpackage sizes, simulator/device behavior and real sharing flows
remain unverified. No DevTools operation, preview, upload, publication or deployment
was performed.
