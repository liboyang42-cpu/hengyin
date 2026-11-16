#!/usr/bin/env python3
"""Chain a design token check into each existing pre-commit hook without replacing its checks."""
import argparse
from pathlib import Path
import shlex
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BEGIN = "# BEGIN chengyin-native-design\n"
END = "# END chengyin-native-design\n"


def hook_text(existing, command):
    if existing and not existing.startswith(("#!/bin/bash", "#!/usr/bin/env bash", "#!/bin/sh", "#!/usr/bin/env sh")):
        raise ValueError("Existing pre-commit is not a shell hook; refusing to replace it")
    if BEGIN in existing:
        if existing.count(BEGIN) != 1 or existing.count(END) != 1:
            raise ValueError("Ambiguous existing native design hook markers")
        start = existing.index(BEGIN)
        stop = existing.index(END, start) + len(END)
        existing = existing[:start] + existing[stop:]
    if not existing:
        existing = "#!/usr/bin/env bash\n"
    first, _, rest = existing.partition("\n")
    return first + "\n" + BEGIN + command + " || exit $?\n" + END + rest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ios-root", type=Path, required=True)
    parser.add_argument("--write", action="store_true", help="Otherwise only describe the exact hook paths")
    args = parser.parse_args()
    ios = args.ios_root.resolve()
    if not (ios / "Questify.xcodeproj").is_dir():
        raise ValueError("Not the existing Questify checkout")
    plans = []
    for side, repo in (("mini", ROOT), ("ios", ios)):
        hook = Path(subprocess.check_output(["git", "-C", str(repo), "rev-parse", "--path-format=absolute", "--git-path", "hooks/pre-commit"], text=True).strip())
        if hook.is_symlink():
            raise ValueError("Refusing to replace a symlink hook: " + str(hook))
        existing = hook.read_text() if hook.exists() else ""
        check = shlex.join(["python3", str(ROOT / "scripts/native-design.py"), "check", "--ios-root", str(ios), "--staged-side", side])
        command = 'if [ "$(git rev-parse --show-toplevel)" = ' + shlex.quote(str(repo)) + ' ]; then\n  ' + check + '\nfi'
        plans.append((hook, existing, hook_text(existing, command)))
    for hook, before, after in plans:
        print(("Install: " if args.write else "Would install: ") + str(hook))
        if args.write:
            if (hook.read_text() if hook.exists() else "") != before:
                raise ValueError("Hook changed during installation: " + str(hook))
            hook.parent.mkdir(parents=True, exist_ok=True)
            hook.write_text(after)
            hook.chmod(hook.stat().st_mode | 0o100)


if __name__ == "__main__":
    main()
