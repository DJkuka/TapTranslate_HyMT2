#!/usr/bin/env python3
"""Deep, whole-DEX tracing of the legacy ML Kit translation/model storage code."""
from __future__ import annotations
import argparse, re, json
from collections import deque
from pathlib import Path

ROOT_NAMES = (
    "com/google/mlkit/nl/translate/internal/TranslateJni",
    "com/google/mlkit/nl/translate/internal/TranslatorImpl",
    "com/google/mlkit/nl/translate/NaturalLanguageTranslateRegistrar",
)
TOKENS = (
    "ModelFileHelper", "RemoteModelFileManager", "RemoteModelUtils",
    "TranslateModelLoader", "TranslateModelMover", "TranslateDLManager",
    "com.google.mlkit.translate.models", "com.google.mlkit.base.models",
    "translate_models_metadata", "downloadModelIfNeeded", "deleteAllModels",
    "Error deleting model directory", "Failed to copy downloaded model file",
    "Translation model files not found", "modelDir=", "REMOTE_MODEL_DELETE_ON_DEVICE",
    "REMOTE_MODEL_IS_DOWNLOADED", "deleteModelLogEvent", "modelDownloadLogEvent",
    "translate_jni", "No existing model file", "Model downloaded successfully",
)
FILE_CALL = re.compile(
    r"(Ljava/io/File;->(delete|list|listFiles|exists|mkdir|mkdirs|renameTo|"
    r"getAbsolutePath|isDirectory|isFile|length|toPath)|"
    r"(Landroid/content/Context;|Landroid/content/ContextWrapper;)->"
    r"(getFilesDir|getCacheDir|getNoBackupFilesDir|getDir|openFileInput|openFileOutput)|"
    r"(Landroid/content/SharedPreferences;)->|"
    r"(Ljava/nio/file/Files;)->|"
    r"(Landroid/os/Environment;)->)",
)
INVOKE = re.compile(r"^\s*invoke-\S+\s+\{[^}]*\},\s+(L[^;]+;)->([^\s(]+)(\([^)]*\)\S+)", re.M)
TYPE = re.compile(r"^\s*(?:new-instance|check-cast|instance-of|const-class|new-array|filled-new-array(?:/range)?)\s+[^,]+,\s*(L[^;]+;)", re.M)
CONST = re.compile(r'^\s*const-string(?:/jumbo)?\s+\S+,\s+"(.*)"\s*$', re.M)

def get_dirs(root):
    ds=[p for p in root.glob("smali*") if p.is_dir()]
    return sorted(ds,key=lambda p:(1,int(p.name.removeprefix("smali_classes"))) if p.name.startswith("smali_classes") else (0,1))

def inventory(root):
    out={}
    for d in get_dirs(root):
        for p in d.rglob("*.smali"):
            rel=p.relative_to(d).as_posix()
            out["L"+rel[:-6]+";"]=p
    return out

def read(path):
    return path.read_text(encoding="utf-8",errors="replace")

def method_sections(src):
    lines=src.splitlines()
    current="<class>"
    buf=[]
    for i,line in enumerate(lines,1):
        s=line.strip()
        if s.startswith(".method "):
            if buf: yield current,buf
            current=s
            buf=[(i,line)]
        elif current!="<class>":
            buf.append((i,line))
            if s==".end method":
                yield current,buf
                current="<class>"
                buf=[]
    if buf: yield current,buf

