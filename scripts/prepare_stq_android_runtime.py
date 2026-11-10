#!/usr/bin/env python3
"""Adapt upstream STQ1_0 support to Tencent Hy-MT2 GGUF tensor id 42."""
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

    # Keep upstream GGML IDs unchanged: Q2_0 remains 42 and STQ1_0 remains 43.
    # Tencent's 1.25-bit Hy-MT2 GGUF uses disk id 42 for STQ1_0, so remap it
    # only for that exact model signature while parsing its tensor table and file metadata.
    header_path = llama / "ggml/include/ggml.h"
    header = header_path.read_text(encoding="utf-8")
    if not re.search(r"GGML_TYPE_NVFP4\s*=\s*40,", header):
        raise SystemExit("Unexpected NVFP4 GGML type id; expected 40")
    if not re.search(r"GGML_TYPE_Q1_0\s*=\s*41,", header):
        raise SystemExit("Unexpected Q1_0 GGML type id; expected 41")
    if not re.search(r"GGML_TYPE_Q2_0\s*=\s*42,", header):
        raise SystemExit("Upstream Q2_0 id 42 must remain unchanged")
    if not re.search(r"GGML_TYPE_STQ1_0\s*=\s*43,", header):
        raise SystemExit("Upstream internal STQ1_0 id 43 must remain unchanged")
    if not re.search(r"GGML_TYPE_COUNT\s*=\s*44", header):
        raise SystemExit("Unexpected GGML_TYPE_COUNT")

    gguf_cpp = llama / "ggml/src/gguf.cpp"
    replace_once(
        gguf_cpp,
        "    // read the tensor info\n    for (int64_t i = 0; ok && i < n_tensors; ++i) {",
        """    // Tencent's Hy-MT2 1.25-bit GGUF stores STQ1_0 as on-disk type 42,
    // which collides with the standard GGML_TYPE_Q2_0 id. Detect this exact
    // model signature from metadata and remap only its tensor entries to the
    // internal STQ1_0 enum (43). Other GGUF files keep standard Q2_0 semantics and metadata.
    const int architecture_idx = gguf_find_key(ctx, "general.architecture");
    const int file_type_idx = gguf_find_key(ctx, "general.file_type");
    const bool is_tencent_hymt2_stq =
        architecture_idx >= 0 &&
        file_type_idx >= 0 &&
        gguf_get_kv_type(ctx, architecture_idx) == GGUF_TYPE_STRING &&
        gguf_get_kv_type(ctx, file_type_idx) == GGUF_TYPE_UINT32 &&
        strcmp(gguf_get_val_str(ctx, architecture_idx), "hunyuan-dense") == 0 &&
        gguf_get_val_u32(ctx, file_type_idx) == 41 &&
        gguf_find_key(ctx, "general.finetune") >= 0 &&
        gguf_get_kv_type(ctx, gguf_find_key(ctx, "general.finetune")) == GGUF_TYPE_STRING &&
        strcmp(gguf_get_val_str(ctx, gguf_find_key(ctx, "general.finetune")), "2bit-stride16") == 0;

    if (is_tencent_hymt2_stq) {
        GGML_LOG_INFO("%s: applying Tencent Hy-MT2 STQ1_0 GGUF compatibility mapping (tensor type 42 -> 43, file_type 41 -> 42)\\n", __func__);
        // Convert the legacy Tencent quantization tag to the current llama.cpp file type.
        gguf_set_val_u32(ctx, "general.file_type", 42);
    }

    // read the tensor info
    for (int64_t i = 0; ok && i < n_tensors; ++i) {""",
        "model-specific Tencent STQ1_0 tensor and file-type compatibility",
    )
    replace_once(
        gguf_cpp,
        "            ok = ok && gr.read(info.t.type);\n\n            // check that tensor type is within defined range",
        """            ok = ok && gr.read(info.t.type);

            // Only Tencent Hy-MT2 1.25-bit GGUF reuses the Q2_0 disk id 42.
            if (is_tencent_hymt2_stq && info.t.type == GGML_TYPE_Q2_0) {
                info.t.type = GGML_TYPE_STQ1_0;
            }

            // check that tensor type is within defined range""",
        "per-tensor Tencent STQ1_0 type remapping",
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

    vulkan_cmake = llama / "ggml/src/ggml-vulkan/CMakeLists.txt"
    replace_once(
        vulkan_cmake,
        "    target_link_libraries(ggml-vulkan PRIVATE Vulkan::Vulkan)",
        """    target_link_libraries(ggml-vulkan PRIVATE Vulkan::Vulkan SPIRV-Headers::SPIRV-Headers)
    if (DEFINED SPIRV_HEADERS_INCLUDE_DIR)
        target_include_directories(ggml-vulkan SYSTEM PRIVATE ${SPIRV_HEADERS_INCLUDE_DIR})
    endif()""",
        "link the installed SPIRV-Headers target and explicit include directory to the Vulkan backend",
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
    print("Tencent model-specific disk type 42 -> internal STQ1_0=43; standard Q2_0=42 preserved; ARM NEON, Vulkan and explicit SPIR-V header include path verified.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
