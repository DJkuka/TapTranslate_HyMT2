cmake_minimum_required(VERSION 3.22.1)
project(hymt2_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED LLAMA_ROOT OR NOT EXISTS "${LLAMA_ROOT}/include/llama.h")
    message(FATAL_ERROR "LLAMA_ROOT must point to the pinned llama.cpp checkout (tag b9878)")
endif()
if(NOT DEFINED VULKAN_AAR_NATIVE_DIR OR NOT EXISTS "${VULKAN_AAR_NATIVE_DIR}/libllama.so")
    message(FATAL_ERROR "VULKAN_AAR_NATIVE_DIR must point to the extracted Vulkan AAR jni/arm64-v8a folder")
endif()

foreach(lib ggml-base ggml-cpu ggml-vulkan ggml llama)
    add_library(${lib} SHARED IMPORTED GLOBAL)
    set_target_properties(${lib} PROPERTIES
        IMPORTED_LOCATION "${VULKAN_AAR_NATIVE_DIR}/lib${lib}.so")
endforeach()

add_library(hymt2_jni SHARED HyMT2PenaltyNative.cpp)
target_include_directories(hymt2_jni PRIVATE
    "${LLAMA_ROOT}/include"
    "${LLAMA_ROOT}/ggml/include")

find_library(log-lib log)
find_library(android-lib android)
find_library(dl-lib dl)

target_link_libraries(hymt2_jni PRIVATE
    llama ggml ggml-cpu ggml-base ggml-vulkan
    ${log-lib} ${android-lib} ${dl-lib})
