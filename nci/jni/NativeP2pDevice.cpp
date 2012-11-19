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

#include "OverrideLog.h"
#include "NfcJniUtil.h"
#include "JavaClassConstants.h"


namespace android
{


static jboolean nativeP2pDeviceDoConnect (JNIEnv* e, jobject o)
{
    ALOGD ("%s", __FUNCTION__);
    return JNI_TRUE;
}


static jboolean nativeP2pDeviceDoDisconnect (JNIEnv* e, jobject o)
{
    ALOGD ("%s", __FUNCTION__);
    return JNI_TRUE;
}


static jbyteArray nativeP2pDeviceDoTransceive (JNIEnv* e, jobject o, jbyteArray data)
{
    ALOGD ("%s", __FUNCTION__);
    return NULL;
}


static jbyteArray nativeP2pDeviceDoReceive (JNIEnv* e, jobject o)
{
    ALOGD ("%s", __FUNCTION__);
    return NULL;
}


static jboolean nativeP2pDeviceDoSend (JNIEnv* e, jobject o, jbyteArray buf)
{
    ALOGD ("%s", __FUNCTION__);
    return JNI_TRUE;
}


/*****************************************************************************
**
** Description:     JNI functions
**
*****************************************************************************/
static JNINativeMethod gMethods[] =
{
    {"doConnect", "()Z", (void *) nativeP2pDeviceDoConnect},
    {"doDisconnect", "()Z", (void *) nativeP2pDeviceDoDisconnect},
    {"doTransceive", "([B)[B", (void *) nativeP2pDeviceDoTransceive},
    {"doReceive", "()[B", (void *) nativeP2pDeviceDoReceive},
    {"doSend", "([B)Z", (void *) nativeP2pDeviceDoSend},
};


/*******************************************************************************
**
** Function:        register_com_android_nfc_NativeP2pDevice
**
** Description:     Regisgter JNI functions with Java Virtual Machine.
**                  e: Environment of JVM.
**
** Returns:         Status of registration.
**
*******************************************************************************/
int register_com_android_nfc_NativeP2pDevice (JNIEnv* e)
{
    return jniRegisterNativeMethods (e, gNativeP2pDeviceClassName,
            gMethods, NELEM(gMethods));
}


} // namepspace android
