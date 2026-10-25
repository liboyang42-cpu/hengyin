# Checkin fixture contract drift

Parent a6e8163e6983157d2e67a1e32ca2e7ced0b9593f; complete original A chat diff, root apply-check PASS.

Actual all16 fixed contexts PLAYER_ARRIVAL/CHECKIN plus real executeBasicIntent preflight prove missing expectedRouteVersion/routeSessionId deterministically reject the old fixture. nodeId also now explicit. Minimal repair reads real action-context HTTP, verifies same original route session and current integral nonnegative version, then submits checkin. No production guard weakened; original26 cases and325 assertions retained,3 assertions added.

Source patch SHA256 4a7a6954a7d452a543455c10284892df24acd75c82645294f968802f72780b53

Java/DB/realHTTP NOT_RUN; no claim16 cases passed, deeper outcomes still pending.
