project(main)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED True)

# JNI headers are provided by the Android NDK sysroot — no find_package needed

add_library(main SHARED
    main.cpp
)

target_link_libraries(main
    PRIVATE
    android
    log
)
