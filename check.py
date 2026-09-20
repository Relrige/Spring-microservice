#!/usr/bin/env python3
"""Quality gate script for VoltStore microservices.

Runs test-compile (or tests) across all microservices using each project's
embedded Maven Wrapper (mvnw / mvnw.cmd).
"""

import argparse
import os
import subprocess
import sys
from pathlib import Path

SERVICES = [
    "catalog-service",
    "delivery-service",
    "inventory-service",
    "order-service",
    "payment-service",
]


def run_gate(services, goal="test-compile", quiet=False):
    root_dir = Path(__file__).resolve().parent
    is_windows = sys.platform == "win32"
    wrapper_name = "mvnw.cmd" if is_windows else "mvnw"

    print(f"Running quality gate goal '{goal}' across {len(services)} service(s)...")
    failures = []

    for svc in services:
        svc_dir = root_dir / svc
        wrapper_path = svc_dir / wrapper_name

        if not wrapper_path.exists():
            print(f"[{svc}] ERROR: wrapper not found at {wrapper_path}")
            failures.append(svc)
            continue

        cmd = [str(wrapper_path), goal]
        if quiet:
            cmd.append("-q")

        print(f"\n[{svc}] Running: {' '.join(cmd)}")
        try:
            res = subprocess.run(cmd, cwd=svc_dir)
            if res.returncode != 0:
                print(f"[{svc}] FAILED with exit code {res.returncode}")
                failures.append(svc)
            else:
                print(f"[{svc}] PASSED")
        except Exception as exc:
            print(f"[{svc}] Execution error: {exc}")
            failures.append(svc)

    print("\n" + "=" * 40)
    if failures:
        print(f"Quality gate FAILED. Failed services ({len(failures)}): {', '.join(failures)}")
        return 1
    else:
        print(f"Quality gate PASSED. All {len(services)} services compiled successfully.")
        return 0


def main():
    parser = argparse.ArgumentParser(description="VoltStore quality gate runner")
    parser.add_argument(
        "--service",
        choices=SERVICES,
        help="Run gate for a single service only",
    )
    parser.add_argument(
        "--test",
        action="store_true",
        help="Run full test suite (requires active Docker daemon for Testcontainers)",
    )
    parser.add_argument(
        "-q", "--quiet",
        action="store_true",
        help="Run Maven with -q (quiet mode)",
    )
    args = parser.parse_args()

    services = [args.service] if args.service else SERVICES
    goal = "test" if args.test else "test-compile"
    sys.exit(run_gate(services, goal=goal, quiet=args.quiet))


if __name__ == "__main__":
    main()
