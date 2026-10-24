#!/usr/bin/env bash
# Real packaged application, disposable stores, no published ports or provider credentials.
set -euo pipefail
prepare_trace_runtime() {
  python3 - "$STRACE" "$OUT/trace-runtime" <<'PY_TRACE'
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

binary, destination = map(Path, sys.argv[1:])
environment = {k: v for k, v in os.environ.items() if not k.startswith("LD_")}
environment["LC_ALL"] = "C"
def output(command):
    return subprocess.run(command, check=True, capture_output=True, text=True,
                          env=environment).stdout
def checked_path(value):
    path = Path(value)
    if not path.is_absolute() or any(c.isspace() for c in value) or ".." in path.parts or not path.is_file():
        raise ValueError("Invalid trace runtime library path")
    return path
interpreters = re.findall(r"Requesting program interpreter:\s*([^\]]+)\]", output(["readelf", "-l", str(binary)]))
if len(interpreters) != 1:
    raise ValueError("Trace runtime requires one ELF interpreter")
loader = checked_path(interpreters[0])
libraries, observed_loader = {}, []
for line in output(["ldd", str(binary)]).splitlines():
    line = line.strip()
    if re.fullmatch(r"linux-(?:vdso|gate)[\w.-]* \((?:0x)?[0-9a-fA-F]+\)", line):
        continue
    match = re.fullmatch(r"([A-Za-z0-9_.+-]+) => (/\S+) \((?:0x)?[0-9a-fA-F]+\)", line)
    if match:
        name, value = match.groups()
        library = checked_path(value)
        if name in libraries:
            raise ValueError("Conflicting trace runtime library")
        libraries[name] = library
        continue
    match = re.fullmatch(r"(/\S+) \((?:0x)?[0-9a-fA-F]+\)", line)
    if match:
        observed_loader.append(checked_path(match.group(1)))
        continue
    raise ValueError("Unresolved trace runtime dependency")
if "libc.so.6" not in libraries or len(observed_loader) != 1 or observed_loader[0].resolve() != loader.resolve():
    raise ValueError("Trace runtime libc or interpreter missing")
destination.mkdir()
(destination / "lib").mkdir()
sources = {"strace": checked_path(str(binary)), "loader": loader}
sources.update({"lib/" + name: path for name, path in libraries.items()})
manifest = {}
for name, source in sources.items():
    target = destination / name
    shutil.copyfile(source, target)
    target.chmod(0o555 if name in ("strace", "loader") else 0o444)
    manifest[name] = hashlib.sha256(target.read_bytes()).hexdigest()
(destination / "manifest.json").write_text(json.dumps({"interpreter": str(loader), "files": manifest}, sort_keys=True) + "\n")
(destination / "manifest.json").chmod(0o444)
(destination / "lib").chmod(0o555)
destination.chmod(0o555)
PY_TRACE
}

