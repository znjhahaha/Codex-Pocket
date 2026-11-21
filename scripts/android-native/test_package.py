"""Exercise the packaging gate with actual NDK-linked fixtures, never production substitutes."""
import importlib.util
import json
import os
import shutil
import struct
import sys
import subprocess
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("native_package", Path(__file__).with_name("package.py"))
package = importlib.util.module_from_spec(spec)
sys.dont_write_bytecode = True
spec.loader.exec_module(package)


def build_receipt(target):
    return {"codex_commit": package.INPUTS["codex"]["commit"], "target": target,
            "ndk_version": package.INPUTS["ndk_version"], "min_sdk": 28,
            "rust_toolchain": package.INPUTS["rust_toolchain"],
            "source_patches": package.INPUTS["source_patches"],
            "profile": "release", "component": "all", "status": "built"}


class PackagingGate(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scratch = tempfile.TemporaryDirectory(prefix="native-package-test-", dir="E:/pocket-build")
        cls.root = Path(cls.scratch.name)
        cls.source = cls.root / "fixture.c"
        cls.source.write_text("int main(void) { return 0; }\n")
        cls.clang = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
        cls.fixtures = {}
        for abi, target in package.INPUTS["targets"].items():
            binary = cls.root / abi
            subprocess.run([str(cls.clang), f"--target={target['clang']}28", "-fPIE", "-pie",
                            "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384",
                            str(cls.source), "-o", str(binary)], check=True, capture_output=True)
            cls.fixtures[abi] = binary

    @classmethod
    def tearDownClass(cls):
        cls.scratch.cleanup()

    def test_real_ndk_pie_for_both_abis(self):
        for abi, path in self.fixtures.items():
            info = package.inspect_elf(path, package.INPUTS["targets"][abi]["machine"], True)
            self.assertEqual(info["interpreter"], "/system/bin/linker64")
            self.assertGreaterEqual(info["load_alignment"], 16384)

    def test_wrong_abi_and_host_file_rejected(self):
        with self.assertRaisesRegex(ValueError, "machine"):
            package.inspect_elf(self.fixtures["arm64-v8a"], 62, True)
        with self.assertRaisesRegex(ValueError, "ELF64"):
            package.inspect_elf(self.clang, 62, True)

    def test_four_kib_binary_rejected(self):
        binary = self.root / "four-kib"
        subprocess.run([str(self.clang), "--target=x86_64-linux-android28", "-fPIE", "-pie",
                        "-Wl,-z,max-page-size=4096", "-Wl,-z,common-page-size=4096",
                        str(self.source), "-o", str(binary)], check=True, capture_output=True)
        with self.assertRaisesRegex(ValueError, "16-KiB"):
            package.inspect_elf(binary, 62, True)

    def test_incomplete_runtime_never_creates_output(self):
        target = self.root / "incomplete"
        receipts = target / "android-native-builds"
        receipts.mkdir(parents=True, exist_ok=True)
        abi, config = next(iter(package.INPUTS["targets"].items()))
        receipt = build_receipt(config["rust"])
        (receipts / f"{abi}-release.json").write_text(json.dumps(receipt))
        folder = target / config["rust"] / "release"
        folder.mkdir(parents=True)
        shutil.copy2(self.fixtures[abi], folder / "codex-app-server")
        output = self.root / "must-not-be-staged"
        with self.assertRaises(FileNotFoundError):
            package.stage(target, output, "release", None)
        self.assertFalse(output.exists())

    def test_stale_receipt_rejected(self):
        target = self.root / "stale"
        receipts = target / "android-native-builds"
        receipts.mkdir(parents=True)
        (receipts / "arm64-v8a-release.json").write_text(json.dumps({"codex_commit": "wrong"}))
        output = self.root / "must-not-stage-stale"
        with self.assertRaisesRegex(ValueError, "codex_commit"):
            package.stage(target, output, "release", None)
        self.assertFalse(output.exists())

    def test_complete_fixture_set_stages_with_exact_names_and_manifest(self):
        target = self.root / "complete-fixture-build"
        receipts = target / "android-native-builds"
        receipts.mkdir(parents=True)
        for abi, config in package.INPUTS["targets"].items():
            receipt = build_receipt(config["rust"])
            (receipts / f"{abi}-release.json").write_text(json.dumps(receipt))
            folder = target / config["rust"] / "release"
            folder.mkdir(parents=True)
            for name in package.INPUTS["binaries"]:
                shutil.copy2(self.fixtures[abi], folder / name)
        output = self.root / "fixture-stage-only"
        manifest = package.stage(target, output, "release", None)
        self.assertEqual(manifest["launch"]["arguments"], ["--listen", "stdio://"])
        for abi in package.INPUTS["targets"]:
            self.assertEqual(set(p.name for p in (output / "jniLibs" / abi).iterdir()),
                             set(package.INPUTS["binaries"].values()))
        with self.assertRaisesRegex(ValueError, "existing output"):
            package.stage(target, output, "release", None)

    def test_interrupted_partial_and_unpatched_builds_rejected(self):
        target = self.root / "invalid-receipts"
        receipts = target / "android-native-builds"
        receipts.mkdir(parents=True)
        for key, value in [("status", "building"), ("component", "app-server"),
                           ("source_patches", []), ("rust_toolchain", "stable")]:
            with self.subTest(key=key):
                receipt = build_receipt("aarch64-linux-android")
                receipt[key] = value
                (receipts / "arm64-v8a-release.json").write_text(json.dumps(receipt))
                output = self.root / f"must-not-stage-{key}"
                with self.assertRaisesRegex(ValueError, key):
                    package.stage(target, output, "release", None)
                self.assertFalse(output.exists())

    def test_unresolved_dependency_prevents_staging(self):
        library = self.root / "libfixture-extra.so"
        subprocess.run([str(self.clang), "--target=aarch64-linux-android28", "-shared", "-fPIC",
                        "-Wl,-soname,libfixture-extra.so", "-Wl,-z,max-page-size=16384",
                        str(self.source), "-o", str(library)], check=True, capture_output=True)
        executable = self.root / "needs-extra"
        subprocess.run([str(self.clang), "--target=aarch64-linux-android28", "-fPIE", "-pie",
                        "-Wl,-z,max-page-size=16384", "-Wl,--no-as-needed", str(library),
                        str(self.source), "-o", str(executable)], check=True, capture_output=True)
        info = package.inspect_elf(executable, 183, True)
        self.assertIn("libfixture-extra.so", info["needed"])
        target = self.root / "missing-library-build"
        receipts = target / "android-native-builds"
        receipts.mkdir(parents=True)
        receipt = build_receipt("aarch64-linux-android")
        (receipts / "arm64-v8a-release.json").write_text(json.dumps(receipt))
        folder = target / "aarch64-linux-android" / "release"
        folder.mkdir(parents=True)
        for binary in package.INPUTS["binaries"]:
            shutil.copy2(executable, folder / binary)
        output = self.root / "must-not-stage-missing-library"
        with self.assertRaisesRegex(ValueError, "requires libfixture-extra.so"):
            package.stage(target, output, "release", None)
        self.assertFalse(output.exists())

    def test_shared_dependencies_are_staged_for_each_abi(self):
        target = self.root / "dynamic-fixture-build"
        receipts = target / "android-native-builds"
        receipts.mkdir(parents=True)
        libraries = self.root / "dynamic-fixture-libraries"
        for abi, config in package.INPUTS["targets"].items():
            directory = libraries / abi
            directory.mkdir(parents=True)
            library = directory / "libfixture-extra.so"
            flags = [str(self.clang), f"--target={config['clang']}28",
                     "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384"]
            subprocess.run(flags + ["-shared", "-fPIC", "-Wl,-soname,libfixture-extra.so",
                                   str(self.source), "-o", str(library)], check=True, capture_output=True)
            folder = target / config["rust"] / "release"
            folder.mkdir(parents=True)
            executable = folder / "codex-app-server"
            subprocess.run(flags + ["-fPIE", "-pie", "-Wl,--no-as-needed", str(library),
                                   str(self.source), "-o", str(executable)], check=True, capture_output=True)
            shutil.copy2(executable, folder / "codex-code-mode-host")
            (receipts / f"{abi}-release.json").write_text(json.dumps(build_receipt(config["rust"])))
        output = self.root / "dynamic-fixture-stage-only"
        manifest = package.stage(target, output, "release", libraries)
        for abi in package.INPUTS["targets"]:
            staged = output / "jniLibs" / abi / "libfixture-extra.so"
            self.assertEqual(staged.read_bytes(), (libraries / abi / staged.name).read_bytes())
            self.assertIsNone(manifest["abis"][abi]["files"][staged.name]["interpreter"])
            self.assertIn(staged.name, manifest["abis"][abi]["files"]["libcodex-app-server.so"]["needed"])


if __name__ == "__main__":
    unittest.main()
