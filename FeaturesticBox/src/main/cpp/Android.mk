LOCAL_PATH := $(call my-dir)
MAIN_LOCAL_PATH := $(LOCAL_PATH)

# ========== FeaturesticCore shared library ==========
include $(CLEAR_VARS)

# --- Module Name ---
LOCAL_MODULE := FeaturesticCore

# -------- C FLAGS (SAFE) --------
LOCAL_CFLAGS := \
	-O3 \
	-Wno-error=format-security \
	-fvisibility=hidden \
	-ffunction-sections \
	-fdata-sections \
	-fno-stack-protector \
	-funwind-tables \
	-fcommon

# -------- CPP FLAGS (NO CODE REMOVED) --------
LOCAL_CPPFLAGS := \
	-std=c++17 \
	-Wno-error=format-security \
	-Wno-error=c++11-narrowing \
	-fvisibility=hidden \
	-fvisibility-inlines-hidden \
	-ffunction-sections \
	-fdata-sections \
	-fno-stack-protector \
	-fexceptions \
	-fno-rtti \
	-funwind-tables \
	-fasynchronous-unwind-tables \
	-fcommon

# -------- LINKER FLAGS --------
LOCAL_LDFLAGS := \
	-Wl,--gc-sections \
	-Wl,--strip-all \
	-Wl,--exclude-libs,ALL \
	-Wl,-z,relro \
	-Wl,-z,now \
	-Wl,-z,noexecstack

LOCAL_LDLIBS := -llog -landroid -lz
LOCAL_ARM_MODE := arm

# --- Include Paths ---
LOCAL_C_INCLUDES += $(LOCAL_PATH)
LOCAL_C_INCLUDES += $(LOCAL_PATH)/Hook
LOCAL_C_INCLUDES += $(LOCAL_PATH)/IO
LOCAL_C_INCLUDES += $(LOCAL_PATH)/JniHook
LOCAL_C_INCLUDES += $(LOCAL_PATH)/SandHook
LOCAL_C_INCLUDES += $(LOCAL_PATH)/SandHook/xdl
LOCAL_C_INCLUDES += $(LOCAL_PATH)/SandHook/libzip
LOCAL_C_INCLUDES += $(LOCAL_PATH)/KittyMemory

# --- ADD: libzip headers ---
LOCAL_C_INCLUDES += $(LOCAL_PATH)/libzip

# --- Source Files ---
FEATURESTIC_CORE_SRC := $(wildcard $(LOCAL_PATH)/*.cpp)
FEATURESTIC_CORE_SRC += $(wildcard $(LOCAL_PATH)/Hook/*.cpp)
ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
LOCAL_C_INCLUDES += $(LOCAL_PATH)/Hook/And64InlineHook
FEATURESTIC_CORE_SRC += $(LOCAL_PATH)/Hook/And64InlineHook/And64InlineHook.cpp
endif
FEATURESTIC_CORE_SRC += $(wildcard $(LOCAL_PATH)/IO/*.cpp)
FEATURESTIC_CORE_SRC += $(wildcard $(LOCAL_PATH)/JniHook/*.cpp)
FEATURESTIC_CORE_SRC += $(wildcard $(LOCAL_PATH)/SandHook/*.cpp)
FEATURESTIC_CORE_SRC += $(wildcard $(LOCAL_PATH)/KittyMemory/*.cpp)


# C Files (xdl - SandHook/xdl)
FEATURESTIC_CORE_SRC += $(wildcard $(LOCAL_PATH)/SandHook/xdl/*.c)
# C Files (libzip - SandHook/libzip)
FEATURESTIC_CORE_SRC += $(wildcard $(LOCAL_PATH)/SandHook/libzip/*.c)

LOCAL_SRC_FILES := $(FEATURESTIC_CORE_SRC:$(LOCAL_PATH)/%=%)

#LOCAL_STATIC_LIBRARIES := libdobby

# --- Build Shared Library ---
include $(BUILD_SHARED_LIBRARY)
