# 6fcd internal MySQL transport SOURCE

Parent: `6fcdf747e17cb5094121f609ae33f7734fda2ae7`.
Original run/job/artifact: `40232934720` / `121288643231` / `12614105173`.
Original ZIP SHA256: `e24fa808b69dd28f253ccdccc5018d18fe87ce82e438e3fe574aa8e11c385601`.

## Confirmed boundary

The original receipt is a source-create ownership predicate failure:
`loopback-binding / OwnerGuardFailure / BINDING_MISSING`. It is not evidence of
a Docker command rejection: no subprocess return code or stderr was recorded.
Cleanup independently confirmed `EXACT_ABSENCE` with no failures. No cohort
business work ran; the original failure is not relabelled as success.

The original job names runner image `ubuntu-24.04`, version `20261012.334.1`.
Its [published manifest](https://github.com/actions/runner-images/blob/ubuntu24/20261012.334/images/ubuntu/Ubuntu2404-Readme.md)
lists Docker client/server 28.0.4 (manifest blob
`e627f14a3702c8c01ea2cc3ed3a7eae6b1941da5`). The original log did not separately
print the running daemon's `docker version`; that distinction remains explicit.
[Moby v28.0.4 endpoint.go](https://github.com/moby/moby/blob/v28.0.4/libnetwork/endpoint.go#L698)
calls `ProgramExternalConnectivity` only when `!n.internal` (blob
`4bca0cf31670f59559b9283ca12158d00e7935a8`). The same version's
[daemon/network.go](https://github.com/moby/moby/blob/v28.0.4/daemon/network.go#L928)
also returns no port-related endpoint options when `n.Internal()` (blob
`bfc7a75d8395cbb8ac14ece4ebd6893ec3fd8aa4`); its `getEndpointPortMapInfo`
builds inspect mappings from driver data, not the requested HostConfig alone.
Thus an internal-only endpoint does not establish the external port mapping
assumed by the old mandatory binding check. Waiting longer is not the proposed
compatibility mechanism.

## Minimal reuse and required shared seams

The original Docker context/image, internal network, names/labels/CIDs, create
arguments including loopback-only `-p`, two-container CPU/memory limits and fixed
SQL remain unchanged. A valid actual Docker loopback binding still takes the
original path and retains all its negative checks.

When no mapping exists, the host uses the existing authenticated-recovery MySQL
relay implementation, without a second forwarding engine, CLI target option,
extra container/network or external publication. Native TCP readiness through
the exact container must succeed first. That excludes the initialization
socket-only server. Native and loopback SQL must return the same full server
UUID, MySQL 8.0.45 and event_scheduler OFF, within the original readiness budget.
Source and target retain distinct UUIDs/ports and the original SQL restrictions.

The host supplies the existing relay with an endpoint reader that checks the
original full CID, image, scope label, internal network ID/IPAM, sole attachment,
private address and running PID. No published container port may exist in this
relay path. The existing relay rechecks identity before bind, each upstream
connection and phase completion; its upstream port remains fixed at 3306.

Two shared-owner changes are supplied separately as review.patch for root:

1. `authenticated_redis_recovery_probe.py`: extract its existing forwarding body
   into private `_owned_mysql_relay(expected, current_endpoint)`. The original
   `mysql_relay(name, baseline, suffix)` still validates its own auth endpoint and
   yields the same integer port. Forwarding, idle limit, socket drain/join and
   original exception preservation are reused unchanged.
2. `restore_business_reconciliation_probe.py`: retain its exact Docker binding
   branch, and admit only the named owned relay transport after host validation.
   The private descriptor binds the endpoint to a live IPv4 127.0.0.1 LISTEN
   socket, owner PID/start ticks, exact FD/inode and current original endpoint.
   The subsequent original SQL independently proves the saved server UUID.
   Missing, replaced or all-interface listeners are rejected. Image, resource
   budget, scope, distinct-server and other-port checks remain in the C probe.

Host alone is not a runnable complete integration: both shared seams and their
direct controls must be applied to the same source by root. No C/F worktree is
overwritten by this delivery. No workflow or full-seven summary change is made.

Relay contexts close and join before exact container/network cleanup. Relay
close failure cannot skip cleanup, erase the original first failure or become a
clean result. Private relay descriptors/defaults are removed by original private
cleanup. The public host receipt adds only fixed transport names per side; it
contains no IP, PID, port, credentials, command, defaults or raw stderr.

## Controls and limits

All original host controls and financial scenarios are retained. The original
missing-binding input now proves that an absent original endpoint cannot enter
relay readiness (`RELAY_RESOURCE_MISSING`), rather than requiring Docker port
publication on an internal network. Other binding/address/range/config negative
controls remain. New source controls cover original endpoint/owner/IPAM checks,
listener replacement/wildcard rejection, native-to-relay identity equality and
mismatch, fixed deadline, close-before-cleanup and first-failure preservation.
Shared controls retain original relay handler/negative cases and check the
unchanged public integer adapter plus C's strict per-endpoint validation seam.

Tests, imports/parsing controls, compilation, Docker/MySQL/HTTP/resources and CI:
**NOT_RUN**. Source doubles in the controls do not prove actual relay connectivity
or cohort business recovery. Root must first validate the fixed payment-gap
diagnostic on the newly integrated source. Full seven-cohort reconciliation,
real provider activity and production acceptance remain unverified.
