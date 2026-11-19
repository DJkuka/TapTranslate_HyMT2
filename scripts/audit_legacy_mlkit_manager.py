#!/usr/bin/env python3
"""Audit whether ML Kit's model-manager API can be restored without replacing APK 219 runtimes."""
from __future__ import annotations

import argparse
import re
from collections import deque
from pathlib import Path

SYSTEM_PREFIXES = (
    "android/", "java/", "javax/", "kotlin/", "kotlinx/",
    "dalvik/", "sun/", "org/jetbrains/annotations/",
)
ROOT_DESCRIPTORS = (
    "Lcom/google/mlkit/common/model/RemoteModelManager;",
    "Lcom/google/mlkit/common/model/RemoteModel;",
    "Lcom/google/mlkit/nl/translate/TranslateRemoteModel;",
    "Lcom/google/mlkit/nl/translate/Translator;",
)
CLASS_REF_RE = re.compile(r"L([^;]+);")
METHOD_REF_RE = re.compile(r"L([^;]+);->([^\s(]+)(\([^)]*\)[^\s]+)")
FIELD_REF_RE = re.compile(r"L([^;]+);->([^\s:]+):([^\s]+)")

def smali_dirs(root: Path) -> list[Path]:
    return sorted((p for p in root.glob("smali*") if p.is_dir()),
                  key=lambda p: (1, int(p.name.removeprefix("smali_classes")))
                  if p.name.startswith("smali_classes") else (0, 1))

def locate(root: Path, descriptor: str) -> Path | None:
    rel = descriptor[1:-1] + ".smali"
    for directory in smali_dirs(root):
        candidate = directory / rel
        if candidate.is_file():
            return candidate
    return None

def parse_defs(path: Path) -> tuple[set[str], set[str], str | None, set[str]]:
    source = path.read_text(encoding="utf-8", errors="replace")
    methods: set[str] = set()
    fields: set[str] = set()
    superclass = None
    interfaces: set[str] = set()
    for line in source.splitlines():
        s = line.strip()
        if s.startswith(".method "):
            methods.add(s.split()[-1])
        elif s.startswith(".field "):
            member = s.split()[-1].split("=", 1)[0]
            fields.add(member)
        elif s.startswith(".super "):
            superclass = s.split()[-1]
        elif s.startswith(".implements "):
            interfaces.add(s.split()[-1])
    return methods, fields, superclass, interfaces

def inventory(root: Path) -> dict[str, Path]:
    out: dict[str, Path] = {}
    for d in smali_dirs(root):
        for p in d.rglob("*.smali"):
            rel = p.relative_to(d).as_posix()
            out["L" + rel[:-6] + ";"] = p
    return out

