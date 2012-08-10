/*
 * Copyright (C) 2010 The Android Open Source Project
 * Copyright (C) 2012 Broadcom Corporation
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
#include "SecureElement.h"
#include "nfa_brcm_api.h"


namespace android
{


extern void com_android_nfc_NfcManager_disableDiscovery (JNIEnv* e, jobject o);
extern void com_android_nfc_NfcManager_enableDiscovery (JNIEnv* e, jobject o, jint mode);
extern char* gNativeNfcSecureElementClassName;
extern int gGeneralTransceiveTimeout;


/*******************************************************************************
**
** Function:        nativeNfcSecureElement_doOpenSecureElementConnection
**
** Description:     Connect to the secure element.
**                  e: JVM environment.
**                  o: Java object.
**
** Returns:         Handle of secure element.  0 is failure.
**
*******************************************************************************/
static jint nativeNfcSecureElement_doOpenSecureElementConnection (JNIEnv* e, jobject o)
{
    ALOGD("%s: enter", __FUNCTION__);
    bool stat = true;
    jint secElemHandle = 0;

    //if controller is not routing AND there is no pipe connected,
    //then turn on the sec elem
    if (! SecureElement::getInstance().isBusy())
        stat = SecureElement::getInstance().activate(0);

    if (stat)
    {
        //establish a pipe to sec elem
        stat = SecureElement::getInstance().connectEE();
        if (stat)
            secElemHandle = SecureElement::getInstance().mActiveEeHandle;
        else
            SecureElement::getInstance().deactivate (0);
    }

TheEnd:
    ALOGD("%s: exit; return handle=0x%X", __FUNCTION__, secElemHandle);
    return secElemHandle;
}


/*******************************************************************************
**
** Function:        nativeNfcSecureElement_doDisconnectSecureElementConnection
**
** Description:     Disconnect from the secure element.
**                  e: JVM environment.
**                  o: Java object.
**                  handle: Handle of secure element.
**
** Returns:         True if ok.
**
*******************************************************************************/
static jboolean nativeNfcSecureElement_doDisconnectSecureElementConnection (JNIEnv* e, jobject o, jint handle)
{
    ALOGD("%s: enter; handle=0x%04x", __FUNCTION__, handle);
    bool stat = false;

    stat = SecureElement::getInstance().disconnectEE (handle);

    //if controller is not routing AND there is no pipe connected,
    //then turn off the sec elem
    if (! SecureElement::getInstance().isBusy())
        SecureElement::getInstance().deactivate (handle);

    ALOGD("%s: exit", __FUNCTION__);
    return stat ? JNI_TRUE : JNI_FALSE;
}


/*******************************************************************************
**
** Function:        nativeNfcSecureElement_doTransceive
**
** Description:     Send data to the secure element; retrieve response.
**                  e: JVM environment.
**                  o: Java object.
**                  handle: Secure element's handle.
**                  data: Data to send.
**
** Returns:         Buffer of received data.
**
*******************************************************************************/
static jbyteArray nativeNfcSecureElement_doTransceive (JNIEnv* e, jobject o, jint handle, jbyteArray data)
{
    UINT8* buf = NULL;
    INT32 buflen = 0;
    const INT32 recvBufferMaxSize = 1024;
    UINT8 recvBuffer [recvBufferMaxSize];
    INT32 recvBufferActualSize = 0;
    jbyteArray result = NULL;

    buf = (UINT8*) e->GetByteArrayElements (data, NULL);
    buflen = e->GetArrayLength (data);

    ALOGD("%s: enter; handle=0x%X; buf len=%ld", __FUNCTION__, handle, buflen);
    SecureElement::getInstance().transceive (buf, buflen, recvBuffer, recvBufferMaxSize, recvBufferActualSize, gGeneralTransceiveTimeout);

    //copy results back to java
    result = e->NewByteArray (recvBufferActualSize);
    if (result != NULL)
    {
        e->SetByteArrayRegion (result, 0, recvBufferActualSize, (jbyte *) recvBuffer);
    }

    e->ReleaseByteArrayElements (data, (jbyte *) buf, JNI_ABORT);
    ALOGD("%s: exit: recv len=%ld", __FUNCTION__, recvBufferActualSize);
    return result;
}


/*******************************************************************************
**
** Function:        nativeNfcSecureElement_doGetUid
**
** Description:     Get the secure element's unique ID.
**                  e: JVM environment.
**                  o: Java object.
**                  handle: Handle of secure element.
**
** Returns:         Secure element's unique ID.
**
*******************************************************************************/
static jbyteArray nativeNfcSecureElement_doGetUid (JNIEnv* e, jobject o, jint handle)
{
    ALOGD("%s: enter; handle=0x%X", __FUNCTION__, handle);
    jbyteArray secureElementUid = NULL;

    SecureElement::getInstance ().getUiccId (handle, secureElementUid);

    ALOGD("%s: exit", __FUNCTION__);
    return secureElementUid;
}


/*******************************************************************************
**
** Function:        nativeNfcSecureElement_doGetTechList
**
** Description:     Get a list of technologies that the secure element supports.
**                  e: JVM environment.
**                  o: Java object.
**                  handle: Handle of secure element.
**
** Returns:         Array of technologies.
**
*******************************************************************************/
static jintArray nativeNfcSecureElement_doGetTechList (JNIEnv* e, jobject o, jint handle)
{
    ALOGD("%s: enter; handle=0x%X", __FUNCTION__, handle);
    jintArray techList = NULL;

    SecureElement::getInstance().getTechnologyList (handle, techList);

    ALOGD("%s: exit", __FUNCTION__);
    return techList;
}


/*****************************************************************************
**
** Description:     JNI functions
**
*****************************************************************************/
static JNINativeMethod gMethods[] =
{
   {"doNativeOpenSecureElementConnection", "()I", (void *) nativeNfcSecureElement_doOpenSecureElementConnection},
   {"doNativeDisconnectSecureElementConnection", "(I)Z", (void *) nativeNfcSecureElement_doDisconnectSecureElementConnection},
   {"doTransceive", "(I[B)[B", (void *) nativeNfcSecureElement_doTransceive},
   {"doGetUid", "(I)[B", (void *) nativeNfcSecureElement_doGetUid},
   {"doGetTechList", "(I)[I", (void *) nativeNfcSecureElement_doGetTechList},
};


/*******************************************************************************
**
** Function:        register_com_android_nfc_NativeNfcSecureElement
**
** Description:     Regisgter JNI functions with Java Virtual Machine.
**                  e: Environment of JVM.
**
** Returns:         Status of registration.
**
*******************************************************************************/
int register_com_android_nfc_NativeNfcSecureElement(JNIEnv *e)
{
    return jniRegisterNativeMethods(e, gNativeNfcSecureElementClassName,
            gMethods, NELEM(gMethods));
}


} // namespace android

