cmake_minimum_required(VERSION 3.22.1)
project("bepinex_android" CXX)

# FusionCore main branch architecture:
#   libmain.so  — registers NativeLoader load/unload, resolves fusion dynamically
#   libfusion.so — il2cpp_init hook, CoreCLR bootstrap, BepInEx entry
#
# These are NOT linked together. libmain resolves libfusion symbols
# at runtime via dlopen("libfusion.so", RTLD_NOLOAD) + dlsym.

add_subdirectory(main)
add_subdirectory(fusion)
