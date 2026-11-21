"""Check release-lock preparation against the actual pinned source snapshot."""
import subprocess
import sys
import tempfile
import tomllib
import unittest
from pathlib import Path


class ReleaseLockPreparation(unittest.TestCase):
    def test_only_local_versions_change_and_second_run_is_idempotent(self):
        checkout = Path("E:/pocket-build/codex-0.171.0")
        expected = "ccf9be8cc458bd6b8336fe84d68d8893f2691a5e"
        self.assertEqual(subprocess.check_output(["git", "-C", str(checkout), "rev-parse", "HEAD"], text=True).strip(), expected)
        script = Path(__file__).with_name("prepare_lock.py")
        with tempfile.TemporaryDirectory(prefix="native-lock-test-", dir="E:/pocket-build") as directory:
            root = Path(directory)
            for name in ["Cargo.toml", "Cargo.lock"]:
                original = subprocess.check_output(["git", "-C", str(checkout), "show", f"HEAD:codex-rs/{name}"])
                (root / name).write_bytes(original)
            before = tomllib.loads((root / "Cargo.lock").read_text())
            subprocess.run([sys.executable, str(script), str(root)], check=True, capture_output=True)
            after = tomllib.loads((root / "Cargo.lock").read_text())
            changes = 0
            self.assertEqual(len(before["package"]), len(after["package"]))
            for old, new in zip(before["package"], after["package"]):
                if old == new:
                    continue
                self.assertNotIn("source", old)
                self.assertEqual(old["version"], "0.0.0")
                self.assertEqual(new, dict(old, version="0.171.0"))
                changes += 1
            self.assertEqual(changes, 174)
            saved = (root / "Cargo.lock").read_bytes()
            subprocess.run([sys.executable, str(script), str(root)], check=True, capture_output=True)
            self.assertEqual((root / "Cargo.lock").read_bytes(), saved)


if __name__ == "__main__":
    unittest.main()
