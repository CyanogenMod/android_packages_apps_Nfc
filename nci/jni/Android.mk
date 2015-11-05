VOB_COMPONENTS := external/libnfc-nci/src
NFA := $(VOB_COMPONENTS)/nfa
NFC := $(VOB_COMPONENTS)/nfc

LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
include $(call all-makefiles-under,$(LOCAL_PATH))

LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/Android.mk


ifneq ($(NCI_VERSION),)
LOCAL_CFLAGS += -DNCI_VERSION=$(NCI_VERSION) -O0 -g
endif

LOCAL_CFLAGS += -Wall -Wextra

define all-cpp-files-under
$(patsubst ./%,%, \
  $(shell cd $(LOCAL_PATH) ; \
          find $(1) -name "*.cpp" -and -not -name ".*") \
 )
endef

LOCAL_SRC_FILES = $(call all-cpp-files-under, .) $(call all-c-files-under, .)

LOCAL_C_INCLUDES += \
    external/libxml2/include \
    frameworks/native/include \
    libcore/include \
    $(NFA)/include \
    $(NFA)/brcm \
    $(NFC)/include \
    $(NFC)/brcm \
    $(NFC)/int \
    $(VOB_COMPONENTS)/hal/include \
    $(VOB_COMPONENTS)/hal/int \
    $(VOB_COMPONENTS)/include \
    $(VOB_COMPONENTS)/gki/ulinux \
    $(VOB_COMPONENTS)/gki/common

LOCAL_SHARED_LIBRARIES := \
    libicuuc \
    libnativehelper \
    libcutils \
    libutils \
    liblog \
    libnfc-nci \

LOCAL_STATIC_LIBRARIES := libxml2

LOCAL_MODULE := libnfc_nci_jni
LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)
