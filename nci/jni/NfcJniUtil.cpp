/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include "NfcJniUtil.h"
#include <errno.h>


/*******************************************************************************
**
** Function:        JNI_OnLoad
**
** Description:     Register all JNI functions with Java Virtual Machine.
**                  jvm: Java Virtual Machine.
**                  reserved: Not used.
**
** Returns:         JNI version.
**
*******************************************************************************/
jint JNI_OnLoad (JavaVM *jvm, void *reserved)
{
    ALOGD ("%s: enter", __FUNCTION__);
    JNIEnv *e = NULL;

    // Check JNI version
    if (jvm->GetEnv ((void **) &e, JNI_VERSION_1_6))
        return JNI_ERR;

    if (android::register_com_android_nfc_NativeNfcManager (e) == -1)
        return JNI_ERR;
    if (android::register_com_android_nfc_NativeLlcpServiceSocket (e) == -1)
        return JNI_ERR;
    if (android::register_com_android_nfc_NativeLlcpSocket (e) == -1)
        return JNI_ERR;
    if (android::register_com_android_nfc_NativeNfcTag (e) == -1)
        return JNI_ERR;
    if (android::register_com_android_nfc_NativeLlcpConnectionlessSocket (e) == -1)
        return JNI_ERR;
    if (android::register_com_android_nfc_NativeP2pDevice (e) == -1)
        return JNI_ERR;
    if (android::register_com_android_nfc_NativeNfcSecureElement (e) == -1)
        return JNI_ERR;
    ALOGD ("%s: exit", __FUNCTION__);
    return JNI_VERSION_1_6;
}


namespace android
{


/*******************************************************************************
**
** Function:        nfc_jni_cache_object
**
** Description:
**
** Returns:         Status code.
**
*******************************************************************************/
int nfc_jni_cache_object (JNIEnv *e, const char *className, jobject *cachedObj)
{
    jclass cls = NULL;
    jobject obj = NULL;
    jmethodID ctor = 0;

    cls = e->FindClass (className);
    if(cls == NULL)
    {
        ALOGE ("%s: find class error", __FUNCTION__);
        return -1;
    }

    ctor = e->GetMethodID (cls, "<init>", "()V");
    obj = e->NewObject (cls, ctor);
    if (obj == NULL)
    {
       ALOGE ("%s: create object error", __FUNCTION__);
       return -1;
    }

    *cachedObj = e->NewGlobalRef (obj);
    if (*cachedObj == NULL)
    {
        e->DeleteLocalRef (obj);
        ALOGE ("%s: global ref error", __FUNCTION__);
        return -1;
    }
    e->DeleteLocalRef (obj);
    return 0;
}


/*******************************************************************************
**
** Function:        nfc_jni_get_nfc_socket_handle
**
** Description:     Get the value of "mHandle" member variable.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         Value of mHandle.
**
*******************************************************************************/
int nfc_jni_get_nfc_socket_handle (JNIEnv *e, jobject o)
{
    jclass c = NULL;
    jfieldID f = 0;

    c = e->GetObjectClass (o);
    f = e->GetFieldID (c, "mHandle", "I");
    return e->GetIntField (o, f);
}


/*******************************************************************************
**
** Function:        nfc_jni_get_nat
**
** Description:     Get the value of "mNative" member variable.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         Pointer to the value of mNative.
**
*******************************************************************************/
struct nfc_jni_native_data* nfc_jni_get_nat(JNIEnv *e, jobject o)
{
   jclass c = NULL;
   jfieldID f = 0;

   /* Retrieve native structure address */
   c = e->GetObjectClass(o);
   f = e->GetFieldID(c, "mNative", "I");
   return (struct nfc_jni_native_data*)e->GetIntField(o, f);
}


} // namespace android
