#!/usr/bin/env python3
"""Focused executable-reference audit for restoring ML Kit's model manager additively."""
from __future__ import annotations

import argparse
import re
from collections import deque
from pathlib import Path

SYSTEM_PREFIXES = (
    "android/", "java/", "javax/", "kotlin/", "kotlinx/",
    "dalvik/", "sun/", "org/jetbrains/annotations/",
)
ROOTS = (
    "Lcom/google/mlkit/common/model/RemoteModelManager;",
    "Lcom/google/mlkit/common/model/RemoteModel;",
    "Lcom/google/mlkit/nl/translate/TranslateRemoteModel;",
)
INVOKE_RE = re.compile(
    r"^\s*invoke-\S+\s+\{[^}]*\},\s+(L[^;]+;)->([^\s(]+)(\([^)]*\)\S+)",
    re.MULTILINE,
)
FIELD_RE = re.compile(
    r"^\s*(?:sget|sput|iget|iput|sget-boolean|sput-boolean|iget-boolean|iput-boolean|"
    r"sget-object|sput-object|iget-object|iput-object|sget-wide|sput-wide|iget-wide|iput-wide)"
    r"\s+\S+,\s+(L[^;]+;)->([^\s:]+):(\S+)",
    re.MULTILINE,
)
TYPE_RE = re.compile(
    r"^\s*(?:new-instance|check-cast|instance-of|const-class|new-array|filled-new-array(?:/range)?)"
    r"\s+[^,]+,\s*(L[^;]+;)",
    re.MULTILINE,
)

def smali_dirs(root: Path) -> list[Path]:
    ds = [p for p in root.glob("smali*") if p.is_dir()]
    return sorted(ds, key=lambda p: (1, int(p.name.removeprefix("smali_classes")))
                  if p.name.startswith("smali_classes") else (0, 1))

def inventory(root: Path) -> dict[str, Path]:
    out: dict[str, Path] = {}
    for d in smali_dirs(root):
        for p in d.rglob("*.smali"):
            rel = p.relative_to(d).as_posix()
            out["L" + rel[:-6] + ";"] = p
    return out

def api(path: Path):
    source = path.read_text(encoding="utf-8", errors="replace")
    methods, fields = set(), set()
    super_desc = None
    interfaces = set()
    for line in source.splitlines():
        s = line.strip()
        if s.startswith(".method "):
            methods.add(s.split()[-1])
        elif s.startswith(".field "):
            fields.add(s.split()[-1].split("=", 1)[0])
        elif s.startswith(".super "):
            super_desc = s.split()[-1]
        elif s.startswith(".implements "):
            interfaces.add(s.split()[-1])
    return methods, fields, super_desc, interfaces

