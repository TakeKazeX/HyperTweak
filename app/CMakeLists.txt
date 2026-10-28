# HyperTweak native hook payload.
#
# The launcher lifecycle and native hook framework are the upstream
# MiuiBackGestureHook implementation. HyperTweak adds the clear-button rule and
# its module-side config/status bridge around that entry point.
#
# Host-side authoring tool (NOT part of this target):
#
#   dart_probe resolves the Dart target registry against a real libapp.so, so a new
#   hook can be developed and regression-tested without a device. It shares
#   dart_image.cpp and dart_targets.cpp with this payload, so its verdict is the
#   payload's verdict. Build it with the host compiler:
#
#     clang++ -std=c++17 -O2 -Wall -Wextra -Werror -o /tmp/dart_probe \
#       -I resolver -I rules tools/dart_probe.cpp resolver/dart_image.cpp \
#       resolver/dart_targets.cpp
#
#   dart_targets.cpp includes the per-feature *_target.inc files from rules/, so the
#   rules/ include directory is required in addition to resolver/.
#
#   Usage:
#     dart_probe <libapp.so>                      # resolve + report every site
#     dart_probe <libapp.so> --expect site=0xADDR # assert a known RVA
#     dart_probe <libapp.so> --mutate 0xADDR      # fail-closed negative control
#     dart_probe <libapp.so> --classes 0xADDR     # dump instruction-class sequence
#
#   It has a main() and reads files, so it is deliberately absent from the source
#   list below; dart_rule_support.cpp likewise calls the Android-only upstream
#   image resolver and is not part of the host build.

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
    "${HYPERTWEAK_NATIVE_DIR}/support/logging.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/resolver/dart_image.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/resolver/dart_targets.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/rules/dock_frame_rule.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/rules/dock_frame_hook.S"
    "${HYPERTWEAK_NATIVE_DIR}/runtime/dart_rule_support.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/runtime/dart_resolution_cache.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/runtime/native_store.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/runtime/native_rule_runtime.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/support/lsposed_hook_backend.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/resolver/runtime_profile_resolver.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/resolver/dart_runtime_resolver.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/miui_home_native_hook.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/runtime/dart_state_publisher.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/runtime/dart_state_worker.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/runtime/dart_observer_retirement.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/transport/broadcast_transport.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/runtime/launcher_process_identity.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/runtime/dart_mapped_image.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/runtime/native_config.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/rules/assistant_widget_rule.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/rules/assistant_widget_hook.S"
    "${HYPERTWEAK_NATIVE_DIR}/rules/xiaoai_gesture_rule.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/rules/xiaoai_gesture_hook.S"
    "${HYPERTWEAK_NATIVE_DIR}/rules/recents_haptic_rule.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/rules/recents_haptic_hook.S"
    "${HYPERTWEAK_NATIVE_DIR}/rules/widget_stack_rule.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/rules/widget_stack_hook.S"
    "${HYPERTWEAK_NATIVE_DIR}/rules/folder_stack_rule.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/rules/folder_stack_hook.S"
    "${HYPERTWEAK_NATIVE_DIR}/rules/clear_button_rule.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/rules/clear_button_hook.S"
    "${HYPERTWEAK_NATIVE_DIR}/rules/folder_columns_rule.cpp"
    "${HYPERTWEAK_NATIVE_DIR}/rules/folder_columns_hook.S"
    "${HYPERTWEAK_NATIVE_DIR}/transport/broadcast_options_tail_hook.S"
    "${HYPERTWEAK_NATIVE_DIR}/transport/broadcast_send_capture_hook.S"
    "${HYPERTWEAK_NATIVE_DIR}/runtime/dart_state_hooks.S"
    "${HYPERTWEAK_NATIVE_DIR}/runtime/input_monitor_pilfer_hook.S"
    "${HYPERTWEAK_NATIVE_DIR}/bridge/jni_bridge.cpp"
)

target_include_directories(${HYPERTWEAK_NATIVE_TARGET} PRIVATE
    "${HYPERTWEAK_NATIVE_DIR}"
    "${HYPERTWEAK_NATIVE_DIR}/bridge"
    "${HYPERTWEAK_NATIVE_DIR}/resolver"
    "${HYPERTWEAK_NATIVE_DIR}/rules"
    "${HYPERTWEAK_NATIVE_DIR}/runtime"
    "${HYPERTWEAK_NATIVE_DIR}/support"
    "${HYPERTWEAK_NATIVE_DIR}/transport"
)

target_compile_features(${HYPERTWEAK_NATIVE_TARGET} PRIVATE cxx_std_17)
target_compile_options(${HYPERTWEAK_NATIVE_TARGET} PRIVATE
    "$<$<AND:$<CONFIG:Release,RelWithDebInfo,MinSizeRel>,$<COMPILE_LANGUAGE:CXX>>:-Oz>"
    "$<$<AND:$<CONFIG:Release,RelWithDebInfo,MinSizeRel>,$<COMPILE_LANGUAGE:CXX>>:-flto=thin>"
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
    "$<$<CONFIG:Release,RelWithDebInfo,MinSizeRel>:-Oz>"
    "$<$<CONFIG:Release,RelWithDebInfo,MinSizeRel>:-flto=thin>"
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
    LINK_DEPENDS "${HYPERTWEAK_NATIVE_DIR}/exports.map"
)

# Persisted offsets are invalidated on module version changes as well as AOT identity.
target_compile_definitions(${HYPERTWEAK_NATIVE_TARGET} PRIVATE
    HYPERTWEAK_NATIVE_VERSION=${HYPERTWEAK_NATIVE_VERSION}
)
