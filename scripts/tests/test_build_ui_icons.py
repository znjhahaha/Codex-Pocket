"""Regression for scoped trace generation in a shared working tree (no Android build)."""
from pathlib import Path
import json
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
UI = Path("android/app/src/main/java/dev/codexpocket/app/ui")


class TraceIconsTest(unittest.TestCase):
    def test_trace_generation_preserves_other_glyphs_and_call_sites(self):
        with tempfile.TemporaryDirectory(prefix="pocket-icons-") as directory:
            root = Path(directory)
            (root / "scripts").mkdir()
            (root / UI).mkdir(parents=True)
            (root / "design/icons").mkdir(parents=True)
            (root / "desktop/src").mkdir(parents=True)
            shutil.copyfile(ROOT / "scripts/build-ui-icons.py", root / "scripts/build-ui-icons.py")
            original = (ROOT / UI / "PocketIcons.kt").read_text(encoding="utf-8")
            # Simulate a concurrent worker's unrelated glyph change.
            original = original.replace('icon("Add",', 'icon("MyUnrelatedAdd",')
            (root / UI / "PocketIcons.kt").write_text(original, encoding="utf-8")
            (root / UI / "Transcript.kt").write_text("// Other worker's edits\n", encoding="utf-8")
            (root / "desktop/src/PocketIcons.tsx").write_text("// Desktop sentinel\n", encoding="utf-8")
            (root / "design/icons/paths.json").write_text('{"Unrelated": ["M1 1L2 2"]}', encoding="utf-8")
            command = [sys.executable, str(root / "scripts/build-ui-icons.py"), "--android-only", "--trace-only"]
            subprocess.run(command, check=True, capture_output=True)
            first = (root / UI / "PocketIcons.kt").read_text(encoding="utf-8")
            self.assertIn('icon("MyUnrelatedAdd",', first)
            self.assertEqual("// Other worker's edits\n", (root / UI / "Transcript.kt").read_text())
            self.assertEqual("// Desktop sentinel\n", (root / "desktop/src/PocketIcons.tsx").read_text())
            paths = json.loads((root / "design/icons/paths.json").read_text())
            self.assertEqual(["M1 1L2 2"], paths["Unrelated"])
            for glyph in ("Edit", "Terminal", "RobotOutline", "Wrench", "Image", "Cube"):
                self.assertIn(glyph, paths)
                self.assertIn(f'val {glyph}: ImageVector', first)
            self.assertEqual(1, first.count("val Cube:"))
            subprocess.run(command, check=True, capture_output=True)
            self.assertEqual(first, (root / UI / "PocketIcons.kt").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
