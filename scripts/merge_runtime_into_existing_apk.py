#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
import sys
import zipfile
import re
from pathlib import Path

BASE_SHA256 = "0e0d30f30a98fa427a62d23c6fe670e9bd9781287da2515b1b8139f9332e82eb"

# The four ML Kit registrars in APK 219 all used the old id.a Component type.
# Translate 17.0.3 / Common 18.11.0 use firebase.components.Component instead.
# Replace all four registrar definitions together so MlKitInitProvider never
# feeds an old id.a object into the new ComponentRuntime.
REPLACEABLE_MLKIT_BOOTSTRAP_CLASSES: set[str] = set()

# The newer TextRegistrar and VisionCommonRegistrar must be accompanied by the
# exact newer ML Kit Vision implementation they were compiled against. Replace
# colliding classes within these prefixes and copy the new classes into classes5.
REPLACEABLE_MLKIT_PACKAGE_PREFIXES: tuple[str, ...] = ()

# Google Play Services core is a versioned runtime family. Keep its classes
# aligned with the helper instead of mixing old APK 219 definitions with
# play-services-base/basement from the ML Kit dependency graph.
REPLACEABLE_GOOGLE_PLAY_SERVICES_PREFIXES: tuple[str, ...] = ()

# These AndroidX APIs are part of the helper's resolved Google Play Services
# dependency graph. Replace matching definitions as a set and copy the full
# helper inventory for each package.
REPLACEABLE_ANDROIDX_PREFIXES: tuple[str, ...] = ()

REPLACEABLE_RUNTIME_PREFIXES = (
    "dev/ffmpegkit/llama/",
    "com/paddle/ocr/",
)

# Keep APK 219's existing Firebase/DataTransport implementation used by
# Crashlytics/Sessions, and its ONNX Runtime used by the app's existing OCR.
# The language-pack manager does not require an ONNX Runtime upgrade. Copy a
# helper class only when the host does not already provide that class.
HOST_PREFERRED_MLKIT_TRANSITIVE_PREFIXES = (
    "com/google/firebase/",
    "com/google/android/datatransport/",
    "com/google/android/gms/",
    "com/google/mlkit/",
    "androidx/",
    "ai/onnxruntime/",
    "org/opencv/",
)

RUNTIME_PREFIXES = (
    # Only the llama Java JNI wrapper and the app-owned Paddle OCR pipeline
    # are replaced. ML Kit, Firebase, GMS, AndroidX, ONNX and OpenCV are
    # provided by the verified APK 219 base.
    "dev/ffmpegkit/llama",
    "com/paddle/ocr",
)

REPLACEABLE_NATIVE = {
    "libllama_jni.so",
    "libllama.so",
    "libggml.so",
    "libggml-base.so",
    "libggml-cpu.so",
    "libggml-vulkan.so",
    "libhymt2_jni.so",
    "libc++_shared.so",
}

REQUIRED_NATIVE = {
    "libllama_jni.so",
    "libllama.so",
    "libggml.so",
    "libggml-base.so",
    "libggml-cpu.so",
    "libggml-vulkan.so",
    "libhymt2_jni.so",
    "libonnxruntime.so",
    "libonnxruntime4j_jni.so",
    "libopencv_java4.so",
}

PRESERVED_CLASSES = (
    "com/tap_to_translate/snap_translate/hymt2/TranslationRegionEditor.smali",
    "com/tap_to_translate/snap_translate/hymt2/DisplayProfilesActivity.smali",
    "com/tap_to_translate/snap_translate/hymt2/DisplayProfilesStore.smali",
    "com/tap_to_translate/snap_translate/hymt2/FloatingIconZOrderFix.smali",
    "com/tap_to_translate/snap_translate/hymt2/ManualSelectionTouchMode.smali",
    "com/tap_to_translate/snap_translate/hymt2/TranslationScreenshotManager.smali",
)

PRESERVED_CLASS_FAMILIES = (
    "com/tap_to_translate/snap_translate/hymt2/TranslationRegionEditor",
)

# Only these app-owned class families belong to this runtime merge.
# Never copy the entire hymt2 package from the helper: it also contains older
# copies of already-integrated Settings/OCR/UI classes and compiler-generated
# lambdas that must remain exactly as APK 219 shipped them.
APP_RUNTIME_CLASS_FAMILIES = (
    "com/tap_to_translate/snap_translate/hymt2/HyMT2Runtime",
    "com/tap_to_translate/snap_translate/hymt2/HyMT2Options",
    "com/tap_to_translate/snap_translate/hymt2/HyMT2Settings",
    "com/tap_to_translate/snap_translate/hymt2/HyMT2SettingsActivity",
    "com/tap_to_translate/snap_translate/hymt2/HyMT2PenaltyNative",
    "com/tap_to_translate/snap_translate/hymt2/OcrSettingsActivity",
    "com/tap_to_translate/snap_translate/hymt2/ocr/PaddleOcrLiveBridge",
)

REPLACEABLE_APP_RUNTIME_FAMILIES = (
    "com/tap_to_translate/snap_translate/hymt2/HyMT2Runtime",
    "com/tap_to_translate/snap_translate/hymt2/HyMT2Options",
    "com/tap_to_translate/snap_translate/hymt2/HyMT2Settings",
    "com/tap_to_translate/snap_translate/hymt2/HyMT2SettingsActivity",
    "com/tap_to_translate/snap_translate/hymt2/HyMT2PenaltyNative",
    "com/tap_to_translate/snap_translate/hymt2/OcrSettingsActivity",
    "com/tap_to_translate/snap_translate/hymt2/ocr/PaddleOcrLiveBridge",
)

