#include <android/log.h>

#define TAG "FeaturesticCore"

#define log_print_error(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define log_print_warn(...)  __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define log_print_info(...)  __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define log_print_debug(...) ((void)0)

#define ALOGE(...) log_print_error(__VA_ARGS__)
#define ALOGW(...) log_print_warn(__VA_ARGS__)
#define ALOGI(...) log_print_info(__VA_ARGS__)
#define ALOGD(...) log_print_debug(__VA_ARGS__)

#ifndef SPEED_LOG_H
#define SPEED_LOG_H 1
#endif
