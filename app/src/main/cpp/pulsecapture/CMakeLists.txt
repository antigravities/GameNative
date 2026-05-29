# Builds libpulse_capture.so — a thin PulseAudio monitor-source recorder used by the replay
# buffer. It links against the prebuilt libpulse.so that already ships in jniLibs/<abi>/ (the
# same lib pactl uses at runtime); its transitive deps (libpulsecommon-13.0, libsndfile, ...)
# also live in jniLibs and are resolved by the Android loader in-process.
#
# This is a standalone CMake project wired directly into Gradle via app/build.gradle.kts'
# externalNativeBuild. It intentionally does NOT pull in the main cpp/CMakeLists.txt tree.

cmake_minimum_required(VERSION 3.22.1)
project(pulsecapture)

# Import the prebuilt libpulse for the ABI currently being built.
add_library(pulse SHARED IMPORTED)
set_target_properties(pulse PROPERTIES IMPORTED_LOCATION
    ${CMAKE_CURRENT_SOURCE_DIR}/../../jniLibs/${ANDROID_ABI}/libpulse.so)

add_library(pulse_capture SHARED pulse_capture.c)
target_include_directories(pulse_capture PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(pulse_capture pulse log android)