ABI_CHECK_APP_RUNTIME_FAMILIES = (
    # These classes are entry points retained for calls from APK 219 code.
    "com/tap_to_translate/snap_translate/hymt2/HyMT2Runtime",
    "com/tap_to_translate/snap_translate/hymt2/HyMT2Settings",
    "com/tap_to_translate/snap_translate/hymt2/HyMT2SettingsActivity",
    "com/tap_to_translate/snap_translate/hymt2/OcrSettingsActivity",
    "com/tap_to_translate/snap_translate/hymt2/ocr/PaddleOcrLiveBridge",
    # HyMT2PenaltyNative is replaced together with HyMT2Runtime and its
    # continuations. Its old suspend complete() method is an internal call
    # from the old runtime, not an external APK 219 API.
)

def run(cmd: list[str]) -> None:
    print("+", " ".join(map(str, cmd)), flush=True)
    subprocess.run(cmd, check=True)

def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def smali_dirs(root: Path) -> list[Path]:
    return sorted(p for p in root.glob("smali*") if p.is_dir())

def locate_smali(root: Path, rel: str) -> Path | None:
    for directory in smali_dirs(root):
        candidate = directory / rel
        if candidate.is_file():
            return candidate
    return None

def verify_base_features(base: Path) -> None:
    missing = [rel for rel in PRESERVED_CLASSES if locate_smali(base, rel) is None]
    if missing:
        raise RuntimeError("APK 219 feature classes are missing: " + ", ".join(missing))
    manifest = (base / "AndroidManifest.xml").read_text(encoding="utf-8")
    for marker in (
        "HyMT2SettingsActivity",
        "DisplayProfilesActivity",
        "OcrSettingsActivity",
        # APK 219 auto-initializes ML Kit through this ContentProvider.
        "com.google.mlkit.common.internal.MlKitInitProvider",
    ):
        if marker not in manifest:
            raise RuntimeError("APK 219 manifest is missing " + marker)
    ids = base / "res/values/ids.xml"
    if not ids.is_file() or 'name="container_widget_iv_add_region"' not in ids.read_text(encoding="utf-8"):
        raise RuntimeError("APK 219 is missing the Добавить панель resource ID")
    layouts = list((base / "res").rglob("*.xml"))
    if not any("container_widget_iv_add_region" in p.read_text(encoding="utf-8", errors="ignore") for p in layouts):
        raise RuntimeError("APK 219 is missing the Добавить панель editor control in its resources")
    print("Verified APK 219 editor, add-panel control, display profiles, selection gesture and settings classes.")

def verify_region_editor_fix(root: Path) -> None:
    rel = "com/tap_to_translate/snap_translate/hymt2/TranslationRegionEditor.smali"
    editor = locate_smali(root, rel)
    if editor is None:
        raise RuntimeError("TranslationRegionEditor is missing from the APK")
    source = editor.read_text(encoding="utf-8")
    import re
    match = re.search(r"^\.method [^\n]* addRegion\([^\n]*$", source, re.MULTILINE)
    if match is None:
        raise RuntimeError("TranslationRegionEditor.addRegion() is missing")
    end = source.find(".end method", match.start())
    if end < 0:
        raise RuntimeError("TranslationRegionEditor.addRegion() is unterminated")
    method = source[match.start():end]
    required = (
        "Landroid/graphics/Rect;",
        "Ljava/lang/Class;->getConstructor([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;",
    )
    missing = [item for item in required if item not in method]
    if missing:
        raise RuntimeError(
            "APK 219 add-panel constructor fix is missing from final DEX: "
            + ", ".join(missing)
        )
    if "Ljava/lang/Class;->getDeclaredConstructor()Ljava/lang/reflect/Constructor;" in method:
        raise RuntimeError("Broken zero-argument region-model constructor detected")
    print("Verified APK 219 Rect/String constructor in TranslationRegionEditor.addRegion().")


def smali_class_api(path: Path) -> tuple[str | None, set[str], dict[str, set[str]], dict[str, set[str]]]:
    """Extract hierarchy and callable/member signatures from a smali class."""
    source = path.read_text(encoding="utf-8")
    super_desc = next((line.split()[-1] for line in source.splitlines() if line.startswith(".super ")), None)
    interfaces = {
        line.split()[-1]
        for line in source.splitlines()
        if line.startswith(".implements ")
    }
    methods: dict[str, set[str]] = {}
    fields: dict[str, set[str]] = {}
    for line in source.splitlines():
        stripped = line.strip()
        if stripped.startswith(".method "):
            tokens = stripped.split()
            signature = tokens[-1]
            flags = set(tokens[1:-1])
            methods[signature] = flags
        elif stripped.startswith(".field "):
            tokens = stripped.split()
            member = tokens[-1].split("=", 1)[0]
            flags = set(tokens[1:-1])
            fields[member] = flags
    return super_desc, interfaces, methods, fields


