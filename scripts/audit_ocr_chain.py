#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

if len(sys.argv) != 3:
    raise SystemExit("usage: audit_ocr_chain.py <decoded-apk-dir> <output-dir>")

root = Path(sys.argv[1])
out = Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)

groups = {
    "ocr_mlkit": [
        "com/google/mlkit/vision/text", "com/google/mlkit/vision/common",
        "TextRecognizer", "TextRecognition", "InputImage", "recognizeText",
    ],
    "ocr_tesseract": [
        "TessBaseAPI", "tesseract", "Tesseract", "getUTF8Text",
        "setImage", "init(Ljava/lang/String;",
    ],
    "screen_capture": [
        "ScreenTranslateService", "MediaProjection", "ImageReader",
        "VirtualDisplay", "createVirtualDisplay", "onImageAvailable",
        "BitmapFactory", "takeScreenshot", "captureScreen",
    ],
    "geometry": [
        "getBoundingBox", "getCornerPoints", "Rect;", "RectF;",
        "getTextBlocks", "getLines", "getElements",
    ],
    "overlay_render": [
        "WindowManager", "TYPE_APPLICATION_OVERLAY", "StaticLayout",
        "Canvas;", "Paint;", "drawText", "TextView", "addView",
        "removeView", "setText",
    ],
}

smali_files = sorted(root.glob("smali*/**/*.smali"))
if not smali_files:
    raise SystemExit(f"No decoded smali files found under {root}")

scores = defaultdict(lambda: defaultdict(int))
class_names = {}
hits_by_file = defaultdict(lambda: defaultdict(list))

for path in smali_files:
    try:
        data = path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        continue
    match = re.search(r"^\.class\s+.*?\s+(L[^;]+;)", data, re.M)
    if match:
        class_names[path] = match.group(1)
    for group, needles in groups.items():
        for needle in needles:
            count = data.count(needle)
            if count:
                scores[path][group] += min(count, 30)
                hits_by_file[path][group].append(needle)

manifest = root / "AndroidManifest.xml"
manifest_summary = ["# AndroidManifest.xml: relevant declarations"]
if manifest.exists():
    try:
        tree = ET.parse(manifest)
        app = tree.getroot().find("application")
        ns = "{http://schemas.android.com/apk/res/android}"
        if app is not None:
            for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
                for node in app.findall(tag):
                    name = node.attrib.get(ns + "name", "")
                    if name:
                        manifest_summary.append(f"{tag}: {name}")
        for node in tree.getroot().findall("uses-permission"):
            name = node.attrib.get(ns + "name", "")
            if name and any(k in name.lower() for k in ("media", "screen", "foreground", "overlay", "storage")):
                manifest_summary.append(f"permission: {name}")
    except Exception as exc:
        manifest_summary.append(f"Manifest parse warning: {exc}")
(out / "manifest-components.txt").write_text("\n".join(manifest_summary) + "\n", encoding="utf-8")

def rank(path: Path):
    g = scores[path]
    return (
        int("ocr_mlkit" in g) + int("ocr_tesseract" in g)
        + int("screen_capture" in g) + int("geometry" in g)
        + int("overlay_render" in g),
        sum(g.values()),
    )

ranked = sorted(scores, key=rank, reverse=True)
direct_ocr = [
    p for p in ranked if "ocr_mlkit" in scores[p] or "ocr_tesseract" in scores[p]
]
selected = list(dict.fromkeys(direct_ocr + ranked))[:90]

report = []
report.append("# Tap Translate OCR pipeline audit")
report.append(f"Decoded source: {root}")
report.append(f"Smali files scanned: {len(smali_files)}")
report.append(f"Files with pipeline references: {len(scores)}")
report.append("")
report.extend(manifest_summary)
report.append("")
report.append("## Highest-signal classes")
for path in ranked[:100]:
    rel = path.relative_to(root).as_posix()
    groups_found = ", ".join(
        f"{g}({scores[path][g]})" for g in groups if scores[path].get(g)
    )
    report.append(f"- {class_names.get(path, '?')} | {rel} | {groups_found}")

report.append("")
report.append("## Relevant method excerpts")
source_out = out / "smali"
source_out.mkdir(exist_ok=True)
for path in selected:
    rel = path.relative_to(root)
    target = source_out / rel
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(path.read_bytes())

    data = path.read_text(encoding="utf-8", errors="ignore").splitlines()
    report.append("")
    report.append(f"### {class_names.get(path, '?')} — {rel.as_posix()}")
    report.append("References: " + "; ".join(
        f"{g}={','.join(hits_by_file[path][g])}" for g in groups if hits_by_file[path].get(g)
    ))
    methods = []
    current = []
    in_method = False
    for line in data:
        if line.startswith(".method "):
            in_method = True
            current = [line]
        elif in_method:
            current.append(line)
            if line.startswith(".end method"):
                block = "\n".join(current)
                if any(token in block for tokens in groups.values() for token in tokens):
                    methods.append(block)
                in_method = False
                current = []
    if methods:
        for block in methods[:12]:
            report.append("~~~smali")
            report.extend(block.splitlines()[:240])
            report.append("~~~")
    else:
        emitted = 0
        for i, line in enumerate(data):
            if any(token in line for tokens in groups.values() for token in tokens):
                start, end = max(0, i - 8), min(len(data), i + 14)
                report.append("~~~smali")
                report.extend(data[start:end])
                report.append("~~~")
                emitted += 1
                if emitted >= 8:
                    break

(out / "ocr-chain-report.md").write_text("\n".join(report) + "\n", encoding="utf-8")
print(f"Smali files scanned: {len(smali_files)}")
print(f"Pipeline-relevant classes: {len(scores)}")
print(f"Selected classes exported: {len(selected)}")
print(f"Direct OCR classes: {len(direct_ocr)}")
print("Top candidates:")
for path in ranked[:35]:
    print(f"{class_names.get(path, '?')}\t{path.relative_to(root).as_posix()}\t{dict(scores[path])}")
print(f"Report: {out / 'ocr-chain-report.md'}")