check_trace_runtime() {
  docker run --rm --name "$RUN_NAME-trace-check" "${POOL_LABEL[@]}" --network none --read-only --cap-drop ALL \
    --user "$(id -u):$(id -g)" -v "$OUT/trace-runtime:/trace-runtime:ro" \
    -e LD_SHOW_AUXV=1 -e LD_TRACE_LOADED_OBJECTS=1 --entrypoint /trace-runtime/loader "$JDK_IMAGE" \
    --inhibit-cache --library-path /trace-runtime/lib --list /trace-runtime/strace > "$OUT/trace-resolved"
  python3 - "$OUT/trace-runtime/manifest.json" "$OUT/trace-resolved" <<'PY_RESOLVED'
import json
from pathlib import Path
import re
import sys

manifest = json.loads(Path(sys.argv[1]).read_text())
expected = {name: "/trace-runtime/" + name for name in manifest["files"] if name != "strace"}
loader_names = {manifest["interpreter"], Path(manifest["interpreter"]).name, "/trace-runtime/loader"}
# LD_SHOW_AUXV belongs to this exact --list process, so ASLR addresses are comparable.
hex_auxv = set("PHDR BASE FLAGS ENTRY NOTELF FPUCW SYSINFO SYSINFO_EHDR RANDOM HWCAP HWCAP2 HWCAP3 HWCAP4 DCACHEBSIZE ICACHEBSIZE UCACHEBSIZE L1I_CACHEGEOMETRY L1D_CACHEGEOMETRY L2_CACHEGEOMETRY L3_CACHEGEOMETRY".split())
dec_auxv = set("EXECFD PHENT PHNUM PAGESZ UID EUID GID EGID CLKTCK SECURE MINSIGSTKSZ RSEQ_FEATURE_SIZE RSEQ_ALIGN L1I_CACHESIZE L1D_CACHESIZE L2_CACHESIZE L3_CACHESIZE".split())
text_auxv = {"EXECFN": r"/trace-runtime/loader", "PLATFORM": r"x86_64", "BASE_PLATFORM": r"x86_64"}
seen, auxv = set(), {}
dependencies_started = vdso_seen = False
for line_number, raw_line in enumerate(Path(sys.argv[2]).read_text().splitlines(), 1):
    line = raw_line.strip()
    if raw_line.startswith("AT_"):
        metadata = re.fullmatch(r"AT_([A-Z0-9_]+):[ \t]+(\S+)", raw_line)
        numeric = re.fullmatch(r"AT_\?\?\? \(0x([0-9a-fA-F]+)\): 0x([0-9a-fA-F]+)", raw_line)
        if dependencies_started or not (metadata or numeric):
            raise ValueError(f"Invalid target auxiliary vector at line {line_number}: {ascii(raw_line)}")
        if numeric:
            # Linux RSEQ entries may be unnamed in this glibc's display table.
            name = {0x1b: "RSEQ_FEATURE_SIZE", 0x1c: "RSEQ_ALIGN"}.get(int(numeric.group(1), 16))
            value = str(int(numeric.group(2), 16))
        else:
            name, value = metadata.groups()
        pattern = r"(?:0x)?[0-9a-fA-F]+" if name in hex_auxv else r"[0-9]+" if name in dec_auxv else text_auxv.get(name)
        if name in auxv or pattern is None or not re.fullmatch(pattern, value):
            raise ValueError(f"Invalid target auxiliary vector at line {line_number}: {ascii(raw_line)}")
        if name == "SYSINFO_EHDR" and (not value.startswith("0x") or int(value, 16) == 0):
            raise ValueError("Target vDSO auxiliary address must be nonzero hexadecimal")
        auxv[name] = value
        continue
    dependencies_started = True
    if "SYSINFO_EHDR" not in auxv:
        raise ValueError("Target vDSO auxiliary address missing before dependencies")
    virtual = re.fullmatch(r"linux-(?:vdso|gate)[\w.-]* \((?:0x)?([0-9a-fA-F]+)\)", line)
    anonymous_virtual = virtual is None
    if anonymous_virtual:
        virtual = re.fullmatch(r"\t \(0x([0-9a-fA-F]+)\)", raw_line)
    if virtual is not None:
        if vdso_seen or int(virtual.group(1), 16) != int(auxv["SYSINFO_EHDR"], 16):
            raise ValueError(
                f"Target vDSO does not match unique process auxiliary address at line {line_number}: "
                f"reason={'duplicate_mapping' if vdso_seen else 'address_mismatch'} "
                f"expected={int(auxv['SYSINFO_EHDR'], 16):#x} actual={int(virtual.group(1), 16):#x} "
                f"vdso_seen={vdso_seen} kind={'anonymous' if anonymous_virtual else 'named'} "
                f"previous_kind={vdso_kind if vdso_seen else 'none'} raw={ascii(raw_line)}")
        vdso_seen = True
        vdso_kind = "anonymous" if anonymous_virtual else "named"
        continue
    match = re.fullmatch(r"(\S+) => (/\S+) \((?:0x)?[0-9a-fA-F]+\)", line)
    if match:
        name, resolved = match.groups()
        key = "loader" if name in loader_names else "lib/" + name
    else:
        match = re.fullmatch(r"(/\S+) \((?:0x)?[0-9a-fA-F]+\)", line)
        if not match:
            raise ValueError(f"Unrecognized target trace runtime dependency at line {line_number}: {ascii(raw_line)}")
        resolved = match.group(1)
        key = "loader" if resolved == "/trace-runtime/loader" else ""
    if key in seen or expected.get(key) != resolved:
        raise ValueError(f"Target trace runtime escaped its private bundle at line {line_number}: {ascii(raw_line)}")
    seen.add(key)
if not vdso_seen:
    raise ValueError("Target vDSO mapping missing")
if seen != set(expected):
    raise ValueError(f"Target trace runtime dependency missing: {sorted(set(expected) - seen)}")
print(f"PASS target trace runtime same-process vDSO={int(auxv['SYSINFO_EHDR'], 16):#x}; kind={vdso_kind}; bundled dependencies={len(seen)}")
PY_RESOLVED
  docker run --rm --name "$RUN_NAME-trace-check" "${POOL_LABEL[@]}" --network none --read-only --cap-drop ALL \
    --user "$(id -u):$(id -g)" -v "$OUT/trace-runtime:/trace-runtime:ro" \
    --entrypoint /trace-runtime/loader "$JDK_IMAGE" \
    --inhibit-cache --library-path /trace-runtime/lib /trace-runtime/strace --version > "$OUT/trace-version"
  grep -q '^strace -- version ' "$OUT/trace-version"
}

