"""Build pinned Android OpenSSL with existing MSYS/NDK tools; no system install."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile
from prepare_msys import prepare
from package import inspect_elf

SCRIPTS = Path(__file__).resolve().parent
INPUTS = json.loads((SCRIPTS / "inputs.lock.json").read_text())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=["arm64-v8a", "x86_64", "all"], default="all")
    parser.add_argument("--cargo-home", type=Path, default=Path("E:/cn-cargo"))
    parser.add_argument("--work", type=Path, default=Path("E:/pocket-build/openssl-android"))
    parser.add_argument("--output", type=Path, default=Path("E:/pocket-build/openssl-artifacts"))
    parser.add_argument("--ndk", type=Path, default=Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/ndk" / INPUTS["ndk_version"])
    parser.add_argument("--bash", type=Path, help="Existing complete MSYS shell; default stages a private copy on E:")
    parser.add_argument("--jobs", type=int, default=4)
    args = parser.parse_args()
    if args.jobs < 1:
        parser.error("--jobs must be positive.")
    for path in [args.work, args.output]:
        if path.resolve().drive.upper() != "E:":
            parser.error("Keep OpenSSL sources and build outputs on E:.")
    if args.bash is None:
        args.bash = prepare(Path("C:/msys64"), Path("E:/pocket-build/android-native-msys"))
    if not args.bash.is_file():
        parser.error("An existing MSYS Bash, Perl and make are required.")
    if f"Pkg.Revision = {INPUTS['ndk_version']}" not in (args.ndk / "source.properties").read_text():
        parser.error("NDK revision differs from the pin.")
    pin = INPUTS["openssl"]
    archive_name = f"openssl-src-{pin['crate_version']}.crate"
    archives = list((args.cargo_home / "registry/cache").glob(f"*/{archive_name}"))
    if len(archives) != 1:
        parser.error(f"Expected one cached pinned source archive: {archive_name}")
    # Validate the registry transfer against the existing Cargo.lock checksum.
    if hashlib.sha256(archives[0].read_bytes()).hexdigest() != pin["crate_checksum"]:
        parser.error("OpenSSL source archive differs from the locked Cargo checksum.")
    args.work.mkdir(parents=True, exist_ok=True)
    source = args.work / f"openssl-src-{pin['crate_version']}" / "openssl"
    if not source.exists():
        with tarfile.open(archives[0]) as archive:
            archive.extractall(args.work, filter="data")
    abis = list(INPUTS["targets"]) if args.abi == "all" else [args.abi]
    for abi in abis:
        target = INPUTS["targets"][abi]["rust"]
        destination = args.output / target
        destination.mkdir(parents=True, exist_ok=True)
        receipt = {"schema": 1, "target": target, "ndk_version": INPUTS["ndk_version"],
                   "min_sdk": INPUTS["min_sdk"], "openssl": pin, "status": "building"}
        receipt_path = destination / "openssl-build.json"
        receipt_path.write_text(json.dumps(receipt, indent=2) + "\n")
        log = args.work / f"{abi}.log"
        command = [str(args.bash), str(SCRIPTS / "build-openssl.sh"), str(source), str(args.ndk),
                   str(args.work / target), str(destination), target, str(args.jobs)]
        print(f"Building OpenSSL {pin['version']} for {abi}; log: {log}", flush=True)
        with log.open("w") as stream:
            result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT)
        if result.returncode:
            print("\n".join(log.read_text(errors="replace").splitlines()[-45:]), flush=True)
            raise SystemExit(result.returncode)
        # Link against both real target archives before declaring this SDK usable.
        # The fixture stays in the build directory and is never a runtime artifact.
        fixture = args.work / target / "tls-link-probe.c"
        fixture.write_text("#include <openssl/ssl.h>\n#include <openssl/sha.h>\n"
                           "int main(void) { unsigned char hash[32]; SHA256((const unsigned char *)\"probe\",5,hash); "
                           "SSL_CTX *ctx=SSL_CTX_new(TLS_method()); if(!ctx) return 1; SSL_CTX_free(ctx); return 0; }\n")
        executable = fixture.with_suffix("")
        clang = args.ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
        command = [str(clang), f"--target={target}{INPUTS['min_sdk']}", "-fPIE", "-pie",
                   "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384",
                   "-I", str(destination / "include"), str(fixture),
                   str(destination / "lib/libssl.a"), str(destination / "lib/libcrypto.a"),
                   "-ldl", "-pthread", "-o", str(executable)]
        with log.open("a") as stream:
            subprocess.run(command, check=True, stdout=stream, stderr=subprocess.STDOUT)
        receipt["link_probe"] = inspect_elf(executable, INPUTS["targets"][abi]["machine"], True)
        receipt["status"] = "built"
        receipt_path.write_text(json.dumps(receipt, indent=2) + "\n")
        print(f"Staged static OpenSSL: {destination}", flush=True)


if __name__ == "__main__":
    main()
