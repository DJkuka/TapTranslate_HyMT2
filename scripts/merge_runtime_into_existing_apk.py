#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

BASE_SHA256 = "c35ba5505e17f0cf11ecfd4672d5f3f0f33a0b662988a0a46d5c08da73d2c1b3"

RUNTIME_PREFIXES = (
    "com/tap_to_translate/snap_translate/hymt2",
    "dev/ffmpegkit/llama",
    "kotlinx",
    "kotlin",
    "org/jetbrains/annotations",
    "ai/onnxruntime",
    "com/paddle/ocr",
    "org/opencv",
)

REPLACEABLE_NATIVE = {
    "libllama_jni.so",
    "libllama.so",
    "libggml.so",
    "libggml-base.so",
    "libggml-cpu.so",
    "libggml-vulkan.so",
    "libhymt2_jni.so",
    "libc++_shared.so",
    "libopencv_java4.so",
}

REQUIRED_NATIVE = {
    "libllama_jni.so",
    "libllama.so",
    "libggml.so",
    "libggml-base.so",
    "libggml-cpu.so",
    "libggml-vulkan.so",
    "libhymt2_jni.so",
    "libonnxruntime.so",
    "libonnxruntime4j_jni.so",
    "libopencv_java4.so",
}

PRESERVED_CLASSES = (
    "com/tap_to_translate/snap_translate/hymt2/TranslationRegionEditor.smali",
    "com/tap_to_translate/snap_translate/hymt2/DisplayProfilesActivity.smali",
    "com/tap_to_translate/snap_translate/hymt2/DisplayProfilesStore.smali",
    "com/tap_to_translate/snap_translate/hymt2/FloatingIconZOrderFix.smali",
    "com/tap_to_translate/snap_translate/hymt2/ManualSelectionTouchMode.smali",
    "com/tap_to_translate/snap_translate/hymt2/TranslationScreenshotManager.smali",
)

def run(cmd: list[str]) -> None:
    print("+", " ".join(map(str, cmd)), flush=True)
    subprocess.run(cmd, check=True)

def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def smali_dirs(root: Path) -> list[Path]:
    return sorted(p for p in root.glob("smali*") if p.is_dir())

def locate_smali(root: Path, rel: str) -> Path | None:
    for directory in smali_dirs(root):
        candidate = directory / rel
        if candidate.is_file():
            return candidate
    return None

def verify_base_features(base: Path) -> None:
    missing = [rel for rel in PRESERVED_CLASSES if locate_smali(base, rel) is None]
    if missing:
        raise RuntimeError("APK 217 feature classes are missing: " + ", ".join(missing))
    manifest = (base / "AndroidManifest.xml").read_text(encoding="utf-8")
    for marker in (
        "HyMT2SettingsActivity",
        "DisplayProfilesActivity",
        "OcrSettingsActivity",
    ):
        if marker not in manifest:
            raise RuntimeError("APK 217 manifest is missing " + marker)
    print("Verified APK 217 editor, display profiles, selection gesture and settings classes.")

def copy_runtime_smali(helper: Path, base: Path) -> None:
    target_root = base / "smali_classes5"
    target_root.mkdir(parents=True, exist_ok=True)
    copied = 0
    replaced = 0
    skipped_identical = 0

    for helper_dir in smali_dirs(helper):
        for prefix in RUNTIME_PREFIXES:
            package_root = helper_dir / prefix
            if not package_root.exists():
                continue
            for source in package_root.rglob("*.smali"):
                rel = source.relative_to(helper_dir)
                target = target_root / rel
                existing = [directory / rel for directory in smali_dirs(base)
                            if (directory / rel).is_file() and directory != target_root]

                if target.is_file():
                    shutil.copy2(source, target)
                    replaced += 1
                    continue

                if existing:
                    if any(path.read_bytes() == source.read_bytes() for path in existing):
                        skipped_identical += 1
                        continue
                    # Host Kotlin/coroutines may be in a different dex; never add
                    # a second copy of a class descriptor with different bytecode.
                    raise RuntimeError(
                        f"Runtime class collision outside classes5.dex: {rel.as_posix()}"
                    )

                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
                copied += 1

    if copied + replaced == 0:
        raise RuntimeError("No Hy-MT2 runtime classes were found in the helper APK")
    print(f"Runtime DEX merge: {copied} added, {replaced} replaced, {skipped_identical} identical host classes retained.")

def copy_native_libraries(helper: Path, base: Path) -> None:
    source_dir = helper / "lib/arm64-v8a"
    if not source_dir.is_dir():
        raise RuntimeError("Helper APK has no lib/arm64-v8a directory")
    destination_dir = base / "lib/arm64-v8a"
    destination_dir.mkdir(parents=True, exist_ok=True)

    sources = list(source_dir.glob("*.so"))
    if not sources:
        raise RuntimeError("Helper APK contains no native shared libraries")

    for source in sources:
        destination = destination_dir / source.name
        if destination.exists() and destination.read_bytes() != source.read_bytes():
            if source.name not in REPLACEABLE_NATIVE:
                raise RuntimeError("Unexpected native library collision: " + source.name)
            print("Replacing native runtime:", source.name)
        shutil.copy2(source, destination)

    missing = sorted(name for name in REQUIRED_NATIVE if not (destination_dir / name).is_file())
    if missing:
        raise RuntimeError("Final APK is missing runtime libraries: " + ", ".join(missing))
    print("Verified STQ1_0 CPU + Q4_K_M Vulkan native library set.")