start_traced_application() {
  docker run -d --name "$RUN_NAME-app" "${POOL_LABEL[@]}" --network "$RUN_NAME" --dns 127.0.0.1 \
    --read-only --tmpfs /tmp:rw,size=256m --cap-drop ALL --cap-add SYS_PTRACE \
    --user "$(id -u):$(id -g)" \
    -v "$OUT/trace-runtime:/trace-runtime:ro" \
    -v "$JAR:/application.jar:ro" -v "$FIXTURE:/fixture:ro" -v "$OUT:/evidence" \
    -v "$OUT/trace-runtime:/evidence/trace-runtime:ro" \
    --entrypoint /trace-runtime/loader "$JDK_IMAGE" \
    --inhibit-cache --library-path /trace-runtime/lib /trace-runtime/strace \
    -f -s 0 -e trace=connect,sendto,sendmsg -E LD_LIBRARY_PATH -E LD_PRELOAD -E LD_AUDIT \
    -o /evidence/network.trace \
    java -Xmx512m -Djava.rmi.server.hostname=127.0.0.1 \
    -Dlogging.config=/fixture/logback.xml -Dlogback.configurationFile=/fixture/logback.xml \
    -jar /application.jar --spring.config.location=file:/fixture/application.properties \
    "--spring.datasource.druid.master.url=jdbc:mysql://$MYSQL_IP:3306/upgrade_startup_probe?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" \
    "--spring.data.redis.host=$REDIS_IP" "${POOL_PROFILE[@]}" >/dev/null
}

pool_execution_evidence() {
  python3 -I - "$ROOT" "$JAR" "$OUT" "$RUN_NAME" "$1" <<'PY_POOL_IDENTITY'
import hashlib, json, os, re, subprocess, sys
from pathlib import Path
root, jar, out = map(Path, sys.argv[1:4])
name, phase = sys.argv[4:]
path = out / 'pool-execution.json'
report = json.loads(path.read_text()) if path.exists() else {}
def output(args):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=15).stdout.strip()
def digest():
    h = hashlib.sha256()
    with jar.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''): h.update(chunk)
    return {'sha256': h.hexdigest(), 'size': jar.stat().st_size}
