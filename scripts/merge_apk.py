#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ANDROID_NS = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID_NS)

ENGINE_NAME = "Hy-MT2 Offline"

# These belong to the user's verified FontFit/AutoFill base and must not be modified.
# x9/e: existing OCR traineddata download/load path; qh/p: language list;
# vh/j: OCR-engine routing; si/a: existing Google Offline model-package selection.
PRESERVE_OCR_SMALI = (
    "smali_classes2/x9/e.smali",
    "smali_classes3/qh/p.smali",
    "smali_classes3/si/a.smali",
)
PRESERVE_METHOD = ("smali_classes3/yh/f.smali", r".method public final e(Ljava/lang/String;)V")
METADATA_PATH = "res/raw/translate_models_metadata.json"
TESSERACT_SO_PATH = "lib/arm64-v8a/libtesseract.so"
RTDETR_MODEL_ASSET = "assets/models/bubble_detection/detector-v4-s_int8.onnx"
RTDETR_MODEL_BYTES = 11_120_765
RTDETR_MODEL_SHA256 = "5fe9e4f576e49d4e7e8b0e029d6d3cdc252abd4694113e1cae120e62c931ea79"

def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def snapshot_preserved_ocr(base: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for rel in PRESERVE_OCR_SMALI:
        path = base / rel
        if not path.is_file():
            raise RuntimeError(f"Verified base is missing OCR preservation file: {rel}")
        result[rel] = sha256_bytes(path.read_bytes())
    metadata = base / METADATA_PATH
    if not metadata.is_file():
        raise RuntimeError(f"Verified base is missing {METADATA_PATH}")

    # yh/f is extended with a new dispatch branch, but its original Google Offline
    # translation method must remain byte-for-byte the same method body.
    host_file, method_header = PRESERVE_METHOD
    host_text = (base / host_file).read_text(encoding="utf-8")
    start = host_text.find(method_header)
    if start < 0:
        raise RuntimeError("Verified base is missing the original Google Offline method yh/f->e")
    end = host_text.find(".end method", start)
    if end < 0:
        raise RuntimeError("Could not isolate original Google Offline method yh/f->e")
    result[host_file + "#GoogleOfflineMethodE"] = sha256_bytes(host_text[start:end + len(".end method")].encode("utf-8"))
    return result

def assert_preserved_ocr(base: Path, before: dict[str, str]) -> None:
    for rel, expected in before.items():
        if "#GoogleOfflineMethodE" in rel:
            host_file, _ = PRESERVE_METHOD
            host_text = (base / host_file).read_text(encoding="utf-8")
            start = host_text.find(PRESERVE_METHOD[1])
            end = host_text.find(".end method", start)
            actual = sha256_bytes(host_text[start:end + len(".end method")].encode("utf-8"))
        else:
            actual = sha256_bytes((base / rel).read_bytes())
        if actual != expected:
            raise RuntimeError(f"OCR/Offline downloader code was unexpectedly modified: {rel}")
    print("Verified original OCR/language-download files and Google Offline translation method remain unchanged")

def verify_final_host_payload(base_apk: Path, final_apk: Path) -> None:
    """Ensure original language metadata, assets, and native OCR libraries survive merge."""
    with zipfile.ZipFile(base_apk) as original, zipfile.ZipFile(final_apk) as built:
        original_names = set(original.namelist())
        built_names = set(built.namelist())
        for rel in (METADATA_PATH, TESSERACT_SO_PATH):
            if rel not in original_names or rel not in built_names:
                raise RuntimeError(f"Original host payload missing after merge: {rel}")
            if original.read(rel) != built.read(rel):
                raise RuntimeError(f"Original host payload changed during merge: {rel}")

        if RTDETR_MODEL_ASSET not in built_names:
            raise RuntimeError("Final APK is missing the RT-DETR model asset")
        model_data = built.read(RTDETR_MODEL_ASSET)
        if len(model_data) != RTDETR_MODEL_BYTES or sha256_bytes(model_data) != RTDETR_MODEL_SHA256:
            raise RuntimeError("Final APK contains an invalid RT-DETR model asset")
        for runtime_name in ("lib/arm64-v8a/libonnxruntime.so", "lib/arm64-v8a/libonnxruntime4j_jni.so"):
            if runtime_name not in built_names:
                raise RuntimeError("Final APK is missing ONNX Runtime library: " + runtime_name)

        metadata = __import__("json").loads(built.read(METADATA_PATH))
        if "PKG_LOW" not in metadata or "PKG_HIGH" not in metadata:
            raise RuntimeError("Original Google Offline Translate PKG_LOW/PKG_HIGH metadata was not preserved")

        required_vulkan = {
            "lib/arm64-v8a/libllama_jni.so",
            "lib/arm64-v8a/libllama.so",
            "lib/arm64-v8a/libggml.so",
            "lib/arm64-v8a/libggml-base.so",
            "lib/arm64-v8a/libggml-cpu.so",
            "lib/arm64-v8a/libggml-vulkan.so",
            "lib/arm64-v8a/libhymt2_jni.so",
        }
        missing_vulkan = sorted(required_vulkan - built_names)
        if missing_vulkan:
            raise RuntimeError("Final APK is missing Vulkan runtime libraries: " + ", ".join(missing_vulkan))

        for prefix in ("assets/", "lib/"):
            source_entries = {n for n in original_names if n.startswith(prefix)}
            for name in source_entries:
                # Replace only the llama.cpp runtime; preserve all unrelated host
                # native libraries, assets, Tesseract and offline translation files.
                if prefix == "lib/" and name.rsplit("/", 1)[-1] in {
                    "libllama_jni.so", "libllama.so", "libggml.so",
                    "libggml-base.so", "libggml-cpu.so", "libggml-vulkan.so",
                    "libhymt2_jni.so", "libc++_shared.so",
                }:
                    continue
                if name not in built_names or original.read(name) != built.read(name):
                    raise RuntimeError(f"Original {prefix} payload changed or disappeared: {name}")
    print("Verified final APK preserves original assets, native libraries, Tesseract and both Offline Translate model packages")

ACTIVITY = "com.tap_to_translate.snap_translate.hymt2.HyMT2SettingsActivity"
ROW_ID_NAME = "hymt2_model_manager_row"
ROW_ID = "0x7f0a04f1"


def run(cmd: list[str], cwd: Path | None = None) -> None:
    print("+", " ".join(map(str, cmd)), flush=True)
    subprocess.run(cmd, cwd=str(cwd) if cwd else None, check=True)


def ensure_text_once(path: Path, marker: str, addition: str, after: str | None = None) -> None:
    text = path.read_text(encoding="utf-8")
    if marker in text:
        return
    if after is None or after not in text:
        raise RuntimeError(f"Insertion anchor not found in {path}: {after!r}")
    text = text.replace(after, after + addition, 1)
    path.write_text(text, encoding="utf-8")


def patch_engine_list(base: Path) -> None:
    path = base / "smali_classes3/yh/h.smali"
    text = path.read_text(encoding="utf-8")
    if ENGINE_NAME not in text:
        text = text.replace(".locals 9\n", ".locals 10\n", 1)
        anchor = '    const-string v8, "Baidu Translate"\n'
        if anchor not in text:
            raise RuntimeError("Could not find provider-list tail in yh/h.smali")
        text = text.replace(anchor, anchor + f'    const-string v9, "{ENGINE_NAME}"\n', 1)
        old = "    filled-new-array/range {v1 .. v8}, [Ljava/lang/String;\n"
        if old not in text:
            raise RuntimeError("Could not find filled-new-array provider list in yh/h.smali")
        text = text.replace(old, "    filled-new-array/range {v1 .. v9}, [Ljava/lang/String;\n", 1)
        path.write_text(text, encoding="utf-8")


def patch_live_hybrid_ocr(base: Path) -> None:
    """Wire RT-DETR into the actual screenshot -> ML Kit -> translation path."""
    path = base / "smali_classes3/vh/j.smali"
    text = path.read_text(encoding="utf-8")

    # Bind detector inference to the exact Bitmap retained by the ML Kit callback.
    # vh/j->a(...) creates vh/i with the same p2 object and later vh/j->d(...) receives it.
    # Hooking the earlier j(Bitmap) entry can see a different capture bitmap and fail identity matching.
    frame_hook = "    invoke-static {p0, p2}, Lcom/tap_to_translate/snap_translate/hymt2/ocr/RtDetrLiveBridge;->onFrame(Ljava/lang/Object;Landroid/graphics/Bitmap;)V\n"
    frame_method = ".method public final a(Lnf/e;Landroid/graphics/Bitmap;Z)V"
    if frame_hook.strip() not in text:
        start = text.find(frame_method)
        if start < 0:
            raise RuntimeError("Could not find ML Kit bitmap submission vh/j->a(Lnf/e,Bitmap,Z)")
        method_end = text.find(".end method", start)
        if method_end < 0:
            raise RuntimeError("Could not isolate vh/j->a ML Kit submission method")
        method = text[start:method_end]
        lines = method.splitlines(keepends=True)
        offset = 0
        insert = None
        for line in lines:
            if line.strip().startswith((".locals ", ".registers ")):
                insert = start + offset + len(line)
                break
            offset += len(line)
        if insert is None:
            raise RuntimeError("Could not find vh/j->a register declaration")
        # p0 and p2 live above v15 in this method; copy them to low scratch registers
        # before invoke-static, then the original prologue overwrites v0/v1 as usual.
        safe_frame_hook = (
            "    move-object/from16 v0, p0\n"
            "    move-object/from16 v1, p2\n"
            "    invoke-static {v0, v1}, Lcom/tap_to_translate/snap_translate/hymt2/ocr/RtDetrLiveBridge;->onFrame(Ljava/lang/Object;Landroid/graphics/Bitmap;)V\n"
        )
        text = text[:insert] + "\n" + safe_frame_hook + text[insert:]

    result_hook = "    invoke-static/range {p0 .. p3}, Lcom/tap_to_translate/snap_translate/hymt2/ocr/RtDetrLiveBridge;->filter(Ljava/lang/Object;Landroid/content/Context;Ljava/lang/Object;Landroid/graphics/Bitmap;)V\n"
    result_method = ".method public final d(Landroid/content/Context;Lnf/d;Landroid/graphics/Bitmap;Z)V"
    if result_hook.strip() not in text:
        start = text.find(result_method)
        if start < 0:
            raise RuntimeError("Could not find live OCR result entry vh/j->d(Context,nf/d,Bitmap,boolean)")
        body = text.find("    .locals 21\n", start)
        if body < 0:
            raise RuntimeError("Could not find vh/j->d local register declaration")
        insert = body + len("    .locals 21\n")
        text = text[:insert] + "\n" + result_hook + text[insert:]

    # Gate the two pair-edge creation sites in the host's paragraph grouping loop.
    method_start = text.find(result_method)
    method_end = text.find(".end method", method_start)
    if method_start < 0 or method_end < 0:
        raise RuntimeError("Could not isolate vh/j->d grouping method")
    method = text[method_start:method_end]
    edge_block = """    new-instance v6, Lwh/d;

    invoke-direct {v6, v8, v13}, Lwh/d;-><init>(II)V

    invoke-virtual {v4, v6}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z

    move/from16 v14, p2"""
    if "RtDetrLiveBridge;->allowGrouping" not in method:
        if method.count(edge_block) != 2:
            raise RuntimeError(
                "Expected exactly two OCR grouping-edge sites in vh/j->d; found "
                + str(method.count(edge_block))
            )
        # Locate both ORIGINAL edge sites before modifying the method. Replacing
        # the same text twice is unsafe because the inserted guard itself contains
        # edge_block, which would nest both guards at the first site and leave the
        # second path unguarded.
        edge_positions = []
        search_from = 0
        while True:
            edge_pos = method.find(edge_block, search_from)
            if edge_pos < 0:
                break
            edge_positions.append(edge_pos)
            search_from = edge_pos + len(edge_block)
        if len(edge_positions) != 2:
            raise RuntimeError("Could not uniquely locate both original OCR grouping edges")

        # Insert from right to left so original offsets remain valid.
        for edge_index, edge_pos in reversed(list(enumerate(edge_positions))):
            label = ":hymt2_skip_bubble_edge_" + str(edge_index)
            guard = """    invoke-static {v0, v2, v8, v13}, Lcom/tap_to_translate/snap_translate/hymt2/ocr/RtDetrLiveBridge;->allowGrouping(Ljava/lang/Object;Ljava/lang/Object;II)Z

    move-result v20

    if-eqz v20, """ + label + """

"""
            guarded_tail = """

    """ + label
            method = (method[:edge_pos] + guard + edge_block
                      + guarded_tail + method[edge_pos + len(edge_block):])
        text = text[:method_start] + method + text[method_end:]

    if "invoke-static {v0, v1}, Lcom/tap_to_translate/snap_translate/hymt2/ocr/RtDetrLiveBridge;->onFrame" not in text or result_hook.strip() not in text:
        raise RuntimeError("RT-DETR hooks were not written into the live OCR call chain")
    if "RtDetrLiveBridge;->allowGrouping" not in text:
        raise RuntimeError("RT-DETR bubble grouping guard was not inserted")
    path.write_text(text, encoding="utf-8")
    print("Wired RT-DETR into capture, ML Kit filtering and cross-bubble grouping guard")


def patch_dispatcher(base: Path) -> None:
    path = base / "smali_classes3/yh/f.smali"
    text = path.read_text(encoding="utf-8")

    if "HyMT2Runtime;->translate" not in text:
        # Insert a new dispatch branch after Google's existing ML Kit offline provider.
        anchor = '''    invoke-virtual {p0, p2}, Lyh/f;->e(Ljava/lang/String;)V

    return-void

    :cond_4
    const-string v0, "Yandex Translate"'''
        replacement = f'''    invoke-virtual {{p0, p2}}, Lyh/f;->e(Ljava/lang/String;)V

    return-void

    :hymt2_check
    const-string v0, "{ENGINE_NAME}"

    invoke-virtual {{p1, v0}}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v0

    if-eqz v0, :cond_4

    invoke-static {{}}, Lqh/p;->d()Ljava/lang/String;

    move-result-object v1

    invoke-static {{}}, Lqh/p;->f()Ljava/lang/String;

    move-result-object v2

    invoke-static {{p0, p2, v1, v2}}, Lcom/tap_to_translate/snap_translate/hymt2/HyMT2Runtime;->translate(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V

    return-void

    :cond_4
    const-string v0, "Yandex Translate"'''
        # The original method arrives at :cond_4 after Offline's non-match, so route it through our check.
        text = text.replace(anchor, replacement, 1)
        # Change the non-match jump of the Offline Translate branch to check Hy-MT2 first.
        old_jump = '''    move-result v0

    if-eqz v0, :cond_4

    invoke-virtual {p0, p2}, Lyh/f;->e(Ljava/lang/String;)V'''
        new_jump = '''    move-result v0

    if-eqz v0, :hymt2_check

    invoke-virtual {p0, p2}, Lyh/f;->e(Ljava/lang/String;)V'''
        if old_jump not in text:
            raise RuntimeError("Could not find Offline Translate branch in yh/f.smali")
        text = text.replace(old_jump, new_jump, 1)

    # In l(), preserve Hy-MT2 as the chosen engine rather than mapping it to Google backup.
    if 'const-string v1, "Hy-MT2 Offline"' not in text:
        anchor = '''    :cond_6
    invoke-virtual {p2, v3}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z'''
        replacement = f'''    :cond_6
    const-string v1, "{ENGINE_NAME}"

    invoke-virtual {{p2, v1}}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v1

    if-nez v1, :goto_0

    invoke-virtual {{p2, v3}}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z'''
        if anchor not in text:
            raise RuntimeError("Could not find engine fallback branch in yh/f.smali")
        text = text.replace(anchor, replacement, 1)

    path.write_text(text, encoding="utf-8")


def patch_picker_callbacks(base: Path) -> None:
    """Launch the SAF picker from all four real translation-engine selectors."""
    specs = [
        (
            "smali_classes3/sh/l.smali",
            "    :cond_0\n    const/4 p4, 0x1",
            [
                '    iget-object v1, p1, Lcom/tap_to_translate/snap_translate/domain/main/widgets/custom/WidgetChooseTranslation;->d:Ljava/util/ArrayList;',
                '    invoke-virtual {v1, p3}, Ljava/util/ArrayList;->get(I)Ljava/lang/Object;',
                '    move-result-object v1',
                '    check-cast v1, Ljava/lang/String;',
                '    const-string v2, "TapTranslateHyMT2"',
                '    invoke-static {v2, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I',
                '    const-string v2, "Hy-MT2 Offline"',
                '    invoke-virtual {v1, v2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z',
                '    move-result v2',
                '    if-eqz v2, :hymt2_picker_done_sh_l',
                '    iget-object v3, p0, Lsh/l;->b:Lcom/tap_to_translate/snap_translate/domain/main/widgets/custom/WidgetChooseTranslation;',
                '    invoke-virtual {v3}, Landroid/view/View;->getContext()Landroid/content/Context;',
                '    move-result-object v3',
                '    invoke-static {v3}, Lcom/tap_to_translate/snap_translate/hymt2/HyMT2Launcher;->openIfModelMissing(Landroid/content/Context;)V',
                '    :hymt2_picker_done_sh_l',
            ],
            "WidgetChooseTranslation Spinner callback",
        ),
        (
            "smali_classes3/mh/e.smali",
            "    invoke-static {p1, v3}, Lqh/n;->b(Ljava/lang/Object;Ljava/lang/String;)V\n\n    sget-object v3, Lyh/h;->b:Ljava/util/HashSet;",
            [
                '    const-string v3, "TapTranslateHyMT2"',
                '    invoke-static {v3, p1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I',
                '    const-string v3, "Hy-MT2 Offline"',
                '    invoke-virtual {p1, v3}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z',
                '    move-result v3',
                '    if-eqz v3, :hymt2_picker_done_mh_e',
                '    iget-object v4, p0, Lmh/e;->b:Lmh/l;',
                '    invoke-virtual {v4}, Landroidx/fragment/app/Fragment;->getContext()Landroid/content/Context;',
                '    move-result-object v3',
                '    invoke-static {v3}, Lcom/tap_to_translate/snap_translate/hymt2/HyMT2Launcher;->openIfModelMissing(Landroid/content/Context;)V',
                '    :hymt2_picker_done_mh_e',
            ],
            "Language picker Fragment callback",
        ),
        (
            "smali/a2/q0.smali",
            "    invoke-static {p1, v4}, Lqh/n;->b(Ljava/lang/Object;Ljava/lang/String;)V\n\n    sget-object v4, Lyh/h;->b:Ljava/util/HashSet;",
            [
                '    const-string v4, "TapTranslateHyMT2"',
                '    invoke-static {v4, p1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I',
                '    const-string v4, "Hy-MT2 Offline"',
                '    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z',
                '    move-result v4',
                '    if-eqz v4, :hymt2_picker_done_a2_q0',
                '    invoke-static {v0}, Lcom/tap_to_translate/snap_translate/hymt2/HyMT2Launcher;->openIfModelMissing(Landroid/content/Context;)V',
                '    :hymt2_picker_done_a2_q0',
            ],
            "SettingsServiceActivity provider callback",
        ),
        (
            "smali_classes3/qh/f.smali",
            "    invoke-static {p1, v6}, Lqh/n;->b(Ljava/lang/Object;Ljava/lang/String;)V\n\n    sget-object v6, Lyh/h;->b:Ljava/util/HashSet;",
            [
                '    const-string v6, "TapTranslateHyMT2"',
                '    invoke-static {v6, p1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I',
                '    const-string v6, "Hy-MT2 Offline"',
                '    invoke-virtual {p1, v6}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z',
                '    move-result v6',
                '    if-eqz v6, :hymt2_picker_done_qh_f',
                '    invoke-virtual {v2}, Landroid/view/View;->getContext()Landroid/content/Context;',
                '    move-result-object v6',
                '    invoke-static {v6}, Lcom/tap_to_translate/snap_translate/hymt2/HyMT2Launcher;->openIfModelMissing(Landroid/content/Context;)V',
                '    :hymt2_picker_done_qh_f',
            ],
            "Language provider list callback",
        ),
    ]
    for rel, anchor, lines, description in specs:
        path = base / rel
        if not path.exists():
            raise RuntimeError(f"Missing picker callback source: {path}")
        text = path.read_text(encoding="utf-8")
        if "HyMT2Launcher;->openIfModelMissing" in text:
            print(f"Picker callback already patched: {description}")
            continue
        if text.count(anchor) != 1:
            raise RuntimeError(f"Expected exactly one picker insertion anchor for {description}; found {text.count(anchor)}")
        insert = "\n".join(lines) + "\n"
        if "\n\n" in anchor:
            before, after = anchor.split("\n\n", 1)
            replacement = before + "\n\n" + insert + "\n" + after
        else:
            before, after = anchor.split("\n", 1)
            replacement = before + "\n" + insert + after
        text = text.replace(anchor, replacement, 1)
        path.write_text(text, encoding="utf-8")
        print(f"Patched immediate Hy-MT2 picker launch: {description}")


def patch_atomic_backport_shims(base: Path) -> None:
    """Supply the two D8-generated CAS forwarding helpers referenced by coroutines."""
    shims = {
        "androidx/concurrent/futures/AbstractResolvableFuture$SafeAtomicHelper$$ExternalSyntheticBackportWithForwarding0.smali": '''.class public final Landroidx/concurrent/futures/AbstractResolvableFuture$SafeAtomicHelper$$ExternalSyntheticBackportWithForwarding0;
.super Ljava/lang/Object;

.method public static m(Ljava/util/concurrent/atomic/AtomicReferenceFieldUpdater;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z
    .locals 1

    invoke-virtual {p0, p1, p2, p3}, Ljava/util/concurrent/atomic/AtomicReferenceFieldUpdater;->compareAndSet(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z

    move-result v0

    return v0
.end method
''',
        "androidx/lifecycle/LifecycleKt$$ExternalSyntheticBackportWithForwarding0.smali": '''.class public final Landroidx/lifecycle/LifecycleKt$$ExternalSyntheticBackportWithForwarding0;
.super Ljava/lang/Object;

.method public static m(Ljava/util/concurrent/atomic/AtomicReference;Ljava/lang/Object;Ljava/lang/Object;)Z
    .locals 1

    invoke-virtual {p0, p1, p2}, Ljava/util/concurrent/atomic/AtomicReference;->compareAndSet(Ljava/lang/Object;Ljava/lang/Object;)Z

    move-result v0

    return v0
.end method
''',
    }
    for rel, content in shims.items():
        # Reuse a definition already present in any host DEX; otherwise add one to classes5.
        existing = [directory / rel for directory in base.glob("smali*") if directory.is_dir() and (directory / rel).exists()]
        if existing:
            print(f"D8 CAS backport shim already supplied by host: {rel}")
            continue
        path = base / "smali_classes5" / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        print(f"Added D8 CAS backport shim: {rel}")

    # Audit that every referenced D8 synthetic class has a corresponding definition.
    descriptors: set[str] = set()
    references: set[str] = set()
    ref_re = re.compile(r"L([^;\s]+ExternalSynthetic[^;\s]*);")
    class_re = re.compile(r"^\.class\s+.*?\s(L[^;]+);", re.MULTILINE)
    for smali_root in base.glob("smali*"):
        if not smali_root.is_dir():
            continue
        for smali_file in smali_root.rglob("*.smali"):
            source = smali_file.read_text(encoding="utf-8", errors="ignore")
            descriptors.update(value + ";" for value in class_re.findall(source))
            references.update("L" + value + ";" for value in ref_re.findall(source))
    missing = sorted(references - descriptors)
    if missing:
        raise RuntimeError("Unresolved D8 synthetic class references remain: " + ", ".join(missing))
    print(f"Verified all {len(references)} referenced D8 synthetic classes are present")

def patch_settings_click(base: Path) -> None:
    # Attach the row click listener inside the actual primary Settings fragment.
    fragment = base / "smali_classes3/mh/p.smali"
    text = fragment.read_text(encoding="utf-8")
    method_start = text.find(".method public final onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V")
    if method_start < 0:
        raise RuntimeError("Primary settings fragment has no onViewCreated method")
    method_end = text.find(".end method", method_start)
    if method_end < 0:
        raise RuntimeError("Could not isolate primary settings onViewCreated method")
    method = text[method_start:method_end]
    if "HyMT2SettingsClickListener" not in method:
        anchor = "    return-void\n"
        insert_at = method.rfind(anchor)
        if insert_at < 0:
            raise RuntimeError("Could not find end of primary settings onViewCreated")
        listener_code = '''    invoke-virtual {p0}, Landroidx/fragment/app/Fragment;->requireView()Landroid/view/View;

    move-result-object v1

    const v2, 0x7f0a04f1

    invoke-virtual {v1, v2}, Landroid/view/View;->findViewById(I)Landroid/view/View;

    move-result-object v1

    new-instance v2, Lcom/tap_to_translate/snap_translate/hymt2/HyMT2SettingsClickListener;

    invoke-direct {v2, p0}, Lcom/tap_to_translate/snap_translate/hymt2/HyMT2SettingsClickListener;-><init>(Lmh/p;)V

    invoke-virtual {v1, v2}, Landroid/view/View;->setOnClickListener(Landroid/view/View$OnClickListener;)V

'''
        method = method[:insert_at] + listener_code + method[insert_at:]
        text = text[:method_start] + method + text[method_end:]
        fragment.write_text(text, encoding="utf-8")

    # The listener is placed in classes5 together with the integrated Hy-MT2 code.
    listener = base / "smali_classes5/com/tap_to_translate/snap_translate/hymt2/HyMT2SettingsClickListener.smali"
    listener.parent.mkdir(parents=True, exist_ok=True)
    listener.write_text('''.class public final Lcom/tap_to_translate/snap_translate/hymt2/HyMT2SettingsClickListener;
.super Ljava/lang/Object;

# interfaces
.implements Landroid/view/View$OnClickListener;

.field private final a:Lmh/p;

.method public constructor <init>(Lmh/p;)V
    .locals 0
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput-object p1, p0, Lcom/tap_to_translate/snap_translate/hymt2/HyMT2SettingsClickListener;->a:Lmh/p;
    return-void
.end method

.method public final onClick(Landroid/view/View;)V
    .locals 3
    iget-object v0, p0, Lcom/tap_to_translate/snap_translate/hymt2/HyMT2SettingsClickListener;->a:Lmh/p;
    invoke-virtual {v0}, Landroidx/fragment/app/Fragment;->requireContext()Landroid/content/Context;
    move-result-object v0
    new-instance v1, Landroid/content/Intent;
    const-class v2, Lcom/tap_to_translate/snap_translate/hymt2/HyMT2SettingsActivity;
    invoke-direct {v1, v0, v2}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V
    invoke-virtual {v0, v1}, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V
    return-void
.end method
''', encoding="utf-8")

    # Remove the old, incorrectly attached listener from the floating settings dialog.
    settings = base / "smali_classes3/com/tap_to_translate/snap_translate/domain/main/activitys/SettingsServiceActivity.smali"
    old = settings.read_text(encoding="utf-8")
    start = old.find("    const p1, 0x7f0a04f1")
    if start >= 0:
        end_marker = "    invoke-virtual {p1, v0}, Landroid/view/View;->setOnClickListener(Landroid/view/View$OnClickListener;)V\n"
        end = old.find(end_marker, start)
        if end < 0:
            raise RuntimeError("Could not remove old secondary-settings listener cleanly")
        old = old[:start] + old[end + len(end_marker):]
        settings.write_text(old, encoding="utf-8")


def _remove_linear_layout_with_id(xml_text: str, view_id: str) -> tuple[str, bool]:
    import re
    opening = re.compile(r'<LinearLayout\b(?=[^>]*android:id="@id/' + re.escape(view_id) + r'")[^>]*>')
    match = opening.search(xml_text)
    if not match:
        return xml_text, False
    token = re.compile(r'<(/?)LinearLayout\b[^>]*>')
    depth = 0
    end = None
    for item in token.finditer(xml_text, match.start()):
        tag = item.group(0)
        if tag.startswith("</"):
            depth -= 1
        elif not tag.endswith("/>"):
            depth += 1
        if depth == 0:
            end = item.end()
            break
    if end is None:
        raise RuntimeError(f"Could not find matching </LinearLayout> for @{view_id}")
    return xml_text[:match.start()] + xml_text[end:], True


def patch_resources(base: Path) -> None:
    # Add Hy-MT2 row to the app's real Settings tab (fragment_settings.xml).
    layout = base / "res/layout/fragment_settings.xml"
    text = layout.read_text(encoding="utf-8")
    if ROW_ID_NAME not in text:
        anchor = '''            <View android:background="@color/cl_underLine" android:layout_width="fill_parent" android:layout_height="1.0dip" android:layout_marginLeft="25.0dip" android:layout_marginRight="25.0dip" />
            <LinearLayout android:gravity="start|center" android:orientation="horizontal" android:id="@id/fragment_setting_ll_voice_settings"'''
        row = f'''            <View android:background="@color/cl_underLine" android:layout_width="fill_parent" android:layout_height="1.0dip" android:layout_marginLeft="25.0dip" android:layout_marginRight="25.0dip" />
            <LinearLayout android:gravity="start|center" android:orientation="horizontal" android:id="@id/{ROW_ID_NAME}" android:paddingLeft="15.0dip" android:paddingTop="15.0dip" android:paddingRight="15.0dip" android:paddingBottom="15.0dip" android:focusable="true" android:clickable="true" android:layout_width="fill_parent" android:layout_height="wrap_content" android:foreground="@drawable/fg_efftect">
                <ImageView android:padding="5.0dip" android:layout_width="30.0dip" android:layout_height="30.0dip" android:layout_marginRight="10.0dip" android:src="@drawable/ic_download_svg" />
                <TextView android:textSize="18.0dip" android:textColor="@color/cl_text_word" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Hy-MT2 Offline · настройки перевода" />
            </LinearLayout>
            <View android:background="@color/cl_underLine" android:layout_width="fill_parent" android:layout_height="1.0dip" android:layout_marginLeft="25.0dip" android:layout_marginRight="25.0dip" />
            <LinearLayout android:gravity="start|center" android:orientation="horizontal" android:id="@id/fragment_setting_ll_voice_settings"'''
        if anchor not in text:
            raise RuntimeError("Could not find primary settings layout insertion point")
        text = text.replace(anchor, row, 1)
        layout.write_text(text, encoding="utf-8")

    # The floating SettingsServiceActivity is not the main settings screen.
    secondary = base / "res/layout/activity_settings_service.xml"
    if secondary.exists():
        old = secondary.read_text(encoding="utf-8")
        old, removed = _remove_linear_layout_with_id(old, ROW_ID_NAME)
        if ROW_ID_NAME in old:
            raise RuntimeError("Hy-MT2 row still exists in secondary SettingsServiceActivity")
        if removed:
            secondary.write_text(old, encoding="utf-8")

    if ROW_ID_NAME not in layout.read_text(encoding="utf-8"):
        raise RuntimeError("Hy-MT2 row was not added to the primary fragment_settings layout")

    ids = base / "res/values/ids.xml"
    id_text = ids.read_text(encoding="utf-8")
    if ROW_ID_NAME not in id_text:
        anchor = '    <item type="id" name="home_wcv_font_size" />\\n'
        if anchor not in id_text:
            raise RuntimeError("Could not find end of ids.xml for new view ID")
        ids.write_text(id_text.replace(anchor, anchor + f'    <item type="id" name="{ROW_ID_NAME}" />\\n', 1), encoding="utf-8")

    public = base / "res/values/public.xml"
    pub_text = public.read_text(encoding="utf-8")
    if ROW_ID_NAME not in pub_text:
        anchor = '    <public type="id" name="home_wcv_font_size" id="0x7f0a04f0" />\\n'
        if anchor not in pub_text:
            raise RuntimeError("Could not find end of public id table")
        public.write_text(pub_text.replace(anchor, anchor + f'    <public type="id" name="{ROW_ID_NAME}" id="{ROW_ID}" />\\n', 1), encoding="utf-8")

    # Explicitly verify both location and click callback.
    if ROW_ID_NAME not in layout.read_text(encoding="utf-8"):
        raise RuntimeError("Primary settings layout has no Hy-MT2 row")
    if ROW_ID_NAME in secondary.read_text(encoding="utf-8"):
        raise RuntimeError("Secondary settings layout still contains the Hy-MT2 row")
    fragment_smali = (base / "smali_classes3/mh/p.smali").read_text(encoding="utf-8")
    if "HyMT2SettingsClickListener" not in fragment_smali:
        raise RuntimeError("Primary settings row has no click listener")

def patch_manifest(base: Path) -> None:
    path = base / "AndroidManifest.xml"
    text = path.read_text(encoding="utf-8")
    if ACTIVITY in text:
        return
    marker = "    </application>"
    activity = f'''        <activity android:name="{ACTIVITY}" android:exported="false" android:theme="@style/AppThemeNoActionBar" android:label="Hy-MT2 Offline" />\n'''
    if marker not in text:
        raise RuntimeError("Could not find application closing tag in manifest")
    path.write_text(text.replace(marker, activity + marker, 1), encoding="utf-8")


def copy_package(helper_dirs: list[Path], base: Path, rel_prefixes: tuple[str, ...]) -> int:
    target_root = base / "smali_classes5"
    target_root.mkdir(parents=True, exist_ok=True)
    existing = set()
    for smali_dir in base.glob("smali*"):
        if not smali_dir.is_dir() or smali_dir == target_root:
            continue
        for p in smali_dir.rglob("*.smali"):
            existing.add(p.relative_to(smali_dir).as_posix())

    copied = 0
    replaced = 0
    for helper in helper_dirs:
        for prefix in rel_prefixes:
            root = helper / prefix
            if not root.exists():
                continue
            for source in root.rglob("*.smali"):
                rel = source.relative_to(helper).as_posix()
                destination = target_root / rel
                if rel in existing:
                    if destination.is_file():
                        # This input APK already contains the Hy-MT2 runtime.
                        # Refresh its own classes with the updated helper build.
                        shutil.copy2(source, destination)
                        replaced += 1
                        continue
                    # The host APK already supplies Kotlin stdlib/coroutines and
                    # annotations. Keep those host copies instead of duplicating them
                    # from the helper APK. The actual Hy-MT2 and llama API packages
                    # are refreshed in classes5.dex above.
                    if rel.startswith(("kotlin/", "kotlinx/", "org/jetbrains/annotations/")):
                        continue
                    # Do not silently introduce duplicate app/runtime classes across DEX files.
                    other = [d for d in base.glob("smali*") if d.is_dir() and d != target_root and (d / rel).is_file()]
                    if other:
                        if (other[0] / rel).read_bytes() == source.read_bytes():
                            continue
                        raise RuntimeError(f"Cannot safely replace {rel}: existing definition is outside classes5.dex")
                    continue
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, destination)
                existing.add(rel)
                copied += 1
    print(f"Updated {replaced} existing runtime classes; added {copied} new classes")
    return copied


