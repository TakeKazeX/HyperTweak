# HyperTweak native hook payload.
#
# The library is delivered inside the module APK and injected by LSPosed, which
# calls the exported `native_init` in every process the module is scoped to. It
# is therefore built as a small, self-contained, fail-closed payload: no
# exceptions, no RTTI, only `native_init` and the JNI status query exported.
cmake_minimum_required(VERSION 3.22)

project(hypertweak_native LANGUAGES CXX ASM)

if(NOT ANDROID)
    message(FATAL_ERROR "hypertweak_native must use the Android NDK toolchain")
endif()

if(NOT "${ANDROID_ABI}" STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Only arm64-v8a is supported; got '${ANDROID_ABI}'")
endif()

# LSPlt supplies the PLT/GOT hook used for the MADV_DONTNEED page guard. It must
# be the 16 KB page-size build: the payload patches pages that the framework can
# otherwise discard on any device booted with 16 KB pages.
find_package(lsplt REQUIRED CONFIG)

set(HYPERTWEAK_NATIVE_TARGET hypertweak_native)
set(HYPERTWEAK_NATIVE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/NativeRules")

add_library(${HYPERTWEAK_NATIVE_TARGET} SHARED
    "${HYPERTWEAK_NATIVE_DIR}/logging.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/lsposed_hook_backend.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/framework_api.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/native_config.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/clear_button_rule.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/clear_button_hook.S"
    "${HYPERTWEAK_NATIVE_DIR}/rules_entry.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/jni_bridge.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/native_init.cpp"
)

target_include_directories(${HYPERTWEAK_NATIVE_TARGET} PRIVATE
    "${HYPERTWEAK_NATIVE_DIR}"
)

target_compile_features(${HYPERTWEAK_NATIVE_TARGET} PRIVATE cxx_std_17)
target_compile_options(${HYPERTWEAK_NATIVE_TARGET} PRIVATE
    -fno-exceptions
    -fno-rtti
    -fno-threadsafe-statics
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
    -mbranch-protection=standard
    -Wall
    -Wextra
    -Werror
)

target_link_options(${HYPERTWEAK_NATIVE_TARGET} PRIVATE
    "-Wl,--version-script=${HYPERTWEAK_NATIVE_DIR}/exports.map"
    -Wl,--gc-sections
    -Wl,--no-undefined
    -Wl,-z,relro
    -Wl,-z,now
    "-Wl,-rpath,$ORIGIN"
)

target_link_libraries(${HYPERTWEAK_NATIVE_TARGET} PRIVATE log dl lsplt::lsplt)

set_target_properties(${HYPERTWEAK_NATIVE_TARGET} PROPERTIES
    CXX_EXTENSIONS OFF
    OUTPUT_NAME "${HYPERTWEAK_NATIVE_TARGET}"
)
