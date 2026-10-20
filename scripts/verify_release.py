"""Validate the versions consumed by both applications and the Windows installer."""
from pathlib import Path
import os
import re
import xml.etree.ElementTree as ET


def main() -> None:
    root = Path(__file__).resolve().parent.parent
    project = ET.parse(root / "windows/TrikiMusicController.Windows/TrikiMusicController.Windows.csproj")
    version = project.findtext(".//Version")
    if version is None or re.fullmatch(r"\d+\.\d+\.\d+", version) is None:
        raise SystemExit("Invalid Windows semantic version")
    for field in ("AssemblyVersion", "FileVersion"):
        if project.findtext(f".//{field}") != version + ".0":
            raise SystemExit(f"{field} differs from {version}.0")
    android = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
    if re.search(r'versionName\s*=\s*"' + re.escape(version) + '"', android) is None:
        raise SystemExit("Android versionName differs from Windows")
    installer = (root / "windows/installer/TrikiMusicController.iss").read_text(encoding="utf-8")
    if f'#define AppVersion "{version}"' not in installer:
        raise SystemExit("Windows installer version differs from the application")
    ref = os.environ.get("GITHUB_REF", "")
    if ref.startswith("refs/tags/") and ref != "refs/tags/v" + version:
        raise SystemExit("Release tag differs from the version being built")
    if not (root / f"docs/releases/Release-v{version}.md").is_file():
        raise SystemExit("Release notes are missing")
    if output := os.environ.get("GITHUB_OUTPUT"):
        with open(output, "a", encoding="utf-8") as stream:
            stream.write(f"version={version}\n")
    print(version)


if __name__ == "__main__":
    main()