def copy_helper_payload(helper: Path, base: Path) -> None:
    helper_dirs = [p for p in helper.glob("smali*") if p.is_dir()]
    prefixes = (
        "com/tap_to_translate/snap_translate/hymt2",
        "dev/ffmpegkit/llama",
        "kotlinx",
        "kotlin",
        "org/jetbrains/annotations",
        "ai/onnxruntime",
    )
    count = copy_package(helper_dirs, base, prefixes)
    if count == 0:
        raise RuntimeError("No Hy-MT2 runtime classes were found in the helper APK")
    print(f"Copied {count} smali classes to classes5.dex")
    target_root = base / "smali_classes5"

    # Copy the prebuilt ARM64 native libraries from the AAR dependency.
    helper_lib = helper / "lib/arm64-v8a"
    if not helper_lib.exists():
        raise RuntimeError("The helper APK has no arm64-v8a native library directory")
    out_lib = base / "lib/arm64-v8a"
    out_lib.mkdir(parents=True, exist_ok=True)
    so_files = list(helper_lib.glob("*.so"))
    if not so_files:
        raise RuntimeError("The llama-android AAR did not package any native .so files")
    replaceable = {
        "libllama_jni.so", "libllama.so", "libggml.so",
        "libggml-base.so", "libggml-cpu.so", "libggml-vulkan.so",
        "libhymt2_jni.so", "libc++_shared.so",
    }
    for source in so_files:
        destination = out_lib / source.name
        if destination.exists() and destination.read_bytes() != source.read_bytes():
            if source.name not in replaceable:
                raise RuntimeError(f"Unexpected native library collision: {source.name}")
            print(f"Replacing Hy-MT2 runtime library: {source.name}")
        shutil.copy2(source, destination)
    required_runtime = {
        "libllama_jni.so", "libllama.so", "libggml.so",
        "libggml-base.so", "libggml-cpu.so", "libggml-vulkan.so",
        "libhymt2_jni.so", "libonnxruntime.so", "libonnxruntime4j_jni.so",
    }
    missing_runtime = sorted(name for name in required_runtime if not (out_lib / name).is_file())
    if missing_runtime:
        raise RuntimeError("Vulkan runtime libraries missing from helper APK: " + ", ".join(missing_runtime))
    print("Copied/replaced native libraries:", ", ".join(sorted(p.name for p in so_files)))

    # Preserve helper assets (RT-DETR weights and legal notices) in the final host APK.
    helper_assets = helper / "assets"
    copied_assets = 0
    if helper_assets.exists():
        for source in helper_assets.rglob("*"):
            if not source.is_file():
                continue
            relative = source.relative_to(helper_assets)
            destination = base / "assets" / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            if destination.exists():
                if destination.read_bytes() != source.read_bytes():
                    raise RuntimeError(f"Helper asset conflicts with host asset: {relative.as_posix()}")
                continue
            shutil.copy2(source, destination)
            copied_assets += 1
    print(f"Copied {copied_assets} helper assets into final APK")

    # Coroutines can discover Android's Main dispatcher via META-INF service files.
    helper_services = helper / "unknown/META-INF/services"
    base_services = base / "unknown/META-INF/services"
    if helper_services.exists():
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
                lines = list(dict.fromkeys([x for x in (old + "\n" + new).splitlines() if x.strip()]))
                destination.write_text("\n".join(lines) + "\n", encoding="utf-8")
            else:
                shutil.copy2(source, destination)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-apk", required=True, type=Path)
    parser.add_argument("--helper-apk", required=True, type=Path)
    parser.add_argument("--apktool", required=True, type=Path)
    parser.add_argument("--work", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--zipalign", required=True)
    args = parser.parse_args()

    args.work.mkdir(parents=True, exist_ok=True)
    base = args.work / "base_decoded"
    helper = args.work / "helper_decoded"
    if base.exists():
        shutil.rmtree(base)
    if helper.exists():
        shutil.rmtree(helper)

    run(["java", "-jar", str(args.apktool), "d", "-f", "-o", str(base), str(args.base_apk)])
    run(["java", "-jar", str(args.apktool), "d", "-f", "-o", str(helper), str(args.helper_apk)])

    preserved_ocr = snapshot_preserved_ocr(base)

    copy_helper_payload(helper, base)
    patch_atomic_backport_shims(base)
    patch_engine_list(base)
    patch_dispatcher(base)
    patch_live_hybrid_ocr(base)
    patch_picker_callbacks(base)
    patch_settings_click(base)
    patch_resources(base)
    patch_manifest(base)
    assert_preserved_ocr(base, preserved_ocr)

    unaligned = args.work / "TapTranslate_HyMT2_unsigned_unaligned.apk"
    run(["java", "-jar", str(args.apktool), "b", str(base), "-o", str(unaligned)])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    run([args.zipalign, "-f", "-p", "4", str(unaligned), str(args.output)])
    run([args.zipalign, "-c", "-p", "4", str(args.output)])
    verify_final_host_payload(args.base_apk, args.output)
    print(f"Unsigned APK created: {args.output} ({args.output.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise
