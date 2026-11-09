#!/usr/bin/env python3
"""Verify that the intended clean FontFit/AutoFill APK is the integration base."""
from __future__ import annotations

import hashlib
import json
import sys
import zipfile
from pathlib import Path

EXPECTED_SHA256 = "3cc9ac5a10add494b2053b947a8c748c7f0c2f5c69e936216a5ef1df4c833910"
METADATA = "res/raw/translate_models_metadata.json"
TESSERACT_SO = "lib/arm64-v8a/libtesseract.so"
REQUIRED_DEX = ["classes.dex", "classes2.dex", "classes3.dex", "classes4.dex"]


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: verify_base_apk.py <apk>", file=sys.stderr)
        return 2
    apk = Path(sys.argv[1])
    if not apk.is_file():
        raise SystemExit(f"Base APK not found: {apk}")

    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    if digest != EXPECTED_SHA256:
        raise SystemExit(
            "Wrong base APK. Expected the user-provided "
            "TapTranslate_1.125_FontFit_AutoFill_unsigned.apk.\n"
            f"Expected SHA-256: {EXPECTED_SHA256}\nActual SHA-256:   {digest}"
        )

    with zipfile.ZipFile(apk) as zf:
        names = set(zf.namelist())
        required = REQUIRED_DEX + [METADATA, TESSERACT_SO]
        missing = [name for name in required if name not in names]
        if missing:
            raise SystemExit("Base APK is missing required original files: " + ", ".join(missing))
        metadata = json.loads(zf.read(METADATA))
        missing_packages = [key for key in ("PKG_LOW", "PKG_HIGH") if key not in metadata]
        if missing_packages:
            raise SystemExit(
                "Base APK's original Offline Translate metadata is incomplete; missing "
                + ", ".join(missing_packages)
            )
        print("Verified clean FontFit_AutoFill base APK")
        print("SHA-256:", digest)
        print("Original Offline Translate packages: PKG_LOW + PKG_HIGH")
        print("Original Tesseract native library:", TESSERACT_SO)
        print("Original DEX set:", ", ".join(REQUIRED_DEX))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
