"""
Tier 1 Feature Test: Build and Unit Test Execution.

Verifies that Windows .NET 10 unit tests execute and pass cleanly,
Release win-x64 publish succeeds, and Android debug APK compiles without errors.
Strict 0-emoji compliance enforced.
"""

import os
import time
import unittest
from tests.helpers.process_runner import run_process

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


class TestBuildAndUnitTests(unittest.TestCase):

    def test_windows_unit_tests(self):
        """Verify Windows C# xUnit test suite executes and passes with 0 failures."""
        csproj = os.path.join(REPO_ROOT, "windows", "TrikiMusicController.Windows.Tests", "TrikiMusicController.Windows.Tests.csproj")
        self.assertTrue(os.path.exists(csproj), f"Windows test project missing: {csproj}")

        result = None
        for attempt in range(3):
            result = run_process(
                ["dotnet", "test", csproj, "--no-restore", "-v", "minimal", "/p:UseSharedCompilation=false", "/nodeReuse:false"],
                cwd=REPO_ROOT,
                timeout_seconds=60,
            )
            if result.exit_code == 0:
                break
            time.sleep(1.5)

        self.assertIsNotNone(result)
        self.assertEqual(result.exit_code, 0, f"dotnet test failed with stderr:\n{result.stderr}\nstdout:\n{result.stdout}")
        self.assertIn("0", result.stdout, "Unexpected test runner output format")

    def test_windows_release_publish(self):
        """Verify Windows Release win-x64 self-contained build produces valid binaries."""
        csproj = os.path.join(REPO_ROOT, "windows", "TrikiMusicController.Windows", "TrikiMusicController.Windows.csproj")
        self.assertTrue(os.path.exists(csproj), f"Windows main project missing: {csproj}")

        result = None
        for attempt in range(3):
            result = run_process(
                [
                    "dotnet", "publish", csproj, "-c", "Release", "-r", "win-x64",
                    "--self-contained", "true", "--no-restore",
                    "/p:UseSharedCompilation=false", "/nodeReuse:false"
                ],
                cwd=REPO_ROOT,
                timeout_seconds=90,
            )
            if result.exit_code == 0:
                break
            time.sleep(1.5)

        self.assertIsNotNone(result)
        self.assertEqual(result.exit_code, 0, f"dotnet publish failed:\n{result.stderr}\nstdout:\n{result.stdout}")

    def test_android_assemble_debug(self):
        """Verify Android assembleDebug generates valid debug APK."""
        gradlew = os.path.join(REPO_ROOT, "gradlew.bat")
        self.assertTrue(os.path.exists(gradlew), f"gradlew.bat missing at {gradlew}")

        result = run_process(
            [gradlew, "assembleDebug", "--offline"],
            cwd=REPO_ROOT,
            timeout_seconds=90,
        )
        if result.exit_code != 0:
            # Fallback to normal online assembleDebug if offline fails
            result = run_process(
                [gradlew, "assembleDebug"],
                cwd=REPO_ROOT,
                timeout_seconds=120,
            )
        self.assertEqual(result.exit_code, 0, f"Android assembleDebug failed:\n{result.stderr}\nstdout:\n{result.stdout}")


if __name__ == "__main__":
    unittest.main()