def short(desc: str) -> str:
    return desc[1:-1] if desc.startswith("L") and desc.endswith(";") else desc

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True, type=Path)
    ap.add_argument("--helper", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    args = ap.parse_args()
    base, helper = inventory(args.base), inventory(args.helper)
    base_api = {d: api(p) for d,p in base.items()}
    helper_api = {d: api(p) for d,p in helper.items()}

    queue = deque(d for d in ROOTS if d in helper)
    visited: set[str] = set()
    missing_classes: set[str] = set()
    missing_methods: set[tuple[str,str,str]] = set()
    missing_fields: set[tuple[str,str,str]] = set()
    unresolved: set[str] = set()
    calls_by_owner: dict[str,set[str]] = {}
    callers_by_owner: dict[str,set[str]] = {}

    while queue:
        desc = queue.popleft()
        if desc in visited:
            continue
        visited.add(desc)
        p = helper.get(desc)
        if p is None:
            unresolved.add(desc)
            continue
        source = p.read_text(encoding="utf-8", errors="replace")
        # Only executable references are considered. Comments, debug metadata,
        # annotations and generic signatures do not expand the runtime closure.
        method_refs = INVOKE_RE.findall(source)
        field_refs = FIELD_RE.findall(source)
        type_refs = TYPE_RE.findall(source)
        supers = [api(p)[2]] if api(p)[2] else []
        interfaces = list(api(p)[3])
        all_refs = set()
        for owner, name, sig in method_refs:
            target = owner if owner.startswith("L") else "L"+owner+";"
            calls_by_owner.setdefault(target,set()).add(name+sig)
            callers_by_owner.setdefault(target,set()).add(short(desc))
            all_refs.add(target)
            if target in base:
                if name+sig not in base_api[target][0]:
                    missing_methods.add((desc,target,name+sig))
            elif target in helper:
                if target not in visited:
                    missing_classes.add(target)
                    queue.append(target)
            else:
                if not short(target).startswith(SYSTEM_PREFIXES):
                    unresolved.add(target)
        for owner, name, typ in field_refs:
            target = owner if owner.startswith("L") else "L"+owner+";"
            all_refs.add(target)
            if target in base:
                if name+":"+typ not in base_api[target][1]:
                    missing_fields.add((desc,target,name+":"+typ))
            elif target in helper:
                if target not in visited:
                    missing_classes.add(target)
                    queue.append(target)
            else:
                if not short(target).startswith(SYSTEM_PREFIXES):
                    unresolved.add(target)
        for raw in type_refs + [(s[1:-1] if s.startswith("L") else s) for s in supers+interfaces]:
            target = raw if raw.startswith("L") else "L"+raw+";"
            all_refs.add(target)
            if target in base:
                continue
            if target in helper:
                missing_classes.add(target)
                if target not in visited:
                    queue.append(target)
            elif not short(target).startswith(SYSTEM_PREFIXES):
                unresolved.add(target)

    lines=[]
    def emit(s=""): lines.append(s)
    emit("FOCUSED ML KIT MODEL-MANAGER EXECUTABLE ABI AUDIT")
    emit(f"APK219 definitions: {len(base)}")
    emit(f"isolated helper definitions: {len(helper)}")
    emit("Roots (Translator deliberately excluded): " + ", ".join(short(d) for d in ROOTS))
    emit(f"Helper classes traversed: {len(visited)}")
    emit(f"New classes required by executable-reference closure: {len(missing_classes)}")
    emit(f"Methods absent from unchanged APK219 classes: {len(missing_methods)}")
    emit(f"Fields absent from unchanged APK219 classes: {len(missing_fields)}")
    emit(f"Unresolved non-platform references: {len(unresolved)}")
    emit()
    emit("=== TRAVERSED HELPER CLASSES ===")
    for d in sorted(visited): emit(short(d))
    emit()
    emit("=== MISSING CLASSES ===")
    for d in sorted(missing_classes): emit(short(d))
    emit()
    emit("=== METHOD ABI MISMATCHES ===")
    for c,o,m in sorted(missing_methods): emit(f"{short(c)} -> {short(o)}->{m}")
    emit()
    emit("=== FIELD ABI MISMATCHES ===")
    for c,o,f in sorted(missing_fields): emit(f"{short(c)} -> {short(o)}->{f}")
    emit()
    emit("=== UNRESOLVED REFERENCES ===")
    for d in sorted(unresolved): emit(short(d))
    emit()
    emit("=== TASK METHODS CALLED BY THE MANAGER CLOSURE ===")
    task="Lcom/google/android/gms/tasks/Task;"
    for caller in sorted(callers_by_owner.get(task,())):
        emit("Caller: "+caller)
    for m in sorted(calls_by_owner.get(task,())): emit(m)
    emit()
    emit("=== APK219 TASK DECLARATION ===")
    if task in base:
        m, f, sup, impl = base_api[task]
        emit("super="+str(sup))
        emit("interfaces="+", ".join(sorted(impl)))
        for x in sorted(m): emit(x)
    else:
        emit("Task not present")
    emit()
    emit("=== OLD TASK PARAMETER INTERFACE TYPES ===")
    if task in base:
        source=base[task].read_text(encoding="utf-8", errors="replace")
        types=set()
        for name in re.findall(r"L(pa/[^;]+);",source): types.add("L"+name+";")
        for d in sorted(types):
            if d in base:
                m,f,sup,impl=base_api[d]
                emit(short(d)+" super="+str(sup)+" interfaces="+",".join(sorted(impl)))
                for x in sorted(m): emit("  "+x)
    emit()
    emit("=== OLD TASK PARAMETER INTERFACE TYPE DEFINITIONS ===")
    if task in base:
        source=base[task].read_text(encoding="utf-8", errors="replace")
        types=set("L"+name+";" for name in re.findall(r"L(pa/[^;]+);",source))
        for d in sorted(types):
            emit(short(d) + (": PRESENT IN APK219" if d in base else ": ABSENT IN APK219"))
            if d in base:
                for method in sorted(base_api[d][0]): emit("  " + method)
    emit()
    emit("=== INTERPRETATION ===")
    if missing_methods or missing_fields:
        emit("At least one manager-closure executable reference targets an existing APK219 class with a missing exact member. Additive restore requires a narrow compatibility adapter or an alternate internal implementation; do not replace the host class.")
    else:
        emit("No executable member mismatch was found in the focused closure. A no-replacement additive restore is a candidate for an isolated test build, not yet runtime-proven.")
    emit("Translator was intentionally excluded because the existing host translator must remain untouched.")
    args.out.parent.mkdir(parents=True,exist_ok=True)
    args.out.write_text("\n".join(lines)+"\n",encoding="utf-8")
    print("\n".join(lines))
    return 0
if __name__=="__main__":
    raise SystemExit(main())
