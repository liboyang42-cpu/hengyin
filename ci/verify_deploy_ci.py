#!/usr/bin/env python3
"""Fail closed before a manual deployment unless the exact SHA has complete CI evidence."""
import json
import re
import subprocess
import sys


def api(path):
    return json.loads(subprocess.check_output(["gh", "api", path], text=True))


def verify(sha, repository):
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Manual deployment requires an explicit full commit SHA")
    requirements = {
        "ci.yml": {
            "Backend tests": "Build and test backend",
            "Mini program static checks": "Run mini program unit contracts",
            "Secret scan (gitleaks)": None,
            "SBOM + dependency scan": None,
            "Migration layout gate": None,
            "Migration safety gate": None,
            "Admin UI E2E contract gate": None,
            "Admin UI build gate": None,
            "Fast gates (CWD / API existence / silentError)": None,
        },
        "funds-mysql-it.yml": {
            "Funds IT gate self-test (skipped=0 negative controls)": None,
            "Funds MySQL IT (50/9/30, skipped=0, fail-closed)":
                "Run funds MySQL integration gate (fail-closed on missing MySQL)",
        },
    }
    for workflow, jobs_required in requirements.items():
        runs = api("repos/%s/actions/workflows/%s/runs?head_sha=%s&per_page=100" %
                   (repository, workflow, sha))["workflow_runs"]
        candidates = [run for run in runs if run["head_sha"] == sha
                      and run.get("head_repository", {}).get("full_name") == repository]
        if not candidates:
            raise ValueError(workflow + ": no CI run for this repository and exact SHA")
        latest = max(candidates, key=lambda run: (run["run_number"], run["run_attempt"]))
        if latest["status"] != "completed" or latest["conclusion"] != "success":
            raise ValueError(workflow + ": latest exact-SHA run is not successful")
        jobs = {job["name"]: job for job in api("repos/%s/actions/runs/%s/jobs?per_page=100" %
                                              (repository, latest["id"]))["jobs"]}
        for name, step in jobs_required.items():
            job = jobs.get(name, {})
            if job.get("conclusion") != "success":
                raise ValueError(workflow + ": required job missing, skipped or failed: " + name)
            if step and not any(item["name"] == step and item["conclusion"] == "success"
                                for item in job.get("steps", [])):
                raise ValueError(workflow + ": required test step did not run: " + step)
    print("Exact SHA has successful full Main and Funds CI; this check does not deploy")


if __name__ == "__main__":
    try:
        if len(sys.argv) != 3:
            raise ValueError("Usage: verify_deploy_ci.py SHA owner/repository")
        verify(sys.argv[1], sys.argv[2])
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
