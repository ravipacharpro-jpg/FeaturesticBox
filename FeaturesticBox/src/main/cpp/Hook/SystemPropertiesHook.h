#ifndef FEATURESTICBOX_SYSTEMPROPERTIESHOOK_H
#define FEATURESTICBOX_SYSTEMPROPERTIESHOOK_H

#include <map>
#include "BaseHook.h"
#include <string>
#include <sys/system_properties.h>

class SystemPropertiesHook : public BaseHook {
public:
    static void init(JNIEnv *env);
    static void setProp(const char *key, const char *value);
};

extern "C" {
int safe_system_property_get(const char *name, char *value);
const prop_info *safe_system_property_find(const char *name);
}

#endif // FEATURESTICBOX_SYSTEMPROPERTIESHOOK_H

