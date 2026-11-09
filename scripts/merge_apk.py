#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID_NS = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID_NS)

ENGINE_NAME = "Hy-MT2 Offline"
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
    settings = base / "smali_classes3/com/tap_to_translate/snap_translate/domain/main/activitys/SettingsServiceActivity.smali"
    text = settings.read_text(encoding="utf-8")
    if "hymt2_model_manager_row" not in text and "0x7f0a04f1" not in text:
        anchor = '''    invoke-virtual {p1, v0}, Landroid/view/View;->setOnClickListener(Landroid/view/View$OnClickListener;)V

    iget-object p1, p0, Lcom/tap_to_translate/snap_translate/domain/main/activitys/SettingsServiceActivity;->k:Landroid/widget/ImageView;'''
        replacement = '''    invoke-virtual {p1, v0}, Landroid/view/View;->setOnClickListener(Landroid/view/View$OnClickListener;)V

    const p1, 0x7f0a04f1

    invoke-virtual {p0, p1}, Landroidx/appcompat/app/AppCompatActivity;->findViewById(I)Landroid/view/View;

    move-result-object p1

    new-instance v0, Llh/m;

    invoke-direct {v0, p0}, Llh/m;-><init>(Landroid/app/Activity;)V

    invoke-virtual {p1, v0}, Landroid/view/View;->setOnClickListener(Landroid/view/View$OnClickListener;)V

    iget-object p1, p0, Lcom/tap_to_translate/snap_translate/domain/main/activitys/SettingsServiceActivity;->k:Landroid/widget/ImageView;'''
        if anchor not in text:
            raise RuntimeError("Could not find SettingsServiceActivity listener insertion point")
        text = text.replace(anchor, replacement, 1)
        settings.write_text(text, encoding="utf-8")

    click = base / "smali_classes3/lh/m.smali"
    if not click.exists():
        click.write_text('''.class public final Llh/m;
.super Ljava/lang/Object;

# interfaces
.implements Landroid/view/View$OnClickListener;

# instance fields
.field private final a:Landroid/app/Activity;

# direct methods
.method public constructor <init>(Landroid/app/Activity;)V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    iput-object p1, p0, Llh/m;->a:Landroid/app/Activity;

    return-void
.end method

# virtual methods
.method public final onClick(Landroid/view/View;)V
    .locals 3

    iget-object v0, p0, Llh/m;->a:Landroid/app/Activity;

    new-instance v1, Landroid/content/Intent;

    const-class v2, Lcom/tap_to_translate/snap_translate/hymt2/HyMT2SettingsActivity;

    invoke-direct {v1, v0, v2}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V

    invoke-virtual {v0, v1}, Landroid/app/Activity;->startActivity(Landroid/content/Intent;)V

    return-void
.end method
''', encoding="utf-8")


def patch_resources(base: Path) -> None:
    # Add the new settings row.
    layout = base / "res/layout/activity_settings_service.xml"
    text = layout.read_text(encoding="utf-8")
    if ROW_ID_NAME not in text:
        anchor = '                    <com.tap_to_translate.snap_translate.domain.main.widgets.custom.WidgetChooseLanguage'
        row = f'''                    <LinearLayout android:gravity="center" android:orientation="horizontal" android:id="@id/{ROW_ID_NAME}" android:background="@drawable/bg_spinner_language" android:paddingTop="5.0dip" android:paddingBottom="5.0dip" android:focusable="true" android:clickable="true" android:layout_width="fill_parent" android:layout_height="45.0dip" android:layout_marginLeft="15.0dip" android:layout_marginTop="15.0dip" android:layout_marginRight="15.0dip" android:foreground="@drawable/fg_efftect_8" android:paddingVertical="5.0dip">
                        <LinearLayout android:gravity="center|left" android:orientation="vertical" android:layout_width="0.0dip" android:layout_height="wrap_content" android:layout_marginLeft="20.0dip" android:layout_weight="1.0">
                            <TextView android:theme="@style/fontSemi" android:textSize="17.0dip" android:textColor="@color/cl_text_word" android:gravity="center|left" android:layout_width="fill_parent" android:layout_height="wrap_content" android:text="Hy-MT2 Offline · GGUF model settings" android:includeFontPadding="false" />
                        </LinearLayout>
                        <ImageView android:layout_width="11.0dip" android:layout_height="11.0dip" android:layout_marginRight="20.0dip" android:src="@drawable/ic_more_dropdown_svg" />
                    </LinearLayout>
'''
        if anchor not in text:
            raise RuntimeError("Could not find insertion point in activity_settings_service.xml")
        text = text.replace(anchor, row + anchor, 1)
        layout.write_text(text, encoding="utf-8")

    ids = base / "res/values/ids.xml"
    id_text = ids.read_text(encoding="utf-8")
    if ROW_ID_NAME not in id_text:
        item = f'    <item type="id" name="{ROW_ID_NAME}" />\n'
        anchor = '    <item type="id" name="home_wcv_font_size" />\n'
        if anchor not in id_text:
            raise RuntimeError("Could not find end of ids.xml for new view ID")
        id_text = id_text.replace(anchor, anchor + item, 1)
        ids.write_text(id_text, encoding="utf-8")

    public = base / "res/values/public.xml"
    pub_text = public.read_text(encoding="utf-8")
    if ROW_ID_NAME not in pub_text:
        item = f'    <public type="id" name="{ROW_ID_NAME}" id="{ROW_ID}" />\n'
        anchor = '    <public type="id" name="home_wcv_font_size" id="0x7f0a04f0" />\n'
        if anchor not in pub_text:
            raise RuntimeError("Could not find end of public id table")
        pub_text = pub_text.replace(anchor, anchor + item, 1)
        public.write_text(pub_text, encoding="utf-8")


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
    for helper in helper_dirs:
        for prefix in rel_prefixes:
            root = helper / prefix
            if not root.exists():
                continue
            for source in root.rglob("*.smali"):
                rel = source.relative_to(helper).as_posix()
                if rel in existing:
                    continue
                destination = target_root / rel
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, destination)
                existing.add(rel)
                copied += 1
    return copied


def copy_helper_payload(helper: Path, base: Path) -> None:
    helper_dirs = [p for p in helper.glob("smali*") if p.is_dir()]
    prefixes = (
        "com/tap_to_translate/snap_translate/hymt2",
        "dev/ffmpegkit/llama",
        "kotlinx",
        "kotlin",
        "org/jetbrains/annotations",
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
    for source in so_files:
        destination = out_lib / source.name
        if destination.exists() and destination.read_bytes() != source.read_bytes():
            raise RuntimeError(f"Native library collision: {source.name}")
        shutil.copy2(source, destination)
    print("Copied native libraries:", ", ".join(sorted(p.name for p in so_files)))

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

    copy_helper_payload(helper, base)
    patch_atomic_backport_shims(base)
    patch_engine_list(base)
    patch_dispatcher(base)
    patch_picker_callbacks(base)
    patch_settings_click(base)
    patch_resources(base)
    patch_manifest(base)

    unaligned = args.work / "TapTranslate_HyMT2_unsigned_unaligned.apk"
    run(["java", "-jar", str(args.apktool), "b", str(base), "-o", str(unaligned)])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    run([args.zipalign, "-f", "-p", "4", str(unaligned), str(args.output)])
    run([args.zipalign, "-c", "-p", "4", str(args.output)])
    print(f"Unsigned APK created: {args.output} ({args.output.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise
