# Mini Program main-package source reduction

Source baseline: 11c8055e179930cd63a1d6e296b8285b0586dd16.

The existing release preflight counts uncompressed source bytes using
`chengyinhub-xcx/project.config.json` file exclusions and top-level folder
exclusions, while skipping hidden directories and all registered subpackage roots.
The existing 3 MiB BLOCK threshold is unchanged.

| Source bytes | Baseline | Candidate |
| --- | ---: | ---: |
| Main package | 3,257,791 | 3,114,862 |
| Existing ceiling | 3,145,728 | 3,145,728 |
| Margin | -112,063 | 30,866 |

These counts were calculated from the complete Git tree's blob sizes using the
existing preflight's inclusion rules. No edited consumer remains in the main
package: every runtime consumer edit is within its existing subpackage.
The non-runtime `utils/publish/guide-progress.js` remains excluded under its
existing exact-file rule.

## Change and reference evidence

37 files (142,929 baseline bytes) move from main-package directories into their
existing exclusive consumer's subpackage. The audit manifest is
`chengyinhub-xcx/scripts/baselines/mainpkg-leaves.json`; it records each original
blob SHA and destination. No page registrations, routes, preload rules, package
roots, dependencies, release thresholds or dynamic action evidence change.

- Five exploration-tool clients and their common stylesheet belong to subpackageTools.
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
retained. The UI lint exception for predict-entry is rekeyed to its new path,
with the same line and reason; its sorted-key digest is recomputed without
changing counts or thresholds.

The new package ownership contract checks that reviewed leaves remain included,
old paths stay absent, consumers use the same package, and moved-file dependencies
resolve. Negative controls cover stale require/WXS/component paths, dynamic image
prefixes, a missing stylesheet and a cross-package require/image.

## Validation status at candidate creation

Connector-side checks completed: complete Git-tree byte calculation, JavaScript
syntax parsing of edited JS, JSON parsing, static dependency resolution for edited
runtime modules, and a scan of newly introduced lines for private paths and
credential patterns. These are not shell or Node test results.

Node unit tests, UI/scope lint, the release preflight and the source-only WeChat
release contract were not executed in the cloud workspace before this candidate:
the execution environment remained unavailable. Existing PR CI and coordinator
checks must provide that evidence before integration. No additional workflow was
dispatched manually.

The release preflight's separate dynamic action evidence BLOCK is owned by the
coordinator and is deliberately preserved. There is no WeChat compiler receipt;
compiled main/subpackage sizes, simulator/device behavior and real sharing flows
remain unverified. No DevTools operation, preview, upload, publication or deployment
was performed.