def short(d):
    return d[1:-1] if d.startswith("L") and d.endswith(";") else d

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--base",type=Path,required=True)
    ap.add_argument("--out",type=Path,required=True)
    args=ap.parse_args()
    inv=inventory(args.base)
    lines=[]
    def w(s=""): lines.append(s)
    w("WHOLE-DEX LEGACY TRANSLATE / MODEL STORAGE TRACE")
    w(f"Total base APK classes scanned: {len(inv)}")
    root_desc=["L"+x+";" for x in ROOT_NAMES]
    w("=== ROOT CLASS PRESENCE ===")
    for d in root_desc: w(f"{short(d)}: {'PRESENT' if d in inv else 'ABSENT'}")
    w()

    # First pass: find every class/method that owns a relevant constant.
    owners={}
    for desc,p in inv.items():
        src=read(p)
        hits=[]
        current="<class>"
        for n,line in enumerate(src.splitlines(),1):
            s=line.strip()
            if s.startswith(".method "): current=s
            if "const-string" in s:
                for token in TOKENS:
                    if token.lower() in s.lower():
                        hits.append((n,current,token,s))
        if hits: owners[desc]=hits
    w("=== ALL RELEVANT STRINGS ACROSS ALL DEX PACKAGES ===")
    for desc,hits in sorted(owners.items()):
        w("--- "+short(desc))
        for n,m,token,line in hits:
            w(f"line {n} [{m}] token={token}: {line.strip()}")
    w()

    # Full declared methods and all invoke/new-instance operations of the ML Kit roots.
    w("=== FULL LEGACY ROOT CLASS METHOD/CALL SUMMARY ===")
    for d in root_desc:
        if d not in inv: continue
        src=read(inv[d])
        w("--- "+short(d))
        for line in src.splitlines():
            s=line.strip()
            if s.startswith((".class ",".super ",".implements ",".field ",".method ")):
                w(s)
            elif s.startswith(("invoke-","new-instance","check-cast","const-class")):
                w("  "+s)
        w()

    # Trace calls/types from the three retained roots through base classes.
    queue=deque((d,0) for d in root_desc if d in inv)
    seen=set()
    trace_depth=3
    w(f"=== BASE CALL/TYPE GRAPH FROM LEGACY TRANSLATE ROOTS (depth <= {trace_depth}) ===")
    while queue:
        desc,depth=queue.popleft()
        if desc in seen or depth>trace_depth: continue
        seen.add(desc)
        p=inv.get(desc)
        if not p: continue
        src=read(p)
        calls=INVOKE.findall(src)
        types=TYPE.findall(src)
        targets=set()
        for owner,name,sig in calls:
            if owner in inv and owner not in seen: targets.add(owner)
        for target in types:
            if target in inv and target not in seen: targets.add(target)
        # Print all methods with file-related calls or relevant strings; also roots at any depth.
        relevant_methods=[]
        for m,body in method_sections(src):
            bodytext="\n".join(x for _,x in body)
            if FILE_CALL.search(bodytext) or any(t.lower() in bodytext.lower() for t in TOKENS):
                relevant_methods.append((m,body))
        if depth==0 or relevant_methods:
            w(f"--- depth={depth} class={short(desc)}")
            if depth==0:
                for line in src.splitlines():
                    s=line.strip()
                    if s.startswith((".class ",".super ",".implements ",".method ")): w(s)
            for m,body in relevant_methods:
                w("  METHOD "+m)
                for n,line in body:
                    if line.strip().startswith(("invoke-","new-instance","check-cast","const-string","sget","sput","iget","iput","return","move-result")) or FILE_CALL.search(line):
                        w(f"    {n}: {line.strip()}")
        if depth<trace_depth:
            for t in sorted(targets):
                queue.append((t,depth+1))
    w()
    w("=== CLASSES WITH RELEVANT STRINGS THAT ARE OUTSIDE NAMED ML KIT PACKAGES ===")
    for d in sorted(owners):
        name=short(d)
        if not (name.startswith("com/google/mlkit/") or name.startswith("com/google/android/gms/internal/mlkit_")):
            w(name)
    w()

    # Full implementations of the obfuscated legacy model storage/download classes.
    # These are the actual APK219 classes behind names such as ModelFileHelper;
    # their short descriptors are needed to avoid substituting the modern runtime.
    DUMP_TARGETS = (
        "hf/b", "hf/c", "kf/c", "kf/e", "kf/f", "kf/i", "kf/l", "kf/p",
        "gf/k", "gf/m", "gf/i", "jf/b", "jf/d",
        "com/google/mlkit/nl/translate/internal/TranslatorImpl",
        "com/google/mlkit/nl/translate/internal/TranslateJni",
        "com/google/mlkit/nl/translate/NaturalLanguageTranslateRegistrar",
    )
    w("=== FULL APK219 SMALI FOR LEGACY TRANSLATE/MODEL STORAGE CLASSES ===")
    for name in DUMP_TARGETS:
        desc = "L" + name + ";"
        p = inv.get(desc)
        if p is None:
            w(f"--- {name}: NOT FOUND")
            continue
        w(f"--- BEGIN {name} ---")
        for line in read(p).splitlines():
            w(line)
        w(f"--- END {name} ---")
        w()

    w("=== TRANSLATE MODEL RESOURCE METADATA ===")
    metadata = args.base / "res/raw/translate_models_metadata.json"
    if metadata.is_file():
        try:
            meta = json.loads(metadata.read_text(encoding="utf-8"))
            for package_name in ("PKG_HIGH", "PKG_LOW"):
                entries = meta.get(package_name, {})
                w(f"{package_name}: {len(entries)} model-pair entries")
                for pair, row in sorted(entries.items()):
                    caps = row.get("CAPS", {})
                    w(
                        f"{pair} DL_SZ={row.get('DL_SZ')} SZ={row.get('SZ')} "
                        f"RV={row.get('RV')} HASH={row.get('HASH')} "
                        f"L1={caps.get('L1')} L2={caps.get('L2')}"
                    )
            w(f"Metadata root keys: {sorted(meta.keys())}")
        except Exception as exc:
            w(f"Metadata parse failed: {type(exc).__name__}: {exc}")
    else:
        w("res/raw/translate_models_metadata.json: NOT FOUND in decoded APK")
    args.out.parent.mkdir(parents=True,exist_ok=True)
    args.out.write_text("\n".join(lines)+"\n",encoding="utf-8")
    print(f"Wrote {args.out}: {len(lines)} lines, {args.out.stat().st_size} bytes, {len(owners)} string-owning classes, {len(seen)} graph classes")
    return 0

if __name__=="__main__": raise SystemExit(main())
