project(main)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED True)

# JNI headers are provided by the Android NDK sysroot — no find_package needed

add_library(main SHARED
    main.cpp
)

# The Gradle project uses c++_shared for fusion. libmain is loaded through
# the game's linker namespace, so force its C++ runtime to be self-contained.
target_link_options(main PRIVATE -static-libstdc++)

target_link_libraries(main
    PRIVATE
    android
    log
    # libmain is loaded by the game's linker namespace via System.load().
    # That namespace cannot resolve our APK's libc++_shared.so dependency.
    # Keep this small bridge self-contained instead.
    c++_static
)
