"""Exercise agent staging with synthetic static executables."""
import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.dont_write_bytecode = True


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(f"{name}.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


agent = load("package_agent")
fixtures = load("test_fetch_official")


class AgentStaging(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory(prefix="agent-staging-")
        self.root = Path(self.scratch.name)
        self.release = self.root / "target/aarch64-unknown-linux-musl/release"
        self.release.mkdir(parents=True)
        (self.release / "pocket-agent").write_bytes(fixtures.elf() + b"agent")
        (self.release / "pocket-runtime-host").write_bytes(fixtures.elf() + b"host")

    def tearDown(self):
        self.scratch.cleanup()

    def stage(self, version="0.7.1"):
        return agent.stage(self.root / "target", self.root / "out", version, ["arm64-v8a"])

    def test_manifest_binds_the_packaged_names_to_the_staged_bytes(self):
        self.stage()
        directory = self.root / "out/jniLibs/arm64-v8a"
        manifest = json.loads((directory / "libpocket-runtime-manifest.so").read_bytes())
        self.assertEqual((manifest["schema"], manifest["product"], manifest["version"], manifest["architecture"]),
                         (1, "Codex Pocket", "0.7.1", "aarch64"))
        self.assertEqual([entry["path"] for entry in manifest["files"]],
                         ["libpocket-agent.so", "libpocket-runtime-host.so"])
        for entry in manifest["files"]:
            data = (directory / entry["path"]).read_bytes()
            self.assertEqual((len(data), hashlib.sha256(data).hexdigest()), (entry["bytes"], entry["sha256"]))

    def test_staged_files_are_never_replaced(self):
        self.stage()
        staged = self.root / "out/jniLibs/arm64-v8a/libpocket-agent.so"
        (self.release / "pocket-agent").write_bytes(fixtures.elf() + b"newer")
        with self.assertRaisesRegex(ValueError, "Already staged"):
            self.stage()
        self.assertTrue(staged.read_bytes().endswith(b"agent"))

    def test_unusable_input_stages_nothing(self):
        for mutate, text in ((lambda: (self.release / "pocket-runtime-host").write_bytes(fixtures.elf(interpreter=True)),
                              "dynamic loader"),
                             (lambda: (self.release / "pocket-runtime-host").unlink(), "")):
            mutate()
            with self.assertRaisesRegex((ValueError, OSError), text):
                self.stage()
            self.assertFalse((self.root / "out").exists())
        with self.assertRaisesRegex(ValueError, "Version"):
            self.stage(version="../x")


if __name__ == "__main__":
    unittest.main()