def verify_class_abi(host: Path, helper: Path, label: str) -> None:
    """Ensure a replacement class preserves the base app's callable ABI."""
    host_super, host_interfaces, host_methods, host_fields = smali_class_api(host)
    helper_super, helper_interfaces, helper_methods, helper_fields = smali_class_api(helper)

    if host_super != helper_super:
        raise RuntimeError(
            f"{label} superclass mismatch: base={host_super}, helper={helper_super}"
        )
    missing_interfaces = sorted(host_interfaces - helper_interfaces)
    if missing_interfaces:
        raise RuntimeError(
            f"Helper {label} drops interfaces used by APK 219: "
            + ", ".join(missing_interfaces)
        )

    def is_generated_bridge(signature: str, flags: set[str]) -> bool:
        method_name = signature.split("(", 1)[0]
        return "synthetic" in flags or method_name.startswith("$r8$lambda$")

    missing_methods = sorted(
        signature for signature, flags in host_methods.items()
        if "private" not in flags
        and not is_generated_bridge(signature, flags)
        and signature not in helper_methods
    )
    if missing_methods:
        raise RuntimeError(
            f"Helper {label} is missing methods used by APK 219: "
            + ", ".join(missing_methods)
        )
    incompatible_methods = sorted(
        signature for signature, flags in host_methods.items()
        if "private" not in flags
        and not is_generated_bridge(signature, flags)
        and signature in helper_methods
        and ("static" in flags) != ("static" in helper_methods[signature])
    )
    if incompatible_methods:
        raise RuntimeError(
            f"Helper {label} changes static/instance method ABI: "
            + ", ".join(incompatible_methods)
        )

    missing_fields = sorted(
        member for member, flags in host_fields.items()
        if "private" not in flags and member not in helper_fields
    )
    if missing_fields:
        raise RuntimeError(
            f"Helper {label} is missing fields used by APK 219: "
            + ", ".join(missing_fields)
        )
    incompatible_fields = sorted(
        member for member, flags in host_fields.items()
        if "private" not in flags
        and member in helper_fields
        and ("static" in flags) != ("static" in helper_fields[member])
    )
    if incompatible_fields:
        raise RuntimeError(
            f"Helper {label} changes static/instance field ABI: "
            + ", ".join(incompatible_fields)
        )

    print(
        f"Verified {label} ABI compatibility:",
        len(host_methods), "base methods;",
        len(host_fields), "base fields;",
        len(host_interfaces), "base interfaces."
    )


def verify_llama_wrapper_compatibility(host: Path, helper: Path) -> None:
    verify_class_abi(host, helper, "Llama JNI wrapper")


