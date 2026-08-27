project(fusion)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED True)

# JNI headers are provided by the Android NDK sysroot — no find_package needed

set(SOURCE_FILES
    src/fusion.cpp
    src/fusion_config.cpp
    src/dotnet.cpp
    src/exports.cpp
    src/logger.cpp
    src/hooking/il2cpp.cpp
    src/hooking/safehook.cpp
    src/hooking/libunity.cpp
)

add_library(fusion SHARED
    src/fusion.cpp
    src/fusion_config.cpp
    src/dotnet.cpp
    src/exports.cpp
    src/logger.cpp
    src/hooking/allocator.cpp
    src/hooking/il2cpp.cpp
    src/hooking/safehook.cpp
    src/hooking/libunity.cpp
    src/utilities/library.cpp
)

# fusion is loaded manually from libmain through the game's linker namespace.
# That namespace cannot resolve libc++_shared.so from the launcher APK.
target_link_options(fusion PRIVATE -static-libstdc++)

# Include directories: fusion headers + parent cpp dir (for dobby.h)
target_include_directories(fusion PUBLIC include ${CMAKE_SOURCE_DIR})

# Path to prebuilt .so files for linking
set(LIB_FOLDER ${CMAKE_SOURCE_DIR}/../jniLibs/${CMAKE_ANDROID_ARCH_ABI})

# fusion depends on main for libmain_set_override_* symbols.
# main resolves fusion symbols dynamically via dlopen/dlsym.
# This creates a one-way linker dependency: fusion → main.

target_link_libraries(fusion
    PUBLIC
    main
    android
    log
    ${LIB_FOLDER}/libdobby.so
)