try:
    assert re.fullmatch(r'cy-startup-\d+-\d+', name)
    source = output(['git', '-C', str(root), 'rev-parse', 'HEAD'])
    tree = output(['git', '-C', str(root), 'rev-parse', 'HEAD^{tree}'])
    assert re.fullmatch('[0-9a-f]{40}', source) and source == os.environ['GITHUB_SHA']
    assert re.fullmatch('[0-9a-f]{40}', tree)
    run, attempt = os.environ['GITHUB_RUN_ID'], os.environ['GITHUB_RUN_ATTEMPT']
    assert re.fullmatch('[1-9][0-9]*', run) and re.fullmatch('[1-9][0-9]*', attempt)
    identity = {'sourceSha': source, 'sourceTree': tree, 'runId': run, 'runAttempt': attempt}
    current = digest()
    if phase == 'before':
        assert not report
        report = dict(identity, jarBefore=current, taskName=name,
                      jarProof='runner/mounted-file digests only; no independent downloaded JAR verification')
    else:
        assert phase in ('running', 'after')
        assert all(report[k] == v for k, v in identity.items())
        assert report['jarBefore'] == current and report['taskName'] == name
        obj, = json.loads(output(['docker', '--context', 'default', 'container', 'inspect', name + '-app']))
        assert obj['Name'] == '/' + name + '-app'
        assert obj['Config']['Labels']['com.chengyin.pool-recovery'] == name
        assert re.fullmatch('[0-9a-f]{64}', obj['Id'])
        mount, = [m for m in obj['Mounts'] if m['Destination'] == '/application.jar']
        assert mount['Type'] == 'bind' and mount['Source'] == str(jar.resolve()) and mount['RW'] is False
        raw = output(['docker', '--context', 'default', 'exec', obj['Id'], 'sha256sum', '/application.jar'])
        assert re.fullmatch(r'[0-9a-f]{64}  /application\.jar', raw)
        assert raw.split()[0] == current['sha256']
        if phase == 'after': assert report['appContainerId'] == obj['Id']
        report['appContainerId'] = obj['Id']
        report['jarMounted' + phase.title()] = current
        report['jar' + phase.title()] = current
    report['result'] = 'PASS_SOURCE_JAR_UNCHANGED' if phase == 'after' else 'RECORDED_' + phase.upper()
except Exception as exc:
    report['result'] = 'FAIL_EXECUTION_IDENTITY'
    report['failurePhase'] = phase
    report['failureType'] = type(exc).__name__
    raise
finally:
    path.write_text(json.dumps(report, indent=2) + '\n')
PY_POOL_IDENTITY
}

pool_owned_cleanup() {
  python3 -I - "$OUT" "$RUN_NAME" "$1" <<'PY_POOL_CLEANUP'
import json, re, subprocess, sys
from pathlib import Path
out, name, original = Path(sys.argv[1]), sys.argv[2], int(sys.argv[3])
report = {'taskName': name, 'originalExitCode': original, 'removed': [], 'errors': []}
owned_ids = set()
def call(args):
    return subprocess.run(['docker', '--context', 'default'] + args,
                          check=True, capture_output=True, text=True, timeout=15).stdout
def inventory(kind):
    text = call([kind, 'ls'] + (['--all'] if kind == 'container' else []) + ['--no-trunc', '--format', '{{json .}}'])
    rows = [json.loads(line) for line in text.splitlines()]
    key = 'Names' if kind == 'container' else 'Name'
    assert len({r[key] for r in rows}) == len(rows)
    return {r[key]: r['ID'] for r in rows}
def error(stage, exc):
    report['errors'].append({'stage': stage, 'type': type(exc).__name__,
                             'returnCode': getattr(exc, 'returncode', None)})
try:
    assert re.fullmatch(r'cy-startup-\d+-\d+', name)
    # The old probe field was a literal, never a measured connection timeout.
    receipt = out / 'pool-recovery.json'
    if receipt.exists():
        try:
            value = json.loads(receipt.read_text())
            value['profile'].pop('connectTimeoutMs', None)
            value['connectTimeoutObservation'] = 'UNKNOWN_NOT_MEASURED'
            receipt.write_text(json.dumps(value, indent=2) + '\n')
        except Exception as exc: error('receipt-profile-correction', exc)
    for kind, names in [('container', [name + '-' + s for s in ('app', 'mysql', 'redis', 'trace-check')]),
                        ('network', [name])]:
        try: before = inventory(kind)
        except Exception as exc:
            error(kind + '-inventory-before', exc)
            continue
        for target in names:
            if target not in before: continue
            try:
                identifier = before[target]
                assert re.fullmatch('[0-9a-f]{64}', identifier)
                obj, = json.loads(call([kind, 'inspect', identifier]))
                assert obj['Id'] == identifier and obj['Name'] == ('/' if kind == 'container' else '') + target
                labels = obj['Config'].get('Labels') if kind == 'container' else obj.get('Labels')
                assert (labels or {}).get('com.chengyin.pool-recovery') == name
                if kind == 'network': assert obj['Internal'] is True
                owned_ids.add(identifier)
                call([kind, 'rm'] + (['--force'] if kind == 'container' else []) + [identifier])
                report['removed'].append({'kind': kind, 'name': target, 'id': identifier})
            except Exception as exc: error(kind + '-remove-' + target, exc)
    # Successful, independent inventories distinguish absence from daemon failure.
    remaining = []
    for kind in ('container', 'network'):
        try:
            rows = inventory(kind)
            for target, identifier in rows.items():
                if target in {name, *(name + '-' + s for s in ('app', 'mysql', 'redis', 'trace-check'))} or identifier in owned_ids:
                    remaining.append({'kind': kind, 'name': target, 'id': identifier})
        except Exception as exc: error(kind + '-inventory-after', exc)
    report['remaining'] = remaining
except Exception as exc:
    error('receipt-or-scope', exc)
report['result'] = 'PASS_OWNED_RESOURCES_ABSENT' if not report['errors'] and not report.get('remaining') else 'FAIL_OWNED_CLEANUP'
(out / 'pool-cleanup.json').write_text(json.dumps(report, indent=2) + '\n')
if report['result'] != 'PASS_OWNED_RESOURCES_ABSENT':
    print('FAIL pool cleanup: ' + json.dumps(report), file=sys.stderr)
    sys.exit(1)
PY_POOL_CLEANUP
}