def copy_runtime_smali(helper: Path, base: Path) -> set[str]:
    # Never inject thousands of helper definitions into APK 219's original
    # DEX files. The previous approach overwrote classes in their original
    # DEX partitions; that pushed classes.dex beyond the 65,535 method-index
    # limit (smali reported index 65,704). Instead append one or more new DEX
    # partitions after the highest base DEX, preserving the helper's own
    # multidex partitioning.
    helper_dirs = smali_dirs(helper)
    base_dirs = smali_dirs(base)
    base_indices = [
        1 if directory.name == "smali" else int(directory.name.removeprefix("smali_classes"))
        for directory in base_dirs
    ]
    next_dex_index = max(base_indices, default=1) + 1
    target_roots = {
        helper_dir: base / f"smali_classes{next_dex_index + index}"
        for index, helper_dir in enumerate(helper_dirs)
    }

    copied = 0
    replaced = 0
    skipped_identical = 0
    bootstrap_replaced: set[str] = set()
    replaced_runtime_prefixes: set[str] = set()
    relocated_classes: set[str] = set()
    replaced_app_families: set[str] = set()
    added_app_families: set[str] = set()

    for helper_dir in helper_dirs:
        target_root = target_roots[helper_dir]
        for prefix in RUNTIME_PREFIXES:
            package_root = helper_dir / prefix
            if not package_root.exists():
                continue
            for source in package_root.rglob("*.smali"):
                rel = source.relative_to(helper_dir)
                target = target_root / rel
                existing = [directory / rel for directory in smali_dirs(base)
                            if (directory / rel).is_file() and directory != target_root]

                # APK 219 contains newer host-side implementations of these
                # editor/UI classes. Never overwrite them with copies from the
                # helper APK. Some preserved classes live in classes5.dex itself,
                # so checking only for a copy in another DEX is insufficient.
                rel_name = rel.as_posix()

                # Only the explicitly selected JNI and app-owned OCR package
                # prefixes are eligible for replacement. SDK package classes
                # from APK 219 are protected by verify_sdk_class_preservation().
                replaceable_prefix = next(
                    (prefix for prefix in REPLACEABLE_RUNTIME_PREFIXES
                     if rel_name.startswith(prefix)),
                    None,
                )
                replaceable_app_family = next(
                    (
                        family for family in REPLACEABLE_APP_RUNTIME_FAMILIES
                        if rel_name == family + ".smali"
                        or rel_name.startswith(family + "$")
                    ),
                    None,
                )
                if (
                    replaceable_app_family in ABI_CHECK_APP_RUNTIME_FAMILIES
                    and rel_name == replaceable_app_family + ".smali"
                    and existing
                ):
                    if len(existing) != 1:
                        raise RuntimeError(
                            "Multiple base definitions found for app runtime class: " + rel_name
                        )
                    verify_class_abi(existing[0], source, replaceable_app_family.rsplit("/", 1)[-1])
                if rel_name.startswith("dev/ffmpegkit/llama/") and existing:
                    if len(existing) != 1:
                        raise RuntimeError(
                            "Multiple base definitions found for Llama JNI wrapper: " + rel_name
                        )
                    verify_llama_wrapper_compatibility(existing[0], source)

                if (
                    rel_name in REPLACEABLE_MLKIT_BOOTSTRAP_CLASSES
                    or replaceable_prefix is not None
                    or replaceable_app_family is not None
                ):
                    # Relocate the matched helper definition into the appended
                    # DEX partition. Delete any old APK 219 definitions rather
                    # than overwriting them in classes.dex/classes2.dex, which
                    # can overflow their 16-bit method_id tables.
                    target.parent.mkdir(parents=True, exist_ok=True)
                    for stale in existing:
                        stale.unlink()
                        print("Relocating host definition out of", stale.parent.parent.name + ":", rel_name)
                    shutil.copy2(source, target)
                    replaced += 1
                    relocated_classes.add(rel_name)
                    if rel_name in REPLACEABLE_MLKIT_BOOTSTRAP_CLASSES:
                        bootstrap_replaced.add(rel_name)
                    if replaceable_prefix is not None:
                        replaced_runtime_prefixes.add(replaceable_prefix)
                    if replaceable_app_family is not None:
                        replaced_app_families.add(replaceable_app_family)
                    print("Placed selected runtime class in", target_root.name + ":", rel_name)
                    continue

                if (
                    existing
                    and any(
                        rel_name.startswith(prefix)
                        for prefix in HOST_PREFERRED_MLKIT_TRANSITIVE_PREFIXES
                    )
                ):
                    print("Keeping APK 219 host runtime implementation:", rel_name)
                    skipped_identical += 1
                    continue

                preserve_exact = rel_name in PRESERVED_CLASSES
                preserve_family = any(
                    rel_name == prefix + ".smali" or rel_name.startswith(prefix + "$")
                    for prefix in PRESERVED_CLASS_FAMILIES
                )
                if (preserve_exact or preserve_family) and (target.is_file() or existing):
                    skipped_identical += 1
                    print("Preserving APK 219 host class:", rel_name)
                    continue

                if target.is_file():
                    shutil.copy2(source, target)
                    replaced += 1
                    continue

                if existing:
                    if any(path.read_bytes() == source.read_bytes() for path in existing):
                        skipped_identical += 1
                        continue
                    # Keep the Kotlin stdlib, coroutines and annotations already shipped
                    # by the original host APK. The helper's copy may differ by compiler
                    # version, but these packages are host-provided dependencies and
                    # must not be duplicated across DEX files.
                    if rel.as_posix().startswith((
                        "kotlin/",
                        "kotlinx/",
                        "org/jetbrains/annotations/",
                        "androidx/annotation/",
                    )):
                        skipped_identical += 1
                        continue
                    # Retain APK 219 ML Kit implementations already used by its
                    # translator. Only classes absent from the base APK are copied.
                    if rel.as_posix().startswith((
                        "com/google/mlkit/common/",
                        "com/google/mlkit/nl/translate/",
                    )):
                        print("Keeping APK 219 ML Kit class on duplicate:", rel.as_posix())
                        skipped_identical += 1
                        continue
                    # For actual app/runtime classes, a differing definition outside
                    # classes5.dex is unsafe: fail instead of creating duplicate classes.
                    raise RuntimeError(
                        f"Runtime class collision outside classes5.dex: {rel.as_posix()}"
                    )

                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
                copied += 1

    # Merge only explicitly approved app class families. The helper contains
    # the entire hymt2 source package, but most of it duplicates classes already
    # integrated into APK 219 and may contain mismatched synthetic lambdas.
    for helper_dir in helper_dirs:
        target_root = target_roots[helper_dir]
        for family in APP_RUNTIME_CLASS_FAMILIES:
            parent = Path(family).parent
            stem = Path(family).name
            package_dir = helper_dir / parent
            if not package_dir.is_dir():
                continue
            sources: list[Path] = []
            exact = package_dir / (stem + ".smali")
            if exact.is_file():
                sources.append(exact)
            sources.extend(sorted(package_dir.glob(stem + "$*.smali")))

            for source in sources:
                rel = source.relative_to(helper_dir)
                rel_name = rel.as_posix()
                target = target_root / rel
                existing = [
                    directory / rel
                    for directory in smali_dirs(base)
                    if (directory / rel).is_file() and directory != target_root
                ]

                replaceable = family in REPLACEABLE_APP_RUNTIME_FAMILIES
                if replaceable:
                    if family in ABI_CHECK_APP_RUNTIME_FAMILIES and rel_name == family + ".smali" and existing:
                        if len(existing) != 1:
                            raise RuntimeError(
                                "Multiple base definitions found for app runtime class: " + rel_name
                            )
                        verify_class_abi(existing[0], source, family.rsplit("/", 1)[-1])
                    target.parent.mkdir(parents=True, exist_ok=True)
                    for stale in existing:
                        stale.unlink()
                        print("Relocating APK 219 app runtime class:", rel_name)
                    shutil.copy2(source, target)
                    relocated_classes.add(rel_name)
                    replaced_app_families.add(family)
                    replaced += 1
                    print("Placed updated app runtime class in", target_root.name + ":", rel_name)
                else:
                    if existing:
                        if len(existing) == 1 and existing[0].read_bytes() == source.read_bytes():
                            skipped_identical += 1
                            added_app_families.add(family)
                            continue
                        raise RuntimeError(
                            "New language-pack class unexpectedly collides with APK 219: "
                            + rel_name
                        )
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(source, target)
                    relocated_classes.add(rel_name)
                    added_app_families.add(family)
                    copied += 1
                    print("Added language-pack class to", target_root.name + ":", rel_name)

    missing_bootstrap = sorted(REPLACEABLE_MLKIT_BOOTSTRAP_CLASSES - bootstrap_replaced)
    if missing_bootstrap:
        raise RuntimeError(
            "Helper APK is missing matching ML Kit bootstrap implementations: "
            + ", ".join(missing_bootstrap)
        )
    helper_runtime_prefixes = {
        prefix
        for helper_dir in smali_dirs(helper)
        for prefix in REPLACEABLE_RUNTIME_PREFIXES
        if (helper_dir / prefix).is_dir()
        and any((helper_dir / prefix).rglob("*.smali"))
    }
    missing_runtime_prefixes = sorted(helper_runtime_prefixes - replaced_runtime_prefixes)
    if missing_runtime_prefixes:
        raise RuntimeError(
            "Helper APK runtime packages were not merged: "
            + ", ".join(missing_runtime_prefixes)
        )
    missing_app_families = sorted(set(REPLACEABLE_APP_RUNTIME_FAMILIES) - replaced_app_families)
    if missing_app_families:
        raise RuntimeError(
            "Helper APK app runtime families were not merged: "
            + ", ".join(missing_app_families)
        )
    missing_added_families = sorted(set(APP_RUNTIME_CLASS_FAMILIES) - set(REPLACEABLE_APP_RUNTIME_FAMILIES) - added_app_families)
    if missing_added_families:
        raise RuntimeError(
            "Helper APK new language-pack class families were not merged: "
            + ", ".join(missing_added_families)
        )
    if copied + replaced == 0:
        raise RuntimeError("No Hy-MT2 runtime classes were found in the helper APK")
    print("No APK 219 ML Kit bootstrap classes were replaced.")
    print(f"Runtime DEX merge: {copied} added, {replaced} relocated, {skipped_identical} identical host classes retained.")
    print("Appended helper DEX partitions:", ", ".join(path.name for path in target_roots.values()))
    return relocated_classes


