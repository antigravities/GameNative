cmake_minimum_required(VERSION 3.22)
project(cowbase C)

set(CMAKE_C_STANDARD 11)

# Like the other native modules here, cowbase is not part of the default build --
# it ships as a prebuilt .so in jniLibs/arm64-v8a/. To rebuild it either uncomment
# the cowbase externalNativeBuild block in app/build.gradle.kts, build, copy the
# result into jniLibs/arm64-v8a/ and re-comment; or build it directly, which for a
# single dependency-free source file is simpler:
#
#   <ndk>/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android29-clang \
#     -O2 -fPIC -shared -fvisibility=hidden -Wall -Wextra \
#     -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
#     -o libcowbase.so cowbase.c -ldl -llog
#
# <ndk> is the version pinned by ndkVersion in app/build.gradle.kts. Afterwards
# confirm with `llvm-readelf --dyn-syms libcowbase.so` that every interposed
# symbol (open/open64/openat/openat64/creat/creat64/fopen/fopen64/truncate) is
# GLOBAL DEFAULT -- a HIDDEN one does not interpose and the shim would load
# cleanly while doing nothing.

add_library(cowbase SHARED cowbase.c)

find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(cowbase
        ${log-lib}
        ${dl-lib}
)

target_link_options(cowbase PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
)

target_compile_options(cowbase PRIVATE
        -fvisibility=hidden
        -O2
        -Wall
        -Wextra
)

# Strip the final .so in release builds
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_custom_command(TARGET cowbase POST_BUILD
            COMMAND ${CMAKE_STRIP} --strip-unneeded $<TARGET_FILE:cowbase>
    )
endif()
