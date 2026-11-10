#!/usr/bin/env python3
"""Patch the pinned llama-android wrapper for STQ1_0 + Vulkan.

The Tencent Hy-MT2 1.25-bit GGUF published in May 2026 uses tensor type id
42. The current STQ1_0 PR uses id 43 because Q2_0 occupies 42. For this
app's supported model set (Q4_K_M and this STQ model), reserve 42 for STQ1_0
and move Q2_0 to 43. The standard Q4 tensor ids are unchanged.
"""
from __future__ import annotations

import argparse
import re
from pathlib import Path


def replace_once(path: Path, pattern: str, replacement: str, label: str, *, regex: bool = False) -> None:
    text = path.read_text(encoding="utf-8")
    if regex:
        updated, count = re.subn(pattern, replacement, text, count=1, flags=re.MULTILINE)
    else:
        count = text.count(pattern)
        updated = text.replace(pattern, replacement, 1)
    if count != 1:
        raise SystemExit(f"{label}: expected one match in {path}, found {count}")
    path.write_text(updated, encoding="utf-8")
    print(f"Patched {label}: {path}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--llama-root", required=True, type=Path)
    parser.add_argument("--android-wrapper-root", required=True, type=Path)
    args = parser.parse_args()

    llama = args.llama_root
    wrapper = args.android_wrapper_root

    # Tencent's released Hy-MT2 1.25-bit GGUF uses STQ1_0 tensor id 42.
    replace_once(
        llama / "ggml/include/ggml.h",
        r"(GGML_TYPE_Q1_0\s*=\s*41,\s*)GGML_TYPE_Q2_0\s*=\s*42,\s*GGML_TYPE_STQ1_0\s*=\s*43,",
        r"\1GGML_TYPE_STQ1_0  = 42,\n        GGML_TYPE_Q2_0    = 43,",
        "legacy Tencent STQ tensor id 42",
        regex=True,
    )
    constants = llama / "gguf-py/gguf/constants.py"
    replace_once(
        constants,
        r"(^\s*Q1_0\s*=\s*41\s*\n)\s*Q2_0\s*=\s*42\s*\n\s*STQ1_0\s*=\s*43",
        r"\1    STQ1_0 = 42\n    Q2_0 = 43",
        "GGUF Python enum compatibility",
        regex=True,
    )

    # Keep the original Android wrapper Kotlin/JNI API, but compile it against
    # the STQ-enabled llama.cpp submodule and retain the Vulkan backend for Q4.
    gradle = wrapper / "library/build.gradle.kts"
    replace_once(
        gradle,
        '"-DGGML_VULKAN=OFF",',
        '"-DGGML_VULKAN=ON",\n                    "-DVulkan_INCLUDE_DIR=\${System.getenv("VULKAN_HEADERS_INCLUDE_DIR")}",\n                    "-DVulkan_GLSLC_EXECUTABLE=\${System.getenv("VULKAN_GLSLC_EXECUTABLE")}",\n                    "-DSPIRV-Headers_DIR=\${System.getenv("SPIRV_HEADERS_CMAKE_DIR")}",',
        "enable Vulkan and pass Android shader toolchain paths",
    )

    cmake = wrapper / "library/src/main/jni/CMakeLists.txt"
    replace_once(
        cmake,
        'set(GGML_VULKAN          OFF CACHE BOOL "" FORCE)   # Pro flips this ON',
        'set(GGML_VULKAN          ON  CACHE BOOL "" FORCE)   # STQ build keeps Vulkan for Q4 models',
        "enable Vulkan in native CMake",
    )

    # Guard against accidentally preparing a runtime with the upstream id 43.
    header = (llama / "ggml/include/ggml.h").read_text(encoding="utf-8")
    if not re.search(r"GGML_TYPE_STQ1_0\s*=\s*42,", header):
        raise SystemExit("STQ1_0 is not assigned Tencent's legacy GGUF id 42")
    if not re.search(r"GGML_TYPE_Q2_0\s*=\s*43,", header):
        raise SystemExit("Q2_0 was not moved to id 43")
    if "GGML_TYPE_COUNT   = 44" not in header:
        raise SystemExit("GGML_TYPE_COUNT is not 44")
    if '"-DGGML_VULKAN=ON"' not in gradle.read_text(encoding="utf-8"):
        raise SystemExit("Vulkan is not enabled in Android Gradle config")
    if 'set(GGML_VULKAN          ON' not in cmake.read_text(encoding="utf-8"):
        raise SystemExit("Vulkan is not enabled in native CMake config")

    print("STQ1_0 id 42 / Q2_0 id 43 mapping verified; Vulkan remains enabled.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