def verify_sdk_class_preservation(relocated_classes: set[str]) -> None:
    protected_prefixes = (
        "com/google/mlkit/",
        "com/google/firebase/",
        "com/google/android/gms/",
        "androidx/",
        "ai/onnxruntime/",
        "org/opencv/",
    )
    changed = sorted(
        rel for rel in relocated_classes
        if rel.startswith(protected_prefixes)
    )
    if changed:
        raise RuntimeError(
            "Integration attempted to replace APK 219 SDK classes: "
            + ", ".join(changed[:60])
        )
    print(
        "Verified SDK replacement boundary: ML Kit, Firebase, GMS, AndroidX, "
        "ONNX Runtime and OpenCV remain APK 219 implementations."
    )


def snapshot_smali_inventory(root: Path) -> dict[tuple[str, str], str]:
    snapshot: dict[tuple[str, str], str] = {}
    for directory in smali_dirs(root):
        for source in directory.rglob("*.smali"):
            rel = source.relative_to(directory).as_posix()
            snapshot[(directory.name, rel)] = hashlib.sha256(source.read_bytes()).hexdigest()
    return snapshot


def integrate_hymt2_double_tap_cancel(root: Path) -> None:
    # The original floating-icon double-tap listener is obfuscated as lb/b in APK 219.
    # Call the app-owned runtime hook without changing registers or host listener behavior.
    relative = "lb/b.smali"
    path = locate_smali(root, relative)
    if path is None:
        raise RuntimeError("APK 219 floating-icon gesture listener lb/b.smali is missing")
    source = path.read_text(encoding="utf-8")
    signature = re.compile(r"(?m)^\.method [^\n]*\bonDoubleTap\(Landroid/view/MotionEvent;\)Z\s*$")
    match = signature.search(source)
    if match is None:
        raise RuntimeError("Could not locate floating-icon onDoubleTap(MotionEvent)Z handler")
    end = source.find(".end method", match.end())
    if end < 0:
        raise RuntimeError("Floating-icon onDoubleTap handler has no .end method")
    method = source[match.start():end]
    invocation = "    invoke-static {}, Lcom/tap_to_translate/snap_translate/hymt2/HyMT2Runtime;->cancelActiveTranslation()V"
    if invocation not in method:
        locals_match = re.search(r"(?m)^\s*\.(?:locals|registers)\s+\d+\s*$", method)
        if locals_match is None:
            raise RuntimeError("Floating-icon onDoubleTap handler has no register declaration")
        insert_at = match.start() + locals_match.end()
        source = source[:insert_at] + "\n" + invocation + source[insert_at:]
        path.write_text(source, encoding="utf-8")
    verify_hymt2_double_tap_cancel_hook(root)
    print("Integrated Hy-MT2 cancellation into the floating-icon double-tap listener.")


def verify_hymt2_double_tap_cancel_hook(root: Path) -> None:
    path = locate_smali(root, "lb/b.smali")
    if path is None:
        raise RuntimeError("APK 219 floating-icon gesture listener is missing")
    source = path.read_text(encoding="utf-8")
    signature = re.compile(r"(?ms)^\.method [^\n]*\bonDoubleTap\(Landroid/view/MotionEvent;\)Z\s*$.*?^\.end method")
    match = signature.search(source)
    invocation = "invoke-static {}, Lcom/tap_to_translate/snap_translate/hymt2/HyMT2Runtime;->cancelActiveTranslation()V"
    if match is None or invocation not in match.group(0):
        raise RuntimeError("Floating-icon double-tap does not request Hy-MT2 cancellation")


