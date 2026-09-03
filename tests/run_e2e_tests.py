"""
Triki Music Controller — Automated E2E Master Test Runner.

Executes all 4 tiers of comprehensive end-to-end and integration tests:
  Tier 1: Feature Coverage & Subsystem Tests
  Tier 2: Boundary & Corner Case Tests
  Tier 3: Cross-Feature Combination & Concurrency Tests
  Tier 4: Real-World Workloads & Doc Graph Integrity

Strict 0-emoji compliance enforced.
"""

from __future__ import annotations
import argparse
import os
import sys
import time
import unittest
from typing import Dict, List, Tuple

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

TIER_MODULES: Dict[int, List[str]] = {
    1: [
        "tests.tier1_features.test_build_and_unit_tests",
        "tests.tier1_features.test_ble_protocol_decoding",
        "tests.tier1_features.test_lsm6ds_sensor_scaling",
        "tests.tier1_features.test_cross_platform_parity",
        "tests.tier1_features.test_zero_emoji_compliance",
    ],
    2: [
        "tests.tier2_boundaries.test_imu_sensor_extremes",
        "tests.tier2_boundaries.test_floating_point_safety",
        "tests.tier2_boundaries.test_malformed_ble_frames",
        "tests.tier2_boundaries.test_rapid_state_transitions",
        "tests.tier2_boundaries.test_connection_loss_and_gaps",
    ],
    3: [
        "tests.tier3_combinations.test_gesture_arbitration_concurrency",
        "tests.tier3_combinations.test_arbitration_and_wake_gate",
    ],
    4: [
        "tests.tier4_workloads.test_e2e_playback_workflow",
        "tests.tier4_workloads.test_doc_graph_integrity",
    ],
    5: [
        "tests.tier5_adversarial.test_adversarial_imu_floats",
        "tests.tier5_adversarial.test_adversarial_state_oscillations",
        "tests.tier5_adversarial.test_adversarial_protocol_fuzzing",
    ],
}

TIER_TITLES = {
    1: "Tier 1: Feature Coverage & Subsystems",
    2: "Tier 2: Boundary & Corner Cases",
    3: "Tier 3: Cross-Feature Combinations & Concurrency",
    4: "Tier 4: Real-World Workloads & Knowledge Graph",
    5: "Tier 5: Adversarial Coverage Hardening & Stress",
}


class TierTestResult(unittest.TextTestResult):
    def __init__(self, stream, descriptions, verbosity):
        super().__init__(stream, descriptions, verbosity)
        self.test_records: List[Tuple[str, str, float, str]] = []
        self._test_start_time = 0.0

    def startTest(self, test):
        super().startTest(test)
        self._test_start_time = time.time()

    def addSuccess(self, test):
        super().addSuccess(test)
        elapsed = time.time() - self._test_start_time
        self.test_records.append((test.id(), "PASS", elapsed, ""))

    def addFailure(self, test, err):
        super().addFailure(test, err)
        elapsed = time.time() - self._test_start_time
        self.test_records.append((test.id(), "FAIL", elapsed, self._exc_info_to_string(err, test)))

    def addError(self, test, err):
        super().addError(test, err)
        elapsed = time.time() - self._test_start_time
        self.test_records.append((test.id(), "ERROR", elapsed, self._exc_info_to_string(err, test)))

    def addSkip(self, test, reason):
        super().addSkip(test, reason)
        elapsed = time.time() - self._test_start_time
        self.test_records.append((test.id(), "SKIP", elapsed, reason))


def run_tier(tier_num: int, verbose: bool = False) -> Tuple[int, int, int, float, List[Tuple[str, str, float, str]]]:
    suite = unittest.TestSuite()
    loader = unittest.TestLoader()

    for module_name in TIER_MODULES.get(tier_num, []):
        try:
            mod_suite = loader.loadTestsFromName(module_name)
            suite.addTests(mod_suite)
        except Exception as e:
            print(f"ERROR: Failed to load tests from {module_name}: {e}")

    devnull = open(os.devnull, "w")
    runner = unittest.TextTestRunner(stream=devnull, resultclass=TierTestResult, verbosity=0)
    start_time = time.time()
    result: TierTestResult = runner.run(suite)
    devnull.close()
    duration = time.time() - start_time

    passed = len(result.test_records) - len(result.failures) - len(result.errors) - len(result.skipped)
    failed = len(result.failures) + len(result.errors)
    skipped = len(result.skipped)

    return passed, failed, skipped, duration, result.test_records


def main() -> int:
    parser = argparse.ArgumentParser(description="Triki Music Controller E2E Test Runner")
    parser.add_argument("--tier", type=int, choices=[1, 2, 3, 4, 5], help="Run a specific test tier only")
    parser.add_argument("-v", "--verbose", action="store_true", help="Display verbose test output and tracebacks")
    args = parser.parse_args()

    tiers_to_run = [args.tier] if args.tier else [1, 2, 3, 4, 5]

    print("================================================================================")
    print("           TRIKI MUSIC CONTROLLER -- E2E TEST SUITE RUNNER                      ")
    print("================================================================================")
    print(f"Repository Root: {REPO_ROOT}")
    print(f"Tiers Selected : {', '.join(str(t) for t in tiers_to_run)}")
    print(f"Zero-Emoji Rule: Active (ADR-001)")
    print("--------------------------------------------------------------------------------\n")

    overall_start = time.time()
    total_passed = 0
    total_failed = 0
    total_skipped = 0
    all_failures: List[Tuple[str, str]] = []

    for tier_num in tiers_to_run:
        title = TIER_TITLES.get(tier_num, f"Tier {tier_num}")
        print(f"--- {title} ---")
        passed, failed, skipped, duration, records = run_tier(tier_num, verbose=args.verbose)

        total_passed += passed
        total_failed += failed
        total_skipped += skipped

        for test_id, status, elapsed, err in records:
            short_name = test_id.split(".")[-1]
            mod_name = test_id.split(".")[-2]
            print(f"  [{status:4s}] {mod_name}.{short_name} ({elapsed*1000:.1f}ms)")
            if status in ("FAIL", "ERROR"):
                all_failures.append((test_id, err))

        print(f"Summary: {passed} passed, {failed} failed, {skipped} skipped (took {duration:.2f}s)\n")

    overall_duration = time.time() - overall_start

    print("================================================================================")
    print("                           E2E TEST RUN SUMMARY                                 ")
    print("================================================================================")
    print(f"Total Test Cases : {total_passed + total_failed + total_skipped}")
    print(f"Passed           : {total_passed}")
    print(f"Failed / Errors  : {total_failed}")
    print(f"Skipped          : {total_skipped}")
    print(f"Total Duration   : {overall_duration:.2f}s")
    print("--------------------------------------------------------------------------------")

    if total_failed == 0:
        print("RESULT: ALL E2E TESTS PASSED SUCCESSFULLY [100% PASS RATE]")
        print("================================================================================")
        return 0
    else:
        print(f"RESULT: {total_failed} TEST(S) FAILED")
        print("--------------------------------------------------------------------------------")
        for test_id, err in all_failures:
            print(f"\nFAILED: {test_id}")
            print(err)
        print("================================================================================")
        return 1


if __name__ == "__main__":
    sys.exit(main())
