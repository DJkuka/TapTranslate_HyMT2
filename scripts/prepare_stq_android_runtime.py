#!/usr/bin/env python3
"""Adapt upstream STQ1_0 support to the production AngelSlim GGUF tensor id 40."""
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

    header_path = llama / "ggml/include/ggml.h"
    replace_once(
        header_path,
        "        GGML_TYPE_NVFP4   = 40, // NVFP4 (4 blocks, E4M3 scale)\n"
        "        GGML_TYPE_Q1_0    = 41,\n"
        "        GGML_TYPE_Q2_0    = 42,\n"
        "        GGML_TYPE_STQ1_0  = 43,",
        "        GGML_TYPE_NVFP4   = 40, // unchanged\n"
        "        GGML_TYPE_Q1_0    = 41,\n"
        "        GGML_TYPE_STQ1_0  = 42, // Tencent Hy-MT2 official GGUF id\n"
        "        GGML_TYPE_Q2_0    = 43, // moved to free STQ id 42",
        "production STQ tensor id 40 (preserve Q1/Q2 ids)",
    )
    header = header_path.read_text(encoding="utf-8")
    if not re.search(r"GGML_TYPE_STQ1_0\s*=\s*42,", header):
        raise SystemExit("STQ1_0 must use Tencent Hy-MT2 GGUF tensor id 42")
    if not re.search(r"GGML_TYPE_Q2_0\s*=\s*43,", header):
        raise SystemExit("Q2_0 must move to 43 to avoid the legacy STQ1_0 id collision")
    if not re.search(r"GGML_TYPE_NVFP4\s*=\s*40,", header):
        raise SystemExit("NVFP4 must remain at its upstream id 40")
    if not re.search(r"GGML_TYPE_COUNT\s*=\s*44", header):
        raise SystemExit("Unexpected GGML_TYPE_COUNT")

    constants = llama / "gguf-py/gguf/constants.py"
    replace_once(
        constants,
        "    NVFP4   = 40\n    Q1_0    = 41\n    Q2_0    = 42\n    STQ1_0  = 43",
        "    NVFP4   = 40\n    Q1_0    = 41\n    STQ1_0  = 42\n    Q2_0    = 43",
        "GGUF Python tensor type ids",
    )

    arm = (llama / "ggml/src/ggml-cpu/arch/arm/quants.c").read_text(encoding="utf-8")
    if "ggml_vec_dot_stq1_0_q8_K" not in arm or "STQ1_0_DOT_HALF" not in arm:
        raise SystemExit("Optimized ARM NEON STQ1_0 kernel missing")
    generic = (llama / "ggml/src/ggml-cpu/quants.c").read_text(encoding="utf-8")
    if "ggml_vec_dot_stq1_0_q8_K_generic" not in generic:
        raise SystemExit("STQ1_0 generic fallback missing")

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
        '                    "-DLLAMA_CURL=OFF",    // no libcurl dependency\n                    "-DGGML_CPU=ON",\n                    "-DGGML_BACKEND_DL=OFF", // link Vulkan/CPU backends directly\n                    "-DVulkan_INCLUDE_DIR=" + System.getenv("VULKAN_HEADERS_INCLUDE_DIR"),\n                    "-DSPIRV_HEADERS_INCLUDE_DIR=" + System.getenv("SPIRV_HEADERS_INCLUDE_DIR"),\n                    "-DSPIRV-Headers_DIR=" + System.getenv("SPIRV_HEADERS_CMAKE_DIR"),',
        "pass Vulkan and SPIR-V paths to Gradle CMake",
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
    print("Tencent STQ1_0 id 42, Q2_0 id 43, ARM NEON kernel and Vulkan/Q4 settings verified.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
