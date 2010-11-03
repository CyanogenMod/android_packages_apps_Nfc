/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include <semaphore.h>

#include "com_android_nfc.h"

static sem_t *nfc_jni_llcp_send_sem;
static sem_t *nfc_jni_llcp_receive_sem;
static NFCSTATUS nfc_jni_cb_status = NFCSTATUS_FAILED;
static uint8_t receivedSsap;

namespace android {

/*
 * Callbacks
 */

static void nfc_jni_receive_callback(void* pContext, uint8_t ssap, NFCSTATUS status)
{  
   uint8_t* receiveSsap = (uint8_t*)pContext;
   
   LOG_CALLBACK("nfc_jni_receiveFrom_callback", status);

   nfc_jni_cb_status = status;
   
   if(status == NFCSTATUS_SUCCESS)
   {
      *receiveSsap = ssap;
      LOGD("RECEIVE UI_FRAME FROM SAP %d OK \n",*receiveSsap);
   }
   
   sem_post(nfc_jni_llcp_receive_sem);
}

static void nfc_jni_send_callback(void *pContext, NFCSTATUS status)
{     
   PHNFC_UNUSED_VARIABLE(pContext);
   
   LOG_CALLBACK("nfc_jni_sendTo_callback", status);

   nfc_jni_cb_status = status;
   
   sem_post(nfc_jni_llcp_send_sem);
}

/*
* Methods
*/
static jboolean com_android_nfc_NativeLlcpConnectionlessSocket_doSendTo(JNIEnv *e, jobject o, jint nsap, jbyteArray data)
{
   NFCSTATUS ret;
   struct timespec ts;  
   phLibNfc_Handle hLlcpSocket;
   phNfc_sData_t sSendBuffer;
   
   /* Retrieve socket handle */
   hLlcpSocket = nfc_jni_get_nfc_socket_handle(e,o);
   
   sSendBuffer.buffer = (uint8_t*)e->GetByteArrayElements(data, NULL);
   sSendBuffer.length = (uint32_t)e->GetArrayLength(data);   

   LOGD("phLibNfc_Llcp_SendTo()");
   REENTRANCE_LOCK();
   ret = phLibNfc_Llcp_SendTo(hLlcpSocket,
                              nsap,
                              &sSendBuffer,
                              nfc_jni_send_callback,
                              (void*)hLlcpSocket);
   REENTRANCE_UNLOCK();
   if(ret != NFCSTATUS_PENDING)
   {
      LOGE("phLibNfc_Llcp_SendTo() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
      return FALSE;
   } 
   LOGD("phLibNfc_Llcp_SendTo() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
   
   /* Wait for callback response */
   if(sem_wait(nfc_jni_llcp_send_sem) == -1)
      return FALSE;   


   if(nfc_jni_cb_status == NFCSTATUS_SUCCESS)
   {
      return TRUE; 
   }
   else
   {
      return FALSE;    
   }  
}

static jobject com_android_nfc_NativeLlcpConnectionlessSocket_doReceiveFrom(JNIEnv *e, jobject o, jint linkMiu)
{
   NFCSTATUS ret;
   struct timespec ts;
   uint8_t ssap;
   jobject llcpPacket = NULL;
   phLibNfc_Handle hLlcpSocket;
   phNfc_sData_t sReceiveBuffer;
   jclass clsLlcpPacket;
   jfieldID f;
   jbyteArray receivedData;
   
   /* Create new LlcpPacket object */
   if(nfc_jni_cache_object(e,"android/nfc/LlcpPacket",&(llcpPacket)) == -1)
   {
      LOGD("Find LlcpPacket class error");
      return NULL;           
   }
   
   /* Get NativeConnectionless class object */
   clsLlcpPacket = e->GetObjectClass(llcpPacket);
   if(e->ExceptionCheck())
   {
      LOGD("Get Object class error");
      return NULL;  
   } 
   
   /* Retrieve socket handle */
   hLlcpSocket = nfc_jni_get_nfc_socket_handle(e,o);
   LOGD("Socket Handle = 0x%02x",hLlcpSocket);
   
   LOGD("Link LIU = %d",linkMiu);
   
   sReceiveBuffer.buffer = (uint8_t*)malloc(linkMiu);
   sReceiveBuffer.length = linkMiu;
      
   LOGD("phLibNfc_Llcp_RecvFrom()");
   REENTRANCE_LOCK();
   ret = phLibNfc_Llcp_RecvFrom(hLlcpSocket,
                                &sReceiveBuffer,
                                nfc_jni_receive_callback,
                                &ssap);
   REENTRANCE_UNLOCK();
   if(ret != NFCSTATUS_PENDING)
   {
      LOGE("phLibNfc_Llcp_RecvFrom() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
      return NULL;
   } 
   LOGD("phLibNfc_Llcp_RecvFrom() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
   
   /* Wait for callback response */
   if(sem_wait(nfc_jni_llcp_receive_sem) == -1)
      return NULL; 
      

   if(nfc_jni_cb_status == NFCSTATUS_SUCCESS)
   {
      LOGD("Data Received From SSAP = %d\n",ssap);
      
      LOGD("Data Received Length = %d\n",sReceiveBuffer.length);
      
      /* Set Llcp Packet remote SAP */
      f = e->GetFieldID(clsLlcpPacket, "mRemoteSap", "I");
      e->SetIntField(llcpPacket, f,(jbyte)ssap);
      
      /* Set Llcp Packet Buffer */
      LOGD("Set LlcpPacket Data Buffer\n");       
      f = e->GetFieldID(clsLlcpPacket, "mDataBuffer", "[B");
      receivedData = e->NewByteArray(sReceiveBuffer.length);
      e->SetByteArrayRegion(receivedData, 0, sReceiveBuffer.length,(jbyte *)sReceiveBuffer.buffer);      
      e->SetObjectField(llcpPacket, f, receivedData); 

      return llcpPacket;
   }
   else
   {
      return FALSE;    
   }    
}

static jboolean com_android_nfc_NativeLlcpConnectionlessSocket_doClose(JNIEnv *e, jobject o)
{
   NFCSTATUS ret;
   phLibNfc_Handle hLlcpSocket;
   LOGD("Close Connectionless socket");
   
   /* Retrieve socket handle */
   hLlcpSocket = nfc_jni_get_nfc_socket_handle(e,o);

   LOGD("phLibNfc_Llcp_Close()");
   REENTRANCE_LOCK();
   ret = phLibNfc_Llcp_Close(hLlcpSocket);
   REENTRANCE_UNLOCK();
   if(ret == NFCSTATUS_SUCCESS)
   {
      LOGD("phLibNfc_Llcp_Close() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
      return TRUE;
   }
   else
   {
      LOGE("phLibNfc_Llcp_Close() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
      return FALSE;
   }
}


/*
 * JNI registration.
 */
static JNINativeMethod gMethods[] =
{
   {"doSendTo", "(I[B)Z", (void *)com_android_nfc_NativeLlcpConnectionlessSocket_doSendTo},
      
   {"doReceiveFrom", "(I)Landroid/nfc/LlcpPacket;", (void *)com_android_nfc_NativeLlcpConnectionlessSocket_doReceiveFrom},
      
   {"doClose", "()Z", (void *)com_android_nfc_NativeLlcpConnectionlessSocket_doClose},
};


int register_com_android_nfc_NativeLlcpConnectionlessSocket(JNIEnv *e)
{
    nfc_jni_llcp_send_sem = (sem_t *)malloc(sizeof(sem_t));
    nfc_jni_llcp_receive_sem = (sem_t *)malloc(sizeof(sem_t));
   if(sem_init(nfc_jni_llcp_send_sem, 0, 0) == -1)
      return -1;
      
   if(sem_init(nfc_jni_llcp_receive_sem, 0, 0) == -1)
      return -1;

   return jniRegisterNativeMethods(e,
      "com/android/nfc/NativeLlcpConnectionlessSocket",
      gMethods, NELEM(gMethods));
}

} // android namespace
