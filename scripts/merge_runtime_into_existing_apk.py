#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

BASE_SHA256 = "0e0d30f30a98fa427a62d23c6fe670e9bd9781287da2515b1b8139f9332e82eb"

# These four classes form the ML Kit 18.11 bootstrap path used by the
# language-pack manager. APK 219 contains older copies of the same class names.
# Keeping those old definitions while adding 18.11 APIs leaves MlKitContext
# uninitialized. Replace the existing class definitions in-place with the
# helper's matching implementations; do not create duplicate DEX definitions.
REPLACEABLE_MLKIT_BOOTSTRAP_CLASSES = {
    "com/google/mlkit/common/internal/MlKitInitProvider.smali",
    "com/google/mlkit/common/internal/MlKitComponentDiscoveryService.smali",
    "com/google/mlkit/common/internal/CommonComponentRegistrar.smali",
    "com/google/mlkit/nl/translate/NaturalLanguageTranslateRegistrar.smali",
}

RUNTIME_PREFIXES = (
    "com/tap_to_translate/snap_translate/hymt2",
    # The APK already contains some ML Kit internals, but not the public
    # RemoteModelManager APIs used by the language-pack manager.
    "com/google/mlkit/common",
    "com/google/mlkit/nl/translate",
    # ML Kit Common 18.11.0 bootstrap and telemetry transitive dependencies.
    "com/google/firebase/components",
    "com/google/firebase/encoders",
    "com/google/android/datatransport",
    "androidx",
    # TranslateLanguage references implementation classes in this internal package.
    # Transitive Google Play Services runtime required by ML Kit Common 18.11.0.
    "com/google/android/gms/common",
    "com/google/android/gms/dynamic",
    "com/google/android/gms/internal/common",
    "com/google/android/gms/tasks",
    "com/google/android/gms/internal/tasks",
    "com/google/android/gms/internal/mlkit_common",

    "com/google/android/gms/internal/mlkit_translate",
    "dev/ffmpegkit/llama",
    "kotlinx",
    "kotlin",
    "org/jetbrains/annotations",
    "ai/onnxruntime",
    "com/paddle/ocr",
    "org/opencv",
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
    "libopencv_java4.so",
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

def integrate_language_pack_manager(base: Path) -> None:
    # The helper manifest is intentionally not merged wholesale. Register only
    # the new manager Activity in the verified APK 219 manifest.
    manifest_path = base / "AndroidManifest.xml"
    manifest = manifest_path.read_text(encoding="utf-8")
    activity = (
        '        <activity android:name="com.tap_to_translate.snap_translate.hymt2.LanguagePackManagerActivity" '
        'android:exported="false" android:configChanges="orientation|screenSize|keyboardHidden" />\n'
    )
    if "LanguagePackManagerActivity" not in manifest:
        if "</application>" not in manifest:
            raise RuntimeError("APK 219 manifest has no application closing tag")
        manifest = manifest.replace("</application>", activity + "    </application>", 1)
        manifest_path.write_text(manifest, encoding="utf-8")

    # Restore a saved traineddata file before the original host decides to
    # start another network download.
    availability = locate_smali(base, "ca/b2.smali")
    if availability is None:
        raise RuntimeError("APK 219 Tesseract availability check ca/b2.smali is missing")
    source = availability.read_text(encoding="utf-8")
    signature = ".method public static l(Ljava/lang/String;)Z"
    start = source.find(signature)
    if start < 0:
        raise RuntimeError("Could not locate ca/b2.l(String) Tesseract availability method")
    locals_start = source.find(".locals", start)
    if locals_start < 0:
        raise RuntimeError("ca/b2.l(String) has no local-register declaration")
    body_start = source.find("\n", locals_start) + 1
    if "TesseractLanguagePackStore;->beforeLanguageCheck" not in source[start:]:
        source = source[:body_start] + (
            "    invoke-static {p0}, Lcom/tap_to_translate/snap_translate/hymt2/TesseractLanguagePackStore;->beforeLanguageCheck(Ljava/lang/String;)V\n\n"
        ) + source[body_start:]
        availability.write_text(source, encoding="utf-8")

    # Persist the completed host download to the SAF folder. The hook is
    # placed only on the successful path after Content-Length validation.
    downloader = locate_smali(base, "vh/a.smali")
    if downloader is None:
        raise RuntimeError("APK 219 Tesseract downloader vh/a.smali is missing")
    source = downloader.read_text(encoding="utf-8")
    marker = "    :catch_4\n    invoke-virtual {v5}, Ljava/net/HttpURLConnection;->disconnect()V"
    if "TesseractLanguagePackStore;->persistDownloadedPack" not in source:
        if marker not in source:
            raise RuntimeError("Could not locate successful Tesseract download completion in vh/a.smali")
        hook = (
            "    move-object/from16 v0, v16\n"
            "    invoke-virtual {v0}, Ljava/io/File;->getAbsolutePath()Ljava/lang/String;\n"
            "    move-result-object v0\n"
            "    invoke-static {v3, v0}, Lcom/tap_to_translate/snap_translate/hymt2/TesseractLanguagePackStore;->persistDownloadedPack(Ljava/lang/String;Ljava/lang/String;)V\n\n"
        )
        source = source.replace(marker, "    :catch_4\n" + hook + "    invoke-virtual {v5}, Ljava/net/HttpURLConnection;->disconnect()V", 1)
        downloader.write_text(source, encoding="utf-8")

    # Fail fast if either host hook or the Activity registration was not applied.
    manifest = manifest_path.read_text(encoding="utf-8")
    if "LanguagePackManagerActivity" not in manifest:
        raise RuntimeError("LanguagePackManagerActivity was not registered in APK 219 manifest")
    if "TesseractLanguagePackStore;->beforeLanguageCheck" not in availability.read_text(encoding="utf-8"):
        raise RuntimeError("Tesseract restore hook was not installed")
    if "TesseractLanguagePackStore;->persistDownloadedPack" not in downloader.read_text(encoding="utf-8"):
        raise RuntimeError("Tesseract download persistence hook was not installed")
    print("Integrated language-pack manager Activity and both Tesseract host hooks.")


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


def copy_runtime_smali(helper: Path, base: Path) -> None:
    target_root = base / "smali_classes5"
    target_root.mkdir(parents=True, exist_ok=True)
    copied = 0
    replaced = 0
    skipped_identical = 0
    bootstrap_replaced: set[str] = set()

    for helper_dir in smali_dirs(helper):
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

                # The base APK's ML Kit bootstrap classes are from an older runtime.
                # Replacing only missing ML Kit APIs leaves the old provider and
                # registrar wired to the old initialization path. Replace these
                # four definitions at their existing DEX location, never duplicate.
                if rel_name in REPLACEABLE_MLKIT_BOOTSTRAP_CLASSES:
                    candidates = list(existing)
                    if target.is_file():
                        candidates.append(target)
                    if len(candidates) > 1:
                        raise RuntimeError(
                            "Multiple DEX definitions found for ML Kit bootstrap class: "
                            + rel_name + " -> " + ", ".join(str(p) for p in candidates)
                        )
                    destination = candidates[0] if candidates else target
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(source, destination)
                    replaced += 1
                    bootstrap_replaced.add(rel_name)
                    print("Replacing APK 219 ML Kit bootstrap class with matching helper version:", rel_name)
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
                    )):
                        skipped_identical += 1
                        continue
                    # Retain APK 219 ML Kit implementations already used by its
                    # translator. Only classes absent from the base APK are copied.
                    if rel.as_posix().startswith((
                        "com/google/mlkit/common/",
                        "com/google/mlkit/nl/translate/",
                        "com/google/android/gms/common/",
                        "com/google/android/gms/dynamic/",
                        "com/google/android/gms/internal/common/",
                        "com/google/android/gms/tasks/",
                        "com/google/android/gms/internal/tasks/",
                        "com/google/android/gms/internal/mlkit_common/",

                        "com/google/android/gms/internal/mlkit_translate/",
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

    missing_bootstrap = sorted(REPLACEABLE_MLKIT_BOOTSTRAP_CLASSES - bootstrap_replaced)
    if missing_bootstrap:
        raise RuntimeError(
            "Helper APK is missing matching ML Kit bootstrap implementations: "
            + ", ".join(missing_bootstrap)
        )
    if copied + replaced == 0:
        raise RuntimeError("No Hy-MT2 runtime classes were found in the helper APK")
    print("Verified replacement of all ML Kit 18.11 bootstrap classes:", ", ".join(sorted(bootstrap_replaced)))
    print(f"Runtime DEX merge: {copied} added, {replaced} replaced, {skipped_identical} identical host classes retained.")


def verify_mlkit_model_manager_classes(root: Path) -> None:
    # The verified APK 219 manifest has MlKitInitProvider. The manager must reuse
    # that initialized singleton, never call MlKit.initialize() a second time.
    activity = locate_smali(
        root,
        "com/tap_to_translate/snap_translate/hymt2/LanguagePackManagerActivity.smali",
    )
    if activity is None:
        raise RuntimeError("LanguagePackManagerActivity is missing from the APK")
    activity_source = activity.read_text(encoding="utf-8")
    if "Lcom/google/mlkit/common/MlKit;->initialize(Landroid/content/Context;)V" in activity_source:
        raise RuntimeError("LanguagePackManagerActivity must not manually initialize ML Kit; APK 219 has MlKitInitProvider")

    provider = locate_smali(
        root,
        "com/google/mlkit/common/internal/MlKitInitProvider.smali",
    )
    if provider is None:
        raise RuntimeError("ML Kit 18.11 MlKitInitProvider is missing")
    provider_source = provider.read_text(encoding="utf-8")
    if "Lgf/g;->d(" in provider_source:
        raise RuntimeError("Legacy APK 219 MlKitInitProvider implementation was not replaced")
    if not (
        "Lcom/google/mlkit/common/sdkinternal/MlKitContext;->" in provider_source
        or "Lcom/google/mlkit/common/MlKit;->initialize(" in provider_source
    ):
        raise RuntimeError(
            "MlKitInitProvider does not call the ML Kit 18.11 initialization path"
        )

    required = (
        "com/google/mlkit/common/internal/MlKitInitProvider.smali",
        "com/google/mlkit/common/internal/MlKitComponentDiscoveryService.smali",
        # MlKitInitProvider calls ComponentDiscovery before it can initialize MlKitContext.
        "com/google/firebase/components/ComponentDiscovery.smali",
        "com/google/firebase/components/ComponentDiscoveryService.smali",
        "com/google/firebase/components/ComponentRegistrar.smali",
        # ML Kit Common telemetry dependencies are resolved from these namespaces.
        "com/google/firebase/encoders/Encoder.smali",
        "com/google/android/datatransport/TransportFactory.smali",
        "com/google/mlkit/common/internal/CommonComponentRegistrar.smali",
        "com/google/mlkit/nl/translate/NaturalLanguageTranslateRegistrar.smali",
        "com/google/mlkit/common/model/RemoteModelManager.smali",
        "com/google/mlkit/common/model/DownloadConditions.smali",
        "com/google/mlkit/common/model/DownloadConditions$Builder.smali",
        "com/google/mlkit/common/model/RemoteModel.smali",
        "com/google/mlkit/nl/translate/TranslateRemoteModel.smali",
        "com/google/mlkit/nl/translate/TranslateRemoteModel$Builder.smali",
        "com/google/mlkit/nl/translate/TranslateLanguage.smali",
        # TranslateLanguage.<clinit> in ML Kit Translate 17.0.3 references this class.
        "com/google/android/gms/internal/mlkit_common/zza.smali",
        "com/google/android/gms/internal/mlkit_common/zzaf.smali",
        "com/google/android/gms/internal/mlkit_common/zzah.smali",
        "com/google/android/gms/internal/mlkit_common/zzai.smali",
        "com/google/android/gms/internal/mlkit_common/zzav.smali",
        "com/google/android/gms/internal/mlkit_common/zzaw.smali",
        "com/google/android/gms/internal/mlkit_common/zzh.smali",
        "com/google/android/gms/internal/mlkit_common/zzi.smali",
        "com/google/android/gms/internal/mlkit_common/zzlm.smali",
        "com/google/android/gms/internal/mlkit_common/zzlo.smali",
        "com/google/android/gms/internal/mlkit_common/zzmh.smali",
        "com/google/android/gms/internal/mlkit_common/zzmj.smali",
        "com/google/android/gms/internal/mlkit_common/zzmm.smali",
        "com/google/android/gms/internal/mlkit_common/zzmn.smali",
        "com/google/android/gms/internal/mlkit_common/zzmp.smali",
        "com/google/android/gms/internal/mlkit_common/zzmu.smali",
        "com/google/android/gms/internal/mlkit_common/zzmv.smali",
        "com/google/android/gms/internal/mlkit_common/zzmw.smali",
        "com/google/android/gms/internal/mlkit_common/zzna.smali",
        "com/google/android/gms/internal/mlkit_common/zzne.smali",
        "com/google/android/gms/internal/mlkit_common/zzq.smali",
        "com/google/android/gms/internal/mlkit_common/zzr.smali",
        "com/google/android/gms/internal/mlkit_common/zzrr.smali",
        "com/google/android/gms/internal/mlkit_common/zzry.smali",
        "com/google/android/gms/internal/mlkit_common/zzsh.smali",
        "com/google/android/gms/internal/mlkit_common/zzsi.smali",
        "com/google/android/gms/internal/mlkit_common/zzsj.smali",
        "com/google/android/gms/internal/mlkit_common/zzsk.smali",
        "com/google/android/gms/internal/mlkit_common/zzss.smali",
        "com/google/android/gms/internal/mlkit_common/zzu.smali",
        # ML Kit Common RemoteModelManager requires Google Play Services Basement.
        "com/google/android/gms/common/internal/Preconditions.smali",
        "com/google/android/gms/tasks/TaskExecutors.smali",
        "com/google/android/gms/tasks/zzu.smali",
        "com/google/android/gms/internal/tasks/zza.smali",
        "com/google/android/gms/internal/mlkit_translate/zzw.smali",

    )
    missing = [rel for rel in required if locate_smali(root, rel) is None]
    if missing:
        raise RuntimeError(
            "ML Kit language-manager API classes are missing from the APK: "
            + ", ".join(missing)
        )
    print("Verified ML Kit manager APIs and builder classes in the APK.")


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
    helper_assets = helper / "assets"
    added_assets = 0
    if helper_assets.is_dir():
        for source in helper_assets.rglob("*"):
            if not source.is_file():
                continue
            rel = source.relative_to(helper_assets)
            destination = base / "assets" / rel
            destination.parent.mkdir(parents=True, exist_ok=True)
            if destination.exists():
                if destination.read_bytes() != source.read_bytes():
                    raise RuntimeError("Helper asset conflicts with APK 219: " + rel.as_posix())
                continue
            shutil.copy2(source, destination)
            added_assets += 1

    helper_services = helper / "unknown/META-INF/services"
    base_services = base / "unknown/META-INF/services"
    merged_services = 0
    if helper_services.is_dir():
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
                lines = list(dict.fromkeys(
                    line for line in (old + "\n" + new).splitlines() if line.strip()
                ))
                destination.write_text("\n".join(lines) + "\n", encoding="utf-8")
            else:
                shutil.copy2(source, destination)
            merged_services += 1
    print(f"Preserved APK 219 assets; added {added_assets} new helper assets and merged {merged_services} service files.")

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

        # APK 219 ca/b2 is in classes2.dex; vh/a is in classes3.dex.
        # Both are intentionally rebuilt because each receives one Tesseract hook.
        hook_symbols_by_dex = {
            "classes2.dex": (b"beforeLanguageCheck",),
            "classes3.dex": (b"persistDownloadedPack",),
        }
        target_hook_dexes = set(hook_symbols_by_dex)

        # Every original DEX other than the actual hook targets and classes5.dex
        # must remain byte-identical.
        for name in old_names:
            if not (name.startswith("classes") and name.endswith(".dex")):
                continue
            if name == "classes5.dex" or name in target_hook_dexes:
                continue
            if name not in new_names or original.read(name) != built.read(name):
                raise RuntimeError("Unrelated DEX changed during runtime merge: " + name)

        for dex_name, symbols in hook_symbols_by_dex.items():
            if dex_name not in old_names or dex_name not in new_names:
                raise RuntimeError("APK 219 Tesseract host DEX is missing: " + dex_name)
            if original.read(dex_name) == built.read(dex_name):
                raise RuntimeError("Expected Tesseract hook did not modify " + dex_name)
            merged_dex = built.read(dex_name)
            for symbol in symbols:
                if symbol not in merged_dex:
                    raise RuntimeError(
                        "Final " + dex_name + " is missing Tesseract hook: "
                        + symbol.decode("ascii")
                    )

        if "classes5.dex" not in new_names:
            raise RuntimeError("Final APK has no classes5.dex")
        manager_dex = built.read("classes5.dex")
        if b"LanguagePackManagerActivity" not in manager_dex:
            raise RuntimeError("LanguagePackManagerActivity is missing from final classes5.dex")

        if "AndroidManifest.xml" not in new_names:
            raise RuntimeError("Final APK has no AndroidManifest.xml")
        manifest = built.read("AndroidManifest.xml")
        # AndroidManifest.xml is binary AXML in the final APK. Apktool/aapt may
        # encode its string pool as UTF-8 or UTF-16, so check all valid encodings
        # rather than treating a UTF-8-only miss as a missing Activity.
        activity_name = "LanguagePackManagerActivity"
        encoded_names = (
            activity_name.encode("utf-8"),
            activity_name.encode("utf-16le"),
            activity_name.encode("utf-16be"),
        )
        if not any(value in manifest for value in encoded_names):
            raise RuntimeError("LanguagePackManagerActivity is not registered in final manifest")

        signatures = [
            name for name in new_names
            if name.upper().startswith("META-INF/")
            and name.upper().endswith((".RSA", ".DSA", ".EC", ".SF"))
        ]
        if signatures:
            raise RuntimeError("Final APK unexpectedly contains signing files: " + ", ".join(signatures))

    print("Verified APK 219 assets, original non-target DEX/native payload, Tesseract hooks, manager Activity and unsigned state.")

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
    copy_runtime_smali(helper, base)
    integrate_language_pack_manager(base)
    verify_region_editor_fix(base)
    verify_mlkit_model_manager_classes(base)
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
    verify_mlkit_model_manager_classes(final_decoded)
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