def verify_original_smali_inventory(
    snapshot: dict[tuple[str, str], str],
    root: Path,
    relocated_classes: set[str],
) -> None:
    # All original host classes must remain byte-identical in their original
    # DEX, except classes intentionally relocated to the appended helper DEXes
    # and the two Tesseract hooks inserted by this integration.
    allowed_host_hooks: set[tuple[str, str]] = {("smali_classes2", "lb/b.smali")}
    missing: list[str] = []
    changed: list[str] = []
    for (dex_name, rel), expected_hash in snapshot.items():
        if rel in relocated_classes or (dex_name, rel) in allowed_host_hooks:
            continue
        path = root / dex_name / rel
        if not path.is_file():
            missing.append(f"{dex_name}/{rel}")
        elif hashlib.sha256(path.read_bytes()).hexdigest() != expected_hash:
            changed.append(f"{dex_name}/{rel}")
    if missing or changed:
        raise RuntimeError(
            "APK 219 host DEX preservation failed: "
            + ("missing=" + ", ".join(missing[:40]) if missing else "")
            + ("; " if missing and changed else "")
            + ("changed=" + ", ".join(changed[:40]) if changed else "")
        )
    print(
        "Verified original APK 219 smali preservation; relocated "
        + str(len(relocated_classes))
        + " matched runtime classes and retained the rest of the host code."
    )


def verify_helper_runtime_inventory(helper: Path, root: Path) -> None:
    """Require every helper class in the matched ML Kit/GMS/AndroidX packages.

    This is intentionally inventory-driven: adding a new class to a dependency
    cannot silently disappear merely because its package was omitted from a
    hand-maintained short list.
    """
    expected: dict[str, Path] = {}
    for helper_dir in smali_dirs(helper):
        for prefix in REPLACEABLE_RUNTIME_PREFIXES:
            package_root = helper_dir / prefix
            if not package_root.is_dir():
                continue
            for source in package_root.rglob("*.smali"):
                rel = source.relative_to(helper_dir).as_posix()
                if rel in expected and expected[rel].read_bytes() != source.read_bytes():
                    raise RuntimeError("Helper APK defines the same runtime class more than once: " + rel)
                expected[rel] = source

    # Include explicitly replaceable app runtime class families (currently
    # HyMT2Runtime and its generated nested/continuation classes).
    for family in APP_RUNTIME_CLASS_FAMILIES:
        parent = Path(family).parent
        stem = Path(family).name
        for helper_dir in smali_dirs(helper):
            package_dir = helper_dir / parent
            if not package_dir.is_dir():
                continue
            candidates = []
            exact = package_dir / (stem + ".smali")
            if exact.is_file():
                candidates.append(exact)
            candidates.extend(package_dir.glob(stem + "$*.smali"))
            for source in candidates:
                rel = source.relative_to(helper_dir).as_posix()
                if rel in expected and expected[rel].read_bytes() != source.read_bytes():
                    raise RuntimeError("Helper APK defines app runtime class more than once: " + rel)
                expected[rel] = source

    if not expected:
        raise RuntimeError("Helper APK contains no selected app/runtime classes")

    missing: list[str] = []
    duplicate: list[str] = []
    mismatched: list[str] = []
    for rel, source in sorted(expected.items()):
        matches = [
            directory / rel
            for directory in smali_dirs(root)
            if (directory / rel).is_file()
        ]
        if not matches:
            missing.append(rel)
        elif len(matches) > 1:
            duplicate.append(rel)
        elif matches[0].read_bytes() != source.read_bytes():
            mismatched.append(rel)

    if missing or duplicate or mismatched:
        details = []
        if missing:
            details.append("missing=" + ", ".join(missing[:80]))
        if duplicate:
            details.append("duplicate=" + ", ".join(duplicate[:80]))
        if mismatched:
            details.append("not_helper_version=" + ", ".join(mismatched[:80]))
        raise RuntimeError("Matched dependency inventory verification failed: " + "; ".join(details))

    counts: dict[str, int] = {}
    for rel in expected:
        prefix = next((p for p in REPLACEABLE_RUNTIME_PREFIXES if rel.startswith(p)), None)
        if prefix is None:
            family = next(
                f for f in APP_RUNTIME_CLASS_FAMILIES
                if rel == f + ".smali" or rel.startswith(f + "$")
            )
            prefix = "app-family:" + family
        counts[prefix] = counts.get(prefix, 0) + 1
    print("Verified helper class inventories for selected runtime packages:")
    for prefix, count in sorted(counts.items()):
        print(f"  {prefix}: {count} classes")
    print(f"Verified {len(expected)} selected runtime class definitions byte-for-byte.")