def copy_new_assets_and_services(helper: Path, base: Path) -> None:
    helper_assets = helper / "assets"
    added_assets = 0
    if helper_assets.is_dir():
        for source in helper_assets.rglob("*"):
            if not source.is_file():
                continue
            rel = source.relative_to(helper_assets)
            destination = base / "assets" / rel
            destination.parent.mkdir(parents=True, exist_ok=True)
            if destination.exists():
                if destination.read_bytes() != source.read_bytes():
                    raise RuntimeError("Helper asset conflicts with APK 217: " + rel.as_posix())
                continue
            shutil.copy2(source, destination)
            added_assets += 1

    helper_services = helper / "unknown/META-INF/services"
    base_services = base / "unknown/META-INF/services"
    merged_services = 0
    if helper_services.is_dir():
        base_services.mkdir(parents=True, exist_ok=True)
        for source in helper_services.rglob("*"):
            if not source.is_file():
                continue
            rel = source.relative_to(helper_services)
            destination = base_services / rel
            destination.parent.mkdir(parents=True, exist_ok=True)
            if destination.exists():
                old = destination.read_text(encoding="utf-8", errors="ignore")
                new = source.read_text(encoding="utf-8", errors="ignore")
                lines = list(dict.fromkeys(
                    line for line in (old + "\n" + new).splitlines() if line.strip()
                ))
                destination.write_text("\n".join(lines) + "\n", encoding="utf-8")
            else:
                shutil.copy2(source, destination)
            merged_services += 1
    print(f"Preserved APK 217 assets; added {added_assets} new helper assets and merged {merged_services} service files.")

def verify_preserved_payload(base_apk: Path, output_apk: Path) -> None:
    with zipfile.ZipFile(base_apk) as original, zipfile.ZipFile(output_apk) as built:
        old_names = set(original.namelist())
        new_names = set(built.namelist())

        # APK 217's original assets (OCR models, dictionaries, and other resources)
        # must survive byte-for-byte.
        for name in old_names:
            if name.startswith("assets/"):
                if name not in new_names or original.read(name) != built.read(name):
                    raise RuntimeError("APK 217 asset changed or disappeared: " + name)

        # Preserve every pre-existing native library except the explicitly replaced
        # llama.cpp runtime and shared C++/OpenCV compatibility libraries.
        for name in old_names:
            if not name.startswith("lib/"):
                continue
            basename = name.rsplit("/", 1)[-1]
            if basename in REPLACEABLE_NATIVE:
                continue
            if name not in new_names or original.read(name) != built.read(name):
                raise RuntimeError("Unrelated APK 217 native library changed: " + name)

        # DEX files other than classes5.dex must be byte-identical.
        for name in old_names:
            if name.startswith("classes") and name.endswith(".dex") and name != "classes5.dex":
                if name not in new_names or original.read(name) != built.read(name):
                    raise RuntimeError("Unrelated DEX changed during runtime merge: " + name)

        if "classes5.dex" not in new_names:
            raise RuntimeError("Final APK has no classes5.dex")
        signatures = [
            name for name in new_names
            if name.upper().startswith("META-INF/")
            and name.upper().endswith((".RSA", ".DSA", ".EC", ".SF"))
        ]
        if signatures:
            raise RuntimeError("Final APK unexpectedly contains signing files: " + ", ".join(signatures))

    print("Verified original APK 217 OCR assets, dictionaries and unrelated native/DEX payloads.")

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-apk", required=True, type=Path)
    parser.add_argument("--helper-apk", required=True, type=Path)
    parser.add_argument("--apktool", required=True, type=Path)
    parser.add_argument("--work", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--zipalign", required=True)
    args = parser.parse_args()

    if not args.base_apk.is_file():
        raise FileNotFoundError(args.base_apk)
    actual_sha = sha256(args.base_apk)
    if actual_sha != BASE_SHA256:
        raise RuntimeError(f"Wrong base APK 217 SHA-256: {actual_sha}")

    args.work.mkdir(parents=True, exist_ok=True)
    base = args.work / "base_decoded"
    helper = args.work / "helper_decoded"
    if base.exists():
        shutil.rmtree(base)
    if helper.exists():
        shutil.rmtree(helper)

    run(["java", "-jar", str(args.apktool), "d", "-f", "-o", str(base), str(args.base_apk)])
    run(["java", "-jar", str(args.apktool), "d", "-f", "-o", str(helper), str(args.helper_apk)])

    verify_base_features(base)
    copy_runtime_smali(helper, base)
    copy_native_libraries(helper, base)
    copy_new_assets_and_services(helper, base)

    # Runtime-only merge: do not reapply the old OCR, panel editor, profile or
    # settings patches because all those features are already present in APK 217.
    unaligned = args.work / "TapTranslate_STQ1_0_APK217_unsigned_unaligned.apk"
    run(["java", "-jar", str(args.apktool), "b", str(base), "-o", str(unaligned)])

    args.output.parent.mkdir(parents=True, exist_ok=True)
    run([args.zipalign, "-f", "-p", "4", str(unaligned), str(args.output)])
    run([args.zipalign, "-c", "-p", "4", str(args.output)])
    verify_preserved_payload(args.base_apk, args.output)
    print("Base APK SHA-256:", actual_sha)
    print("Unsigned output SHA-256:", sha256(args.output))
    print(f"Unsigned APK created: {args.output} ({args.output.stat().st_size} bytes)")
    return 0

if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print("ERROR:", exc, file=sys.stderr)
        raise
