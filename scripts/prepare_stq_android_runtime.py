#!/usr/bin/env python3
"""Validate the STQ-enabled llama.cpp checkout and enable Vulkan in llama-android."""
from __future__ import annotations
import argparse
import re
from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    data = path.read_text(encoding="utf-8")
    count = data.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor in {path}, found {count}")
    path.write_text(data.replace(old, new, 1), encoding="utf-8")
    print(f"Patched {label}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--llama-root", required=True, type=Path)
    ap.add_argument("--android-wrapper-root", required=True, type=Path)
    args = ap.parse_args()
    llama, wrapper = args.llama_root, args.android_wrapper_root

    header = (llama / "ggml/include/ggml.h").read_text(encoding="utf-8")
    if not re.search(r"GGML_TYPE_STQ1_0\s*=\s*42,", header):
        raise SystemExit("Expected Tencent-compatible GGML_TYPE_STQ1_0 = 42")
    if "GGML_TYPE_COUNT" not in header:
        raise SystemExit("GGML type enum missing")
    common = (llama / "ggml/src/ggml-common.h").read_text(encoding="utf-8")
    if "block_stq1_0" not in common:
        raise SystemExit("STQ1_0 block layout missing")
    cpu = (llama / "ggml/src/ggml-cpu/ggml-cpu.c").read_text(encoding="utf-8")
    if "[GGML_TYPE_STQ1_0]" not in cpu or "ggml_vec_dot_stq1_0_q8_K" not in cpu:
        raise SystemExit("STQ1_0 CPU type traits/kernel registration missing")
    generic = (llama / "ggml/src/ggml-cpu/quants.c").read_text(encoding="utf-8")
    if "ggml_vec_dot_stq1_0_q8_K_generic" not in generic:
        raise SystemExit("STQ1_0 generic CPU fallback missing")

    gradle = wrapper / "library/build.gradle.kts"
    replace_once(
        gradle,
        '                    "-DGGML_VULKAN=OFF",   // Free = CPU/NEON only (Pro enables Vulkan)',
        '                    "-DGGML_VULKAN=ON",    // Vulkan retained for Q4 models; STQ selects CPU',
        "enable Vulkan in Gradle native arguments",
    )
    replace_once(
        gradle,
        '                    "-DLLAMA_CURL=OFF",    // no libcurl dependency',
        '                    "-DLLAMA_CURL=OFF",    // no libcurl dependency\n                    "-DGGML_BACKEND_DL=OFF", // link Vulkan/CPU backends directly',
        "disable dynamic backend loading",
    )

    cmake = wrapper / "library/src/main/jni/CMakeLists.txt"
    replace_once(
        cmake,
        'set(GGML_VULKAN          OFF CACHE BOOL "" FORCE)   # Pro flips this ON',
        'set(GGML_VULKAN          ON  CACHE BOOL "" FORCE)   # Q4 uses Vulkan; STQ is CPU-only',
        "enable Vulkan in native CMake",
    )
    replace_once(
        cmake,
        'set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)',
        'set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)\nset(GGML_BACKEND_DL      OFF CACHE BOOL "" FORCE)',
        "disable dynamic backend loading in native CMake",
    )
    print("STQ1_0 id 42 and generic CPU kernel verified; Vulkan enabled for Q4.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
