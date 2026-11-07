#ifndef LOGGER_H
#define LOGGER_H

#include <android/log.h>

#define TAG "FeaturesticCore"
#define LOGD(...) ((void)0)
#define LOGI(...) ((void)0)
#define LOGW(...) ((void)0)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#endif