def short(desc: str) -> str:
    return desc[1:-1] if desc.startswith("L") and desc.endswith(";") else desc

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True, type=Path)
    ap.add_argument("--helper", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    args = ap.parse_args()

    base_defs = inventory(args.base)
    helper_defs = inventory(args.helper)
    base_apis = {d: parse_defs(p) for d, p in base_defs.items()}
    helper_apis = {d: parse_defs(p) for d, p in helper_defs.items()}

    lines: list[str] = []
    def emit(s: str = "") -> None:
        lines.append(s)

    emit("ML KIT LEGACY MODEL-MANAGER COMPATIBILITY AUDIT")
    emit(f"Base smali classes: {len(base_defs)}")
    emit(f"Isolated helper smali classes: {len(helper_defs)}")
    emit()
    emit("=== REQUIRED API ROOTS ===")
    roots_present = []
    for desc in ROOT_DESCRIPTORS:
        bp = base_defs.get(desc)
        hp = helper_defs.get(desc)
        emit(f"{short(desc)}: base={'YES' if bp else 'NO'} helper={'YES' if hp else 'NO'}")
        if hp:
            roots_present.append(desc)
            m, f, sup, impl = helper_apis[desc]
            emit("  helper methods: " + ", ".join(sorted(m)))
            emit("  superclass: " + str(sup))
            emit("  interfaces: " + ", ".join(sorted(impl)))
        if bp:
            m, f, sup, impl = base_apis[desc]
            emit("  base methods: " + ", ".join(sorted(m)))
    emit()

    # Follow only model-manager implementation classes which are absent in the
    # host. Existing host classes are treated as the binary boundary and are
    # checked for exact methods/fields referenced by newly added code.
    queue = deque(roots_present)
    visited: set[str] = set()
    missing_classes: set[str] = set()
    missing_methods: set[tuple[str, str, str]] = set()
    missing_fields: set[tuple[str, str, str]] = set()
    unresolved_classes: set[str] = set()
    dependency_edges: set[tuple[str, str, str]] = set()

    while queue:
        desc = queue.popleft()
        if desc in visited:
            continue
        visited.add(desc)
        hp = helper_defs.get(desc)
        if not hp:
            unresolved_classes.add(desc)
            continue
        h_methods, h_fields, h_super, h_interfaces = helper_apis[desc]
        source = hp.read_text(encoding="utf-8", errors="replace")

        # Class-level descriptors, superclass and interfaces. Recursively pull
        # a helper implementation only if APK 219 has no definition at all.
        refs = set(CLASS_REF_RE.findall(source))
        if h_super:
            refs.add(h_super[1:-1] if h_super.startswith("L") else h_super)
        refs.update(x[1:-1] if x.startswith("L") else x for x in h_interfaces)

        for ref in sorted(refs):
            target_desc = "L" + ref + ";"
            if ref.startswith(SYSTEM_PREFIXES):
                continue
            if target_desc in base_defs:
                continue
            if target_desc in helper_defs and target_desc not in visited:
                missing_classes.add(target_desc)
                dependency_edges.add((desc, target_desc, "class"))
                queue.append(target_desc)
            elif target_desc not in helper_defs:
                unresolved_classes.add(target_desc)

        for owner, name, sig in METHOD_REF_RE.findall(source):
            target = "L" + owner + ";"
            if owner.startswith(SYSTEM_PREFIXES):
                continue
            if target in base_defs:
                methods = base_apis[target][0]
                if name + sig not in methods:
                    missing_methods.add((desc, target, name + sig))
            elif target in helper_defs:
                missing_classes.add(target)
                dependency_edges.add((desc, target, "method"))
                if target not in visited:
                    queue.append(target)
            else:
                unresolved_classes.add(target)

        for owner, name, typ in FIELD_REF_RE.findall(source):
            target = "L" + owner + ";"
            if owner.startswith(SYSTEM_PREFIXES):
                continue
            if target in base_defs:
                fields = base_apis[target][1]
                if name + ":" + typ not in fields:
                    missing_fields.add((desc, target, name + ":" + typ))
            elif target in helper_defs:
                missing_classes.add(target)
                dependency_edges.add((desc, target, "field"))
                if target not in visited:
                    queue.append(target)
            else:
                unresolved_classes.add(target)

    emit("=== MANAGER DEPENDENCY CLOSURE ===")
    emit(f"Visited helper classes: {len(visited)}")
    emit(f"Classes absent from APK 219 but available in helper: {len(missing_classes)}")
    emit(f"Method references not found exactly in APK 219: {len(missing_methods)}")
    emit(f"Field references not found exactly in APK 219: {len(missing_fields)}")
    emit(f"References not defined in either APK or treated as platform: {len(unresolved_classes)}")
    emit()
    emit("=== MISSING CLASSES TO ADD (NO EXISTING BASE DEFINITION) ===")
    for desc in sorted(missing_classes):
        emit(short(desc))
    emit()
    emit("=== METHOD REFERENCES THAT WOULD BREAK AGAINST PRESERVED APK 219 CLASSES ===")
    for caller, owner, method in sorted(missing_methods):
        emit(f"{short(caller)} -> {short(owner)}->{method}")
    emit()
    emit("=== FIELD REFERENCES THAT WOULD BREAK AGAINST PRESERVED APK 219 CLASSES ===")
    for caller, owner, field in sorted(missing_fields):
        emit(f"{short(caller)} -> {short(owner)}->{field}")
    emit()
    emit("=== UNRESOLVED CLASS REFERENCES ===")
    for desc in sorted(unresolved_classes):
        emit(short(desc))
    emit()

    # The most important binary boundary: Google Tasks.
    task_desc = "Lcom/google/android/gms/tasks/Task;"
    emit("=== TASK API: APK 219 VS ISOLATED HELPER ===")
    for label, api in (("APK219", base_apis.get(task_desc)),
                       ("HELPER", helper_apis.get(task_desc))):
        emit(label + ": " + ("missing" if api is None else ""))
        if api:
            for method in sorted(api[0]):
                emit("  " + method)
    emit()
    emit("=== MODEL/MANAGER RELATED CLASSES ALREADY PRESENT IN APK219 ===")
    prefixes = ("com/google/mlkit/common/model/", "com/google/mlkit/nl/translate/")
    for desc in sorted(base_defs):
        p = short(desc)
        if p.startswith(prefixes):
            emit(p)
    emit()
    emit("=== TRANSLATOR-RELATED CALLS TO TASK IN APK219 ===")
    for desc, path in sorted(base_defs.items()):
        p = short(desc)
        if not (p.startswith("com/google/mlkit/nl/translate/")
                or p.startswith("com/google/mlkit/common/model/")):
            continue
        source = path.read_text(encoding="utf-8", errors="replace")
        for owner, name, sig in METHOD_REF_RE.findall(source):
            if owner == "com/google/android/gms/tasks/Task":
                emit(f"{p} -> Task->{name}{sig}")
    emit()
    emit("=== INTERPRETATION ===")
    if missing_methods:
        emit("The manager implementation cannot be copied as-is while preserving APK 219's existing runtime. It references methods absent from host classes.")
    else:
        emit("No exact missing methods found in the traversed manager dependency closure. A limited additive restore may be feasible; still validate runtime initialization before merging.")
    if unresolved_classes:
        emit("Some symbols are unresolved in both APKs; inspect these manually before any integration.")
    emit("This is a static DEX/API scan, not proof of runtime correctness.")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
