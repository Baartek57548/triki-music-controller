"""
Subprocess execution runner for build tools and unit test suites.

Enforces strict 0-emoji compliance and clean output handling.
"""

from __future__ import annotations
import os
import subprocess
import sys
from dataclasses import dataclass
from typing import List, Optional


@dataclass(frozen=True)
class ProcessResult:
    command: List[str]
    exit_code: int
    stdout: str
    stderr: str
    duration_seconds: float

    @property
    def is_success(self) -> bool:
        return self.exit_code == 0


def run_process(
    cmd: List[str],
    cwd: Optional[str] = None,
    timeout_seconds: int = 120,
) -> ProcessResult:
    import time
    start_time = time.time()
    try:
        proc = subprocess.run(
            cmd,
            cwd=cwd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=timeout_seconds,
            encoding="utf-8",
            errors="replace",
        )
        duration = time.time() - start_time
        return ProcessResult(
            command=cmd,
            exit_code=proc.returncode,
            stdout=proc.stdout or "",
            stderr=proc.stderr or "",
            duration_seconds=duration,
        )
    except subprocess.TimeoutExpired as err:
        duration = time.time() - start_time
        return ProcessResult(
            command=cmd,
            exit_code=-1,
            stdout=err.stdout or "",
            stderr=f"Process timed out after {timeout_seconds} seconds",
            duration_seconds=duration,
        )
    except Exception as err:
        duration = time.time() - start_time
        return ProcessResult(
            command=cmd,
            exit_code=-2,
            stdout="",
            stderr=str(err),
            duration_seconds=duration,
        )
