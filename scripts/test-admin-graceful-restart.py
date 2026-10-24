#!/usr/bin/env python3
"""Linux task-runner self-check; no Java server or external process is used."""
import os
import shutil
import signal
import sys
import subprocess
import tempfile
import time
from pathlib import Path


def wait_for(predicate):
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.05)
    raise AssertionError("owned restart fixture did not reach expected state")


def main():
    if sys.platform != "linux" or not Path("/proc/self/cmdline").exists():
        raise ValueError("Linux runner required; Mac process fixture execution is forbidden")
    source = Path(__file__).resolve().parents[1] / "run-chengyinhub-admin.sh"
    with tempfile.TemporaryDirectory(prefix="cy-admin-restart-") as temporary:
        root = Path(temporary)
        own = root / "own"
        other = root / "other"
        tools = root / "bin"
        for directory in (own, other, tools):
            directory.mkdir()
        shutil.copyfile(source, own / source.name)
        missing = subprocess.run(["bash", str(own / source.name)], capture_output=True)
        assert missing.returncode != 0 and b"jar is missing" in missing.stderr
        for directory in (own, other):
            (directory / "chengyinhub-admin.jar").touch()
        java = tools / "java"
        java.write_text("""#!/usr/bin/env python3
import os, signal, time
from pathlib import Path
Path('started-' + str(os.getpid())).touch()
def stop(*args):
    Path('stopped-' + str(os.getpid())).touch()
    raise SystemExit(0)
signal.signal(signal.SIGTERM, stop)
while True: time.sleep(0.1)
""")
        java.chmod(0o700)
        environment = dict(os.environ, PATH=str(tools) + os.pathsep + os.environ["PATH"])
        old = subprocess.Popen([str(java), "-jar", "chengyinhub-admin.jar"], cwd=own, start_new_session=True)
        unrelated = subprocess.Popen([str(java), "-jar", "chengyinhub-admin.jar"], cwd=other, start_new_session=True)
        restart = None
        try:
            wait_for(lambda: (own / f"started-{old.pid}").exists() and (other / f"started-{unrelated.pid}").exists())
            restart = subprocess.Popen(["bash", str(own / source.name)], cwd=own, env=environment,
                                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, start_new_session=True)
            wait_for(lambda: (own / f"stopped-{old.pid}").exists())
            assert old.wait(timeout=3) == 0, "TERM must allow normal cleanup"
            wait_for(lambda: len(list(own.glob("started-*"))) == 2)
            assert unrelated.poll() is None, "same filename in another checkout must survive"
            assert not (other / f"stopped-{unrelated.pid}").exists()
            assert restart.poll() is None, "log follower must remain available"
        finally:
            for process in (restart, old, unrelated):
                if process is not None and process.poll() is None:
                    os.killpg(process.pid, signal.SIGTERM)
                    process.wait(timeout=5)
        print("PASS: missing jar refused; exact checkout TERM cleanup; unrelated checkout preserved; one replacement")


if __name__ == "__main__":
    main()
