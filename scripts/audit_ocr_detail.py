#!/usr/bin/env python3
from __future__ import annotations
import re, sys
from pathlib import Path
from collections import defaultdict

if len(sys.argv) != 3:
    raise SystemExit("usage: audit_ocr_detail.py <decoded-apk-dir> <output-dir>")
root, out = Path(sys.argv[1]), Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)
all_smali = sorted(root.glob("smali*/**/*.smali"))
descriptor_to_path = {}
path_to_desc = {}
for p in all_smali:
    s = p.read_text(encoding="utf-8", errors="ignore")
    m = re.search(r"^\.class\s+.*?\s+(L[^;]+;)", s, re.M)
    if m:
        descriptor_to_path[m.group(1)] = p
        path_to_desc[p] = m.group(1)

# Key classes confirmed by the first pass; exact package families hold the callbacks
# and view/model classes used by this obfuscated app.
roots = {
    "Lcom/tap_to_translate/snap_translate/domain/main/service/ScreenTranslateService;",
    "Lvh/j;", "Lvh/l;", "Lxh/c;", "Lrh/c;", "Lrh/d;", "Lrh/e;",
    "Lrh/f;", "Lrh/m;", "Loh/m;", "Lx9/e;", "Lqh/p;",
}
families = ("Lvh/", "Lxh/", "Lrh/", "Loh/", "Lx9/")
selected = {d for d in roots if d in descriptor_to_path}
# Include every class within the app's OCR, screen-capture and overlay packages.
selected.update(d for d in descriptor_to_path if d.startswith(families))
# Include app callback/helper classes directly referenced by those classes.
for d in list(selected):
    p = descriptor_to_path.get(d)
    if not p: continue
    s = p.read_text(encoding="utf-8", errors="ignore")
    for ref in re.findall(r"L[^;\s]+;", s):
        if ref in descriptor_to_path and ref.startswith(("Lvh/","Lxh/","Lrh/","Loh/","Lx9/")):
            selected.add(ref)

# Find all direct call-sites to the OCR and capture entry points across the APK.
needles = (
    "Lvh/j;->", "Lxh/c;->", "Lxh/a;->", "Lvh/i;->",
    "Loh/m;->", "ScreenTranslateService;->",
)
callers = defaultdict(list)
for p in all_smali:
    s = p.read_text(encoding="utf-8", errors="ignore")
    matched = [n for n in needles if n in s]
    if matched:
        callers[path_to_desc.get(p, str(p.relative_to(root)))].extend(matched)
        selected.add(path_to_desc[p]) if p in path_to_desc else None

# Explicitly include Tesseract-related code, native declarations and model/config classes.
for p in all_smali:
    s = p.read_text(encoding="utf-8", errors="ignore")
    if ("TessBaseAPI" in s or "com/googlecode/tesseract" in s
        or "libtesseract" in s or "Tesseract" in s):
        d = path_to_desc.get(p)
        if d: selected.add(d)

ordered = sorted(selected, key=lambda d: (0 if d in roots else 1, d))
src_out = out / "smali"
src_out.mkdir(exist_ok=True)
report = [
    "# OCR detailed call-graph audit",
    f"Scanned smali files: {len(all_smali)}",
    f"Decoded classes: {len(descriptor_to_path)}",
    f"Exported classes: {len(ordered)}",
    "",
    "## Exact application pipeline anchors",
]
for d in sorted(roots):
    report.append(f"- {d}: {'FOUND' if d in descriptor_to_path else 'not found'}")
report += ["", "## Cross-reference callers"]
for d, refs in sorted(callers.items()):
    report.append(f"- {d}: {', '.join(sorted(set(refs)))}")
report += ["", "## Exported classes and relevant methods"]

for d in ordered:
    p = descriptor_to_path[d]
    rel = p.relative_to(root)
    dest = src_out / rel
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(p.read_bytes())
    lines = p.read_text(encoding="utf-8", errors="ignore").splitlines()
    report += ["", f"### {d} — {rel.as_posix()}"]
    in_method, method = False, []
    extracted = 0
    for line in lines:
        if line.startswith(".method "):
            in_method, method = True, [line]
        elif in_method:
            method.append(line)
            if line.startswith(".end method"):
                block = "\n".join(method)
                if any(n in block for n in needles) or any(
                    k in block for k in (
                        "TextRecognition", "TextRecognizer", "recognizeText",
                        "InputImage", "getTextBlocks", "getBoundingBox",
                        "getCornerPoints", "MediaProjection", "ImageReader",
                        "createVirtualDisplay", "getPlanes", "getBuffer",
                        "setText", "setList", "getTexttoTranslate",
                        "getColor", "setBackgroundColor", "drawText",
                        "setImage", "TessBaseAPI",
                    )
                ):
                    report += ["~~~smali", *method[:400], "~~~"]
                    extracted += 1
                in_method = False
    if extracted == 0:
        report.append("(No direct pipeline call was found in method bodies; full source exported.)")

(out / "ocr-detailed-callgraph.md").write_text("\n".join(report) + "\n", encoding="utf-8")
(out / "class-index.txt").write_text("\n".join(ordered) + "\n", encoding="utf-8")
print(f"Scanned smali files: {len(all_smali)}")
print(f"Exported classes: {len(ordered)}")
print(f"Cross-reference callers: {len(callers)}")
for d in sorted(roots):
    print(f"{d}\t{'FOUND' if d in descriptor_to_path else 'MISSING'}")
print(f"Report: {out / 'ocr-detailed-callgraph.md'}")
