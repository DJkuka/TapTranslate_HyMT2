#!/usr/bin/env python3
from __future__ import annotations
import argparse, hashlib, shutil, subprocess, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

PKG = Path("com/tap_to_translate/snap_translate/hymt2")
ACTIVITY = "com.tap_to_translate.snap_translate.hymt2.HyMT2CpuSettingsActivity"
REPLACE_SO = "lib/arm64-v8a/libhymt2_jni.so"
RUNTIME_SOS = [
    "libllama_jni.so", "libllama.so", "libggml.so",
    "libggml-base.so", "libggml-cpu.so", "libggml-vulkan.so",
    "libc++_shared.so",
]

def run(args):
    print("+", " ".join(map(str, args)), flush=True)
    subprocess.run(args, check=True)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-apk", type=Path, required=True)
    ap.add_argument("--helper-apk", type=Path, required=True)
    ap.add_argument("--apktool", type=Path, required=True)
    ap.add_argument("--zipalign", required=True)
    ap.add_argument("--aar-native-dir", type=Path, required=True)
    ap.add_argument("--work", type=Path, required=True)
    ap.add_argument("--output", type=Path, required=True)
    args = ap.parse_args()

    expected = "0e0d30f30a98fa427a62d23c6fe670e9bd9781287da2515b1b8139f9332e82eb"
    actual = sha(args.base_apk.read_bytes())
    if actual != expected:
        raise SystemExit(f"Wrong APK 219 base: {actual}")

    args.work.mkdir(parents=True, exist_ok=True)
    base, helper = args.work / "base", args.work / "helper"
    for p in (base, helper):
        if p.exists(): shutil.rmtree(p)
    run(["java", "-jar", str(args.apktool), "d", "-f", "-o", str(base), str(args.base_apk)])
    run(["java", "-jar", str(args.apktool), "d", "-f", "-o", str(helper), str(args.helper_apk)])

    base_pkg = base / "smali_classes5" / PKG
    if not base_pkg.is_dir():
        raise SystemExit("APK219 does not contain Hy-MT2 in smali_classes5 as expected")
    helper_roots = [p for p in helper.glob("smali*") if p.is_dir()]
    copied = 0
    for root in helper_roots:
        src = root / PKG
        if not src.is_dir(): continue
        for f in src.rglob("*.smali"):
            rel = f.relative_to(src)
            dst = base_pkg / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(f, dst)
            copied += 1
    if copied < 5:
        raise SystemExit(f"Too few Hy-MT2 classes copied from helper: {copied}")
    print(f"Updated {copied} Hy-MT2 smali files; no Kotlin, Google, Firebase or ML Kit package copied")

    # Only register the new CPU settings Activity. Do not merge helper permissions,
    # providers, services, application metadata or dependencies.
    manifest = base / "AndroidManifest.xml"
    text = manifest.read_text(encoding="utf-8")
    if ACTIVITY not in text:
        close = "    </application>"
        if close not in text: raise SystemExit("Could not find application end in manifest")
        text = text.replace(close,
            f'        <activity android:name="{ACTIVITY}" android:exported="false" android:label="CPU affinity" />\\n' + close, 1)
        manifest.write_text(text, encoding="utf-8")

    with zipfile.ZipFile(args.base_apk) as original:
        original_names = set(original.namelist())
        for name in RUNTIME_SOS:
            base_name = "lib/arm64-v8a/" + name
            aar_file = args.aar_native_dir / name
            if base_name not in original_names or not aar_file.is_file():
                raise SystemExit("Missing runtime library for ABI comparison: " + name)
            if original.read(base_name) != aar_file.read_bytes():
                raise SystemExit("AAR runtime differs from APK219 for " + name + "; refusing unsafe JNI replacement")
        if REPLACE_SO not in original_names:
            raise SystemExit("APK219 does not contain libhymt2_jni.so")

    new_jni = helper / REPLACE_SO
    if not new_jni.is_file():
        raise SystemExit("Helper APK has no rebuilt libhymt2_jni.so")
    shutil.copy2(new_jni, base / REPLACE_SO)

    unaligned = args.work / "unsigned-unaligned.apk"
    run(["java", "-jar", str(args.apktool), "b", str(base), "-o", str(unaligned)])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    run([args.zipalign, "-f", "-p", "4", str(unaligned), str(args.output)])
    run([args.zipalign, "-c", "-p", "4", str(args.output)])

    with zipfile.ZipFile(args.base_apk) as original, zipfile.ZipFile(args.output) as built:
        names = set(built.namelist())
        missing = sorted(n for n in (original_names - names) if not n.startswith("META-INF/"))
        if missing: raise SystemExit("Base APK entries disappeared: " + ", ".join(missing[:20]))
        for name in original_names:
            if name.startswith("assets/") or name.startswith("lib/"):
                if name == REPLACE_SO: continue
                if original.read(name) != built.read(name):
                    raise SystemExit("Original asset/native library changed: " + name)
        for name in ("lib/arm64-v8a/" + x for x in RUNTIME_SOS):
            if original.read(name) != built.read(name):
                raise SystemExit("Core llama runtime changed unexpectedly: " + name)
        if built.read(REPLACE_SO) != new_jni.read_bytes():
            raise SystemExit("New JNI library not present in final APK")
        if ACTIVITY.encode() not in built.read("AndroidManifest.xml"):
            raise SystemExit("CPU affinity Activity missing from final manifest")

    print("Verified exact APK219 base, unchanged original assets/native libraries (except hymt2_jni), matching AAR runtime, unsigned output.")
    print("SHA256:", sha(args.output.read_bytes()))
    print("SIZE:", args.output.stat().st_size)

if __name__ == "__main__":
    main()