case "$(uname -s)" in
  Linux) ;;
  *) echo 'Startup smoke requires Linux; Mac Java/Docker execution is forbidden' >&2; exit 1 ;;
esac
if [ "${GITHUB_ACTIONS:-}" = true ] && [ "${RUNNER_ENVIRONMENT:-}" = github-hosted ] && [ "${RUNNER_OS:-}" = Linux ]; then
  test -z "${DOCKER_HOST:-}" || { echo 'CI must use its local daemon' >&2; exit 1; }
  STARTUP_DOCKER_CONTEXT=default
  EXPECTED_DOCKER_ENDPOINT=unix:///var/run/docker.sock
elif [ "${CHENGYIN_VPS_TEST_RUN:-}" = 1 ] && [ "${CHENGYIN_VPS_SOURCE_ON_DAEMON:-}" = 1 ]; then
  case "${DOCKER_CONTEXT:-}" in
    chengyin-aws) STARTUP_DOCKER_CONTEXT=chengyin-aws; EXPECTED_DOCKER_ENDPOINT=ssh://chengyin-test ;;
    chengyin-vps-sandbox) STARTUP_DOCKER_CONTEXT=chengyin-vps-sandbox; EXPECTED_DOCKER_ENDPOINT=unix:///Users/arlan/.claude/remote-docker/chengyin-test.sock ;;
    *) echo 'Explicit approved VPS Docker context required' >&2; exit 1 ;;
  esac
else
  echo 'GitHub-hosted Linux runner or explicit approved VPS test run required' >&2; exit 1
fi
# Bind-mounted source, JAR and trace libraries must already exist on the verified daemon host.
docker() { command docker --context "$STARTUP_DOCKER_CONTEXT" "$@"; }
command -v docker >/dev/null
test "$(docker context inspect "$STARTUP_DOCKER_CONTEXT" --format '{{.Endpoints.docker.Host}}')" = "$EXPECTED_DOCKER_ENDPOINT" || {
  echo 'Docker endpoint differs from the approved test target' >&2; exit 1;
}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
FIXTURE="$ROOT/ci/upgrade-startup"
JAR="$ROOT/chengyinhub-admin/target/chengyinhub-admin.jar"
test -f "$JAR"
command -v docker >/dev/null
command -v strace >/dev/null
command -v python3 >/dev/null
docker info >/dev/null
# Pool fault scenario is opt-in for the exact isolated hosted workflow only.
POOL_PROFILE=()
POOL_LABEL=()
if [ "${CHENGYIN_CI_POOL_RECOVERY:-false}" = true ]; then
  python3 -I "$ROOT/ci/pool_recovery_probe.py" --check-execution-only
  test "$(command docker context inspect --format '{{.Endpoints.docker.Host}}')" = unix:///var/run/docker.sock
  POOL_PROFILE=(--spring.datasource.druid.maxActive=20 --spring.datasource.druid.maxWait=5000 --spring.datasource.druid.socketTimeout=60000)
