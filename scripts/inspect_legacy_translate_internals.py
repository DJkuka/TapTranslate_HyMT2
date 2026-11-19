#!/usr/bin/env python3
"""Inspect APK219's existing ML Kit internals for model enumeration/deletion paths."""
from __future__ import annotations
import argparse, re
from pathlib import Path

PREFIXES = (
 "com/google/mlkit/nl/translate/",
 "com/google/mlkit/common/model/",
 "com/google/mlkit/common/sdkinternal/model/",
 "com/google/mlkit/common/sdkinternal/",
)
INTEREST = re.compile(
 r"(model|download|delete|remote|translate|language|filemanager|filehelper|"
 r"modelinfo|modeltype|filemover|translatorimpl|jni)",
 re.I
)
STRING_INTEREST = re.compile(
 r"(model|download|delete|remote|translate|language|tflite|manifest|metadata)",
 re.I
)
INVOKE_INTEREST = re.compile(
 r"(RemoteModel|ModelFile|ModelManager|ModelDownload|ModelInfo|"
 r"Translator|Translate|download|delete|File;->|SharedPreferences)",
 re.I
)
def dirs(root):
 d=[p for p in root.glob("smali*") if p.is_dir()]
 return sorted(d,key=lambda p: (1,int(p.name.removeprefix("smali_classes"))) if p.name.startswith("smali_classes") else (0,1))
def main():
 ap=argparse.ArgumentParser()
 ap.add_argument("--base",type=Path,required=True)
 ap.add_argument("--helper",type=Path,required=True)
 ap.add_argument("--out",type=Path,required=True)
 a=ap.parse_args()
 lines=[]
 def w(s=""): lines.append(s)
 inventories={}
 for label,root in (("APK219",a.base),("HELPER",a.helper)):
  inv={}
  for d in dirs(root):
   for p in d.rglob("*.smali"):
    rel=p.relative_to(d).as_posix()
    inv["L"+rel[:-6]+";"]=p
  inventories[label]=inv
  w(f"=== {label} INVENTORY ===")
  w(f"Total classes: {len(inv)}")
  for desc in sorted(inv):
   p=desc[1:-1]
   if p.startswith(PREFIXES):
    w(p)
  w()

 base=inventories["APK219"]
 w("=== APK219 ML KIT TRANSLATE / MODEL CLASS DECLARATIONS ===")
 for desc,p in sorted(base.items()):
  name=desc[1:-1]
  if not name.startswith(PREFIXES): continue
  if not INTEREST.search(name): continue
  src=p.read_text(encoding="utf-8",errors="replace")
  headers=[x.strip() for x in src.splitlines() if x.strip().startswith((".class ",".super ",".implements ",".method "))]
  w(f"--- {name}")
  for h in headers: w(h)
  w()

 w("=== APK219 RELEVANT CONST-STRING VALUES BY CLASS AND METHOD ===")
 for desc,p in sorted(base.items()):
  src=p.read_text(encoding="utf-8",errors="replace")
  if not any(x in desc[1:-1] for x in (
      "com/google/mlkit/nl/translate/",
      "com/google/mlkit/common/",
      "com/google/android/gms/internal/mlkit_translate/",
      "com/google/android/gms/internal/mlkit_common/",
  )):
   continue
  current="<class>"
  linesrc=src.splitlines()
  for i,line in enumerate(linesrc):
   s=line.strip()
   if s.startswith(".method "): current=s
   if "const-string" in s:
    m=re.search(r',\s*"(.*)"$',s)
    if m and STRING_INTEREST.search(m.group(1)):
     w(f"{desc[1:-1]} :: {current} :: {m.group(1)}")
 w()

 w("=== APK219 MODEL/TRANSLATE INTERNAL CALL GRAPH ===")
 for desc,p in sorted(base.items()):
  name=desc[1:-1]
  if not (name.startswith("com/google/mlkit/nl/translate/")
          or name.startswith("com/google/mlkit/common/model/")
          or name.startswith("com/google/mlkit/common/sdkinternal/model/")):
   continue
  src=p.read_text(encoding="utf-8",errors="replace")
  current="<class>"
  found=[]
  for line in src.splitlines():
   s=line.strip()
   if s.startswith(".method "): current=s
   if s.startswith("invoke-") and INVOKE_INTEREST.search(s):
    found.append((current,s))
  if found:
   w(f"--- {name}")
   for method,call in found: w(f"{method}\n  {call}")
 w()

 w("=== MODEL STORAGE CLASSES PRESENT / ABSENT IN APK219 VS HELPER ===")
 candidates=(
  "com/google/mlkit/common/sdkinternal/model/ModelFileHelper",
  "com/google/mlkit/common/sdkinternal/model/RemoteModelFileManager",
  "com/google/mlkit/common/sdkinternal/model/ModelFileManager",
  "com/google/mlkit/common/sdkinternal/model/ModelDownloadManager",
  "com/google/mlkit/common/sdkinternal/model/ModelInfo",
  "com/google/mlkit/common/sdkinternal/model/ModelInfoRetrieverInterop",
  "com/google/mlkit/common/sdkinternal/model/ModelFileMover",
  "com/google/mlkit/common/sdkinternal/model/RemoteModelLoader",
  "com/google/mlkit/common/model/RemoteModelManager",
  "com/google/mlkit/common/model/RemoteModel",
  "com/google/mlkit/nl/translate/TranslateRemoteModel",
  "com/google/mlkit/nl/translate/internal/TranslatorImpl",
 )
 helper=inventories["HELPER"]
 for name in candidates:
  d="L"+name+";"
  w(f"{name}: base={'YES' if d in base else 'NO'} helper={'YES' if d in helper else 'NO'}")
  for label,inv in (("BASE",base),("HELPER",helper)):
   if d in inv:
    p=inv[d]
    src=p.read_text(encoding="utf-8",errors="replace")
    w(f"  {label}:")
    for line in src.splitlines():
     if line.strip().startswith((".super ",".implements ",".method ")): w("    "+line.strip())
 w()

 w("=== ALL APK219 REFERENCES TO ML KIT MODEL STORAGE METHODS ===")
 for desc,p in sorted(base.items()):
  src=p.read_text(encoding="utf-8",errors="replace")
  refs=[]
  for line in src.splitlines():
   s=line.strip()
   if s.startswith("invoke-") and re.search(r"(ModelFileHelper|RemoteModelFileManager|ModelDownloadManager|ModelInfoRetriever|RemoteModelLoader|RemoteModelManager)",s):
    refs.append(s)
  if refs:
   w("--- "+desc[1:-1])
   for x in refs: w(x)
 w()

 a.out.parent.mkdir(parents=True,exist_ok=True)
 a.out.write_text("\n".join(lines)+"\n",encoding="utf-8")
 print(f"Wrote {a.out} ({a.out.stat().st_size} bytes; {len(lines)} lines)")
 return 0
if __name__=="__main__": raise SystemExit(main())
