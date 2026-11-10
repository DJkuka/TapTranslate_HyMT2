#!/usr/bin/env python3
"""Verify the exact current Tap Translate Hy-MT2 Release APK before Vulkan merge."""
from __future__ import annotations
import hashlib
import json
import sys
import zipfile
from pathlib import Path

EXPECTED_SHA256 = "dfe0463c9d9c0bf39451586e4e7aa405f5721504c61f20cef9fd122618418d83"
REQUIRED = (
    "classes.dex", "classes2.dex", "classes3.dex", "classes4.dex",
    "res/raw/translate_models_metadata.json",
    "lib/arm64-v8a/libtesseract.so",
    "lib/arm64-v8a/libllama_jni.so",
    "lib/arm64-v8a/libllama.so",
    "lib/arm64-v8a/libggml.so",
)

def main() -> int:
    if len(sys.argv) != 2:
        print("usage: verify_vulkan_base_apk.py <apk>", file=sys.stderr)
        return 2
    apk = Path(sys.argv[1])
    if not apk.is_file():
        raise SystemExit(f"Base APK missing: {apk}")
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    if digest != EXPECTED_SHA256:
        raise SystemExit(f"Unexpected Release APK SHA-256: {digest}; expected {EXPECTED_SHA256}")
    with zipfile.ZipFile(apk) as zf:
        names = set(zf.namelist())
        missing = [name for name in REQUIRED if name not in names]
        if missing:
            raise SystemExit("Release APK missing required host/runtime entries: " + ", ".join(missing))
        metadata = json.loads(zf.read("res/raw/translate_models_metadata.json"))
        if not all(key in metadata for key in ("PKG_LOW", "PKG_HIGH")):
            raise SystemExit("Google Offline Translate metadata does not contain PKG_LOW and PKG_HIGH")
        dex = sorted(name for name in names if name.startswith("classes") and name.endswith(".dex"))
        if not any(name.startswith("smali_classes5") for name in dex):
            print("Note: current APK has no classes5.dex; merge script will add it.")
    print("Verified current user Release APK.")
    print("SHA-256:", digest)
    print("Size:", apk.stat().st_size)
    print("Original OCR, Tesseract and Google Offline Translate metadata present.")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