def copy_native_libraries(helper: Path, base: Path) -> None:
    source_dir = helper / "lib/arm64-v8a"
    if not source_dir.is_dir():
        raise RuntimeError("Helper APK has no lib/arm64-v8a directory")
    destination_dir = base / "lib/arm64-v8a"
    destination_dir.mkdir(parents=True, exist_ok=True)

    sources = list(source_dir.glob("*.so"))
    if not sources:
        raise RuntimeError("Helper APK contains no native shared libraries")

    for source in sources:
        destination = destination_dir / source.name
        if destination.exists() and destination.read_bytes() != source.read_bytes():
            if source.name not in REPLACEABLE_NATIVE:
                print("Keeping APK 219 native library on duplicate:", source.name)
                continue
            print("Replacing native runtime:", source.name)
        shutil.copy2(source, destination)

    missing = sorted(name for name in REQUIRED_NATIVE if not (destination_dir / name).is_file())
    if missing:
        raise RuntimeError("Final APK is missing runtime libraries: " + ", ".join(missing))
    print("Verified STQ1_0 CPU + Q4_K_M Vulkan native library set.")

def copy_new_assets_and_services(helper: Path, base: Path) -> None:
    # The new runtime uses model files and OCR assets already present in APK 219.
    # Keep all host META-INF/services descriptors byte-identical as well.
    print("Preserving APK 219 assets and META-INF service registrations unchanged.")

def verify_preserved_payload(base_apk: Path, output_apk: Path) -> None:
    with zipfile.ZipFile(base_apk) as original, zipfile.ZipFile(output_apk) as built:
        old_names = set(original.namelist())
        new_names = set(built.namelist())

        # APK 219's original assets (OCR models, dictionaries, and other resources)
        # must survive byte-for-byte.
        for name in old_names:
            if name.startswith("assets/"):
                if name not in new_names or original.read(name) != built.read(name):
                    raise RuntimeError("APK 219 asset changed or disappeared: " + name)

        # Preserve every pre-existing native library except the explicitly replaced
        # llama.cpp runtime and shared C++/OpenCV compatibility libraries.
        for name in old_names:
            if not name.startswith("lib/"):
                continue
            basename = name.rsplit("/", 1)[-1]
            if basename in REPLACEABLE_NATIVE:
                continue
            if name not in new_names or original.read(name) != built.read(name):
                raise RuntimeError("Unrelated APK 219 native library changed: " + name)

        # Host DEX entries remain present; new selected runtime classes are
        # appended to later DEX partitions without injecting host hooks.

        dex_names = sorted(
            name for name in new_names
            if name == "classes.dex" or (name.startswith("classes") and name.endswith(".dex"))
        )
        if not dex_names:
            raise RuntimeError("Final APK contains no DEX files")
        if "AndroidManifest.xml" not in new_names:
            raise RuntimeError("Final APK has no AndroidManifest.xml")
        manifest = built.read("AndroidManifest.xml")
        if not manifest:
            raise RuntimeError("Final APK AndroidManifest.xml is empty")

        signatures = [
            name for name in new_names
            if name.upper().startswith("META-INF/")
            and name.upper().endswith((".RSA", ".DSA", ".EC", ".SF"))
        ]
        if signatures:
            raise RuntimeError("Final APK unexpectedly contains signing files: " + ", ".join(signatures))

    print("Verified APK 219 assets, original DEX/native payload and unsigned state.")

def verify_dual_model_affinity_runtime(root: Path) -> None:
    required_classes = {
        "HyMT2Runtime": "com/tap_to_translate/snap_translate/hymt2/HyMT2Runtime.smali",
        "HyMT2Settings": "com/tap_to_translate/snap_translate/hymt2/HyMT2Settings.smali",
        "HyMT2SettingsActivity": "com/tap_to_translate/snap_translate/hymt2/HyMT2SettingsActivity.smali",
        "HyMT2PenaltyNative": "com/tap_to_translate/snap_translate/hymt2/HyMT2PenaltyNative.smali",
    }
    contents: dict[str, str] = {}
    for label, relative in required_classes.items():
        path = locate_smali(root, relative)
        if path is None:
            raise RuntimeError(label + " is missing from final APK")
        contents[label] = path.read_text(encoding="utf-8")

    required_markers = {
        "HyMT2Runtime": (
            "STQ1_0", "model_kind", "selectedModelKind", "setSelectedModelUri",
            "cancelActiveTranslation", "translationCancelRequested", "clearHostResult",
        ),
        "HyMT2Settings": ("cpu_affinity_mode", "cpu_affinity_mask", "cpu_affinity_last_report"),
        "HyMT2SettingsActivity": (
            "1.25bit", "stq43", "kind=", "performance", "custom",
            "currentFreqKhz", "currentFreqSource",
        ),
        "HyMT2PenaltyNative": (
            "nativeCpuTopologyJson", "nativeSetCpuAffinity",
            "nativeInstallAbortCallback", "nativeSetAbortRequested",
        ),
    }
    for label, markers in required_markers.items():
        missing = [marker for marker in markers if marker not in contents[label]]
        if missing:
            raise RuntimeError(label + " is missing required model/affinity markers: " + ", ".join(missing))

    if "setSelectedModelUri(Landroid/content/Context;Landroid/net/Uri;Ljava/lang/String;)V" not in contents["HyMT2Runtime"]:
        raise RuntimeError("HyMT2Runtime does not expose the model-kind selection method")
    print("Verified dual-model routing, STQ1_0 model classification and CPU affinity controls.")