fi
RUN_NAME="cy-startup-$$-$RANDOM"
if [ "${CHENGYIN_CI_POOL_RECOVERY:-false}" = true ]; then
  POOL_LABEL=(--label "com.chengyin.pool-recovery=$RUN_NAME")
fi
OUT=$(mktemp -d "${TMPDIR:-/tmp}/cy-startup.XXXXXXXX")
chmod 755 "$OUT"
STRACE=$(command -v strace)
# Keep the tracer's loader/libc closure separate from the application's image libraries.
if [ "${CHENGYIN_CI_POOL_RECOVERY:-false}" != true ]; then prepare_trace_runtime; fi
cleanup() {
  local result=$?
  trap - EXIT
  docker logs "$RUN_NAME-app" > "$OUT/application.log" 2>&1 || true
  if [ "${CHENGYIN_CI_POOL_RECOVERY:-false}" = true ]; then
    # Continue every owned cleanup attempt while preserving the original failure code.
    set +e
    local evidence_result=0 cleanup_result=0
    pool_execution_evidence after || evidence_result=$?
    pool_owned_cleanup "$result" || cleanup_result=$?
    if [ "$result" -eq 0 ] && { [ "$evidence_result" -ne 0 ] || [ "$cleanup_result" -ne 0 ]; }; then result=1; fi
  else
    docker rm -f "$RUN_NAME-app" "$RUN_NAME-mysql" "$RUN_NAME-redis" "$RUN_NAME-trace-check" >/dev/null 2>&1 || true
    docker network rm "$RUN_NAME" >/dev/null 2>&1 || true
  fi
  echo "Startup evidence: $OUT (exit=$result)"
  if [ "$result" -ne 0 ]; then
    if [ -f "$OUT/health.log" ]; then cat "$OUT/health.log"; fi
    tail -100 "$OUT/application.log"
  fi
  exit "$result"
}
trap cleanup EXIT
if [ "${CHENGYIN_CI_POOL_RECOVERY:-false}" = true ]; then
  pool_execution_evidence before
  prepare_trace_runtime
fi
JDK_IMAGE=maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237
MYSQL_IMAGE=mysql:8.0.45@sha256:4af1f8815716546f5b12410f7621f37f93db8dd11a184706ef59111930b8c2ff
REDIS_IMAGE=redis:7.4-alpine@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499
for image in "$JDK_IMAGE" "$MYSQL_IMAGE" "$REDIS_IMAGE"; do docker image inspect "$image" >/dev/null 2>&1 || docker pull "$image"; done
check_trace_runtime
docker network create --internal "${POOL_LABEL[@]}" "$RUN_NAME" >/dev/null
test "$(docker network inspect "$RUN_NAME" --format '{{.Internal}}')" = true
docker run -d --name "$RUN_NAME-mysql" "${POOL_LABEL[@]}" --network "$RUN_NAME" --tmpfs /var/lib/mysql:rw,size=768m \
  -e MYSQL_ALLOW_EMPTY_PASSWORD=yes -e MYSQL_DATABASE=upgrade_startup_probe \
  -e MYSQL_USER=startup_probe -e MYSQL_PASSWORD=synthetic-local-only \
  "$MYSQL_IMAGE" --skip-log-bin --default-time-zone=+00:00 >/dev/null
docker run -d --name "$RUN_NAME-redis" "${POOL_LABEL[@]}" --network "$RUN_NAME" --tmpfs /data:rw,size=64m \
  "$REDIS_IMAGE" redis-server --save '' --appendonly no >/dev/null
ready=false
for attempt in $(seq 1 90); do
  if docker exec -e MYSQL_PWD=synthetic-local-only "$RUN_NAME-mysql" mysql -h127.0.0.1 -ustartup_probe \
    -Dupgrade_startup_probe -N -e 'SELECT VERSION()' > "$OUT/mysql-version" 2>/dev/null; then ready=true; break; fi
  sleep 1
