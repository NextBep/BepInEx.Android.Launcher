// Copyright (c) 2026 XtraCube. All rights reserved.

#ifndef NEXTBEP_TOOLS_H
#define NEXTBEP_TOOLS_H

#include <dlfcn.h>

inline const char *safe_dlerror() {
    char *dlErr = dlerror();
    const char *err = dlErr ? dlErr : "Unknown error";
    return err;
}

#endif //NEXTBEP_TOOLS_H
