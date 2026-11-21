"""Copy existing MSYS build tools onto E: and supply their missing sh alias."""
import argparse
from pathlib import Path
import shutil
import subprocess


def prepare(source: Path, destination: Path) -> Path:
    if destination.resolve().drive.upper() != "E:":
        raise ValueError("Keep the isolated MSYS tools on E:.")
    marker = destination / ".android-native-ready"
    (destination / "tmp").mkdir(parents=True, exist_ok=True)
    if not marker.exists():
        for relative in ["usr/bin", "usr/lib/perl5", "usr/share/perl5"]:
            shutil.copytree(source / relative, destination / relative, dirs_exist_ok=True)
        # Perl's string-form pipes invoke /bin/sh even when make uses SHELL.
        # Preserve the installation and fix only this private copy.
        shutil.copy2(destination / "usr/bin/bash.exe", destination / "usr/bin/sh.exe")
        marker.write_text(str(source.resolve()) + "\n")
    bash = destination / "usr/bin/bash.exe"
    probe = 'export PATH=/usr/bin:$PATH; perl -e \'open my $p, "echo pipe_ok |" or die $!; print <$p>; close $p\''
    result = subprocess.run([str(bash), "-c", probe], text=True, capture_output=True, check=True)
    if result.stdout.strip() != "pipe_ok":
        raise RuntimeError(f"Private MSYS Perl pipe probe failed: {result.stderr}")
    return bash


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path("C:/msys64"))
    parser.add_argument("--output", type=Path, default=Path("E:/pocket-build/android-native-msys"))
    args = parser.parse_args()
    print(prepare(args.source, args.output))