done
test "$ready" = true
grep -qx '8.0.45' "$OUT/mysql-version"
test "$(docker exec "$RUN_NAME-mysql" mysql -uroot -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='upgrade_startup_probe'")" = 0
test "$(docker exec "$RUN_NAME-redis" redis-cli DBSIZE)" = 0
docker exec -i "$RUN_NAME-mysql" mysql -uroot upgrade_startup_probe < "$FIXTURE/schema.sql"
MYSQL_IP=$(docker inspect "$RUN_NAME-mysql" --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')
REDIS_IP=$(docker inspect "$RUN_NAME-redis" --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')
start_traced_application
if [ "${CHENGYIN_CI_POOL_RECOVERY:-false}" = true ]; then pool_execution_evidence running; fi
ready=false
for attempt in $(seq 1 120); do
  docker logs "$RUN_NAME-app" > "$OUT/application.log" 2>&1
  if grep -q '城瘾API启动成功' "$OUT/application.log"; then ready=true; break; fi
  test "$(docker inspect "$RUN_NAME-app" --format '{{.State.Running}}')" = true || break
  sleep 1
done
test "$ready" = true
# Runner UID need not have an /etc/passwd entry in the image; jcmd -l then misses it.
APP_PID=$(docker exec "$RUN_NAME-app" sh -c '
  for entry in /proc/[0-9]*/cmdline; do
    case "$(readlink "${entry%/cmdline}/exe" 2>/dev/null)" in */java) ;; *) continue ;; esac
    if tr "\000" "\n" < "$entry" 2>/dev/null | grep -qxF "/application.jar"; then
      pid=${entry#/proc/}
      printf "%s\n" "${pid%/cmdline}"
    fi
  done')
case "$APP_PID" in
  ''|*[!0-9]*) echo "Expected exactly one application Java PID, got: $APP_PID" >&2; exit 1 ;;
esac
docker exec "$RUN_NAME-app" java /fixture/HealthProbe.java "$APP_PID" > "$OUT/health.log"
cat "$OUT/health.log"
if [ "${CHENGYIN_CI_POOL_RECOVERY:-false}" = true ]; then
  python3 -I "$ROOT/ci/pool_recovery_probe.py" "$RUN_NAME" "$OUT"
  # Recovery uses the same existing in-container health probe after the lock is gone.
  docker exec "$RUN_NAME-app" java /fixture/HealthProbe.java "$APP_PID" > "$OUT/pool-recovery-health.log"
  cat "$OUT/pool-recovery-health.log"
fi
test "$(docker exec "$RUN_NAME-app" curl --noproxy '*' -s -o /tmp/auth-response -w '%{http_code}' http://127.0.0.1:8080/api/city/nodes)" = 401
test "$(docker exec "$RUN_NAME-app" curl --noproxy '*' -s -X POST -o /tmp/auth-response -w '%{http_code}' http://127.0.0.1:8080/api/official/events)" = 401
docker exec "$RUN_NAME-redis" redis-cli --raw GET sys_config:startup.synthetic.probe > "$OUT/config-cache"
grep -q 'cache-roundtrip' "$OUT/config-cache"
test "$(docker exec "$RUN_NAME-redis" redis-cli EXISTS sys_dict:startup_synthetic)" = 1
# Observe beyond the previous heartbeat/export first-run delays; no status publisher or business schema exists.
sleep 6
docker exec "$RUN_NAME-app" test ! -e /tmp/startup-status.json
docker logs "$RUN_NAME-app" > "$OUT/application.log" 2>&1
if grep -q 'Scheduler .* started\.' "$OUT/application.log"; then
  echo 'Quartz started while jobs were disabled' >&2
  exit 1
fi
python3 -I "$ROOT/ci/upgrade_startup_network.py" "$OUT/network.trace" "$MYSQL_IP" "$REDIS_IP"

docker inspect "$RUN_NAME-app" "$RUN_NAME-mysql" "$RUN_NAME-redis" \
  --format '{{.Name}} ports={{json .HostConfig.PortBindings}} networks={{range $key, $value := .NetworkSettings.Networks}}{{$key}} {{end}}' > "$OUT/isolation.log"
cat "$OUT/isolation.log"
echo 'PASS real packaged application ready, isolated startup health UP (disabled payment DOWN), MySQL/Redis cache roundtrip, protected HTTP 401, jobs disabled'
