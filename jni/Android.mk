LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_PRELINK_MODULE := false

LOCAL_SRC_FILES:= \
    com_trustedlogic_trustednfc_android_internal_NativeLlcpConnectionlessSocket.cpp \
    com_trustedlogic_trustednfc_android_internal_NativeLlcpServiceSocket.cpp \
    com_trustedlogic_trustednfc_android_internal_NativeLlcpSocket.cpp \
    com_trustedlogic_trustednfc_android_internal_NativeNdefTag.cpp \
    com_trustedlogic_trustednfc_android_internal_NativeNfcManager.cpp \
    com_trustedlogic_trustednfc_android_internal_NativeNfcTag.cpp \
    com_trustedlogic_trustednfc_android_internal_NativeP2pDevice.cpp \
    trustednfc_jni.cpp

LOCAL_C_INCLUDES += \
    $(JNI_H_INCLUDE) \
    external/libnfc-nxp/src \
    external/libnfc-nxp/inc

LOCAL_SHARED_LIBRARIES := \
    libnativehelper \
    libcutils \
    libutils \
    libnfc

LOCAL_MODULE := libnfc_jni
LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)