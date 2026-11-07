#ifndef FEATURESTICBOX_LINUXHOOK_H
#define FEATURESTICBOX_LINUXHOOK_H

#include "BaseHook.h"

class LinuxHook : public BaseHook {
public:
    static void init(JNIEnv *env);
};

#endif // FEATURESTICBOX_LINUXHOOK_H