def verify_extended_ocr_settings(root: Path) -> None:
    required_classes = {
        "OcrSettingsActivity": "com/tap_to_translate/snap_translate/hymt2/OcrSettingsActivity.smali",
        "PaddleOcrLiveBridge": "com/tap_to_translate/snap_translate/hymt2/ocr/PaddleOcrLiveBridge.smali",
        "PaddleOCRConfig": "com/paddle/ocr/PaddleOCRConfig.smali",
        "EngineConfig": "com/paddle/ocr/EngineConfig.smali",
    }
    contents: dict[str, str] = {}
    for label, relative in required_classes.items():
        path = locate_smali(root, relative)
        if path is None:
            raise RuntimeError(label + " is missing from final APK")
        contents[label] = path.read_text(encoding="utf-8")

    required_ui = (
        "detLimitSideLen", "detLimitType", "detImgMode", "detMaxCandidates",
        "detUseDilation", "detScoreMode", "recBatchSize", "ortThreads",
    )
    required_runtime = (
        "ocr_det_limit_side_len", "ocr_det_limit_type", "ocr_det_img_mode",
        "ocr_det_max_candidates", "ocr_det_use_dilation", "ocr_det_score_mode",
        "ocr_rec_batch_size", "ocr_ort_threads",
    )
    for label, source, markers in (
        ("OcrSettingsActivity", contents["OcrSettingsActivity"], required_ui),
        ("PaddleOcrLiveBridge", contents["PaddleOcrLiveBridge"], required_runtime),
    ):
        missing = [marker for marker in markers if marker not in source]
        if missing:
            raise RuntimeError(label + " is missing extended OCR settings: " + ", ".join(missing))

    if "detMaxCandidates" not in contents["PaddleOCRConfig"] or "recBatchSize" not in contents["PaddleOCRConfig"]:
        raise RuntimeError("PaddleOCRConfig does not expose extended detector/recognizer parameters")
    if "numThreads" not in contents["EngineConfig"]:
        raise RuntimeError("EngineConfig does not expose ONNX Runtime thread control")
    print("Verified extended PP-OCRv6 controls and parameter application.")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-apk", required=True, type=Path)
    parser.add_argument("--helper-apk", required=True, type=Path)
    parser.add_argument("--apktool", required=True, type=Path)
    parser.add_argument("--work", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--zipalign", required=True)
    args = parser.parse_args()

    if not args.base_apk.is_file():
        raise FileNotFoundError(args.base_apk)
    actual_sha = sha256(args.base_apk)
    if actual_sha != BASE_SHA256:
        raise RuntimeError(f"Wrong base APK 219 SHA-256: {actual_sha}")

    args.work.mkdir(parents=True, exist_ok=True)
    base = args.work / "base_decoded"
    helper = args.work / "helper_decoded"
    if base.exists():
        shutil.rmtree(base)
    if helper.exists():
        shutil.rmtree(helper)

    run(["java", "-jar", str(args.apktool), "d", "-f", "-o", str(base), str(args.base_apk)])
    run(["java", "-jar", str(args.apktool), "d", "-f", "-o", str(helper), str(args.helper_apk)])

    verify_base_features(base)
    verify_region_editor_fix(base)
    original_smali_inventory = snapshot_smali_inventory(base)
    relocated_runtime_classes = copy_runtime_smali(helper, base)
    integrate_hymt2_double_tap_cancel(base)
    verify_sdk_class_preservation(relocated_runtime_classes)
    verify_original_smali_inventory(original_smali_inventory, base, relocated_runtime_classes)
    verify_helper_runtime_inventory(helper, base)
    verify_dual_model_affinity_runtime(base)
    verify_hymt2_double_tap_cancel_hook(base)
    verify_extended_ocr_settings(base)
    # No language-pack manager or Tesseract hooks are needed for this integration.
    verify_region_editor_fix(base)
    # ML Kit/Firebase/GMS remain exactly as supplied by APK 219.
    copy_native_libraries(helper, base)
    copy_new_assets_and_services(helper, base)

    # Runtime-only merge: do not reapply the old OCR, panel editor, profile or
    # settings patches because all those features are already present in APK 219.
    unaligned = args.work / "TapTranslate_STQ1_0_APK219_unsigned_unaligned.apk"
    run(["java", "-jar", str(args.apktool), "b", str(base), "-o", str(unaligned)])

    args.output.parent.mkdir(parents=True, exist_ok=True)
    run([args.zipalign, "-f", "-p", "4", str(unaligned), str(args.output)])
    run([args.zipalign, "-c", "-p", "4", str(args.output)])
    verify_preserved_payload(args.base_apk, args.output)

    # Decode the actual output again and inspect the class that Android will run.
    final_decoded = args.work / "final_decoded"
    if final_decoded.exists():
        shutil.rmtree(final_decoded)
    run(["java", "-jar", str(args.apktool), "d", "-f", "-o", str(final_decoded), str(args.output)])
    verify_region_editor_fix(final_decoded)
    verify_sdk_class_preservation(relocated_runtime_classes)
    verify_original_smali_inventory(original_smali_inventory, final_decoded, relocated_runtime_classes)
    verify_helper_runtime_inventory(helper, final_decoded)
    verify_dual_model_affinity_runtime(final_decoded)
    verify_hymt2_double_tap_cancel_hook(final_decoded)
    verify_extended_ocr_settings(final_decoded)
    # ML Kit/Firebase/GMS remain exactly as supplied by APK 219.
    print("Base APK SHA-256:", actual_sha)
    print("Unsigned output SHA-256:", sha256(args.output))
    print(f"Unsigned APK created: {args.output} ({args.output.stat().st_size} bytes)")
    return 0

if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print("ERROR:", exc, file=sys.stderr)
        raise
