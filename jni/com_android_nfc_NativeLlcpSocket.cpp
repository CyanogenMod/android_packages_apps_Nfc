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

static sem_t nfc_jni_llcp_sem;
static NFCSTATUS nfc_jni_cb_status = NFCSTATUS_FAILED;

namespace android {

/*
 * Callbacks
 */
 
static void nfc_jni_disconnect_callback(void*        pContext,
                                               NFCSTATUS    status)
{
   PHNFC_UNUSED_VARIABLE(pContext);
   
   LOG_CALLBACK("nfc_jni_llcp_disconnect_callback", status);
   
   nfc_jni_cb_status = status;

   sem_post(&nfc_jni_llcp_sem);
}

 
static void nfc_jni_connect_callback(void* pContext, uint8_t nErrCode, NFCSTATUS status)
{
   PHNFC_UNUSED_VARIABLE(pContext);
   
   LOG_CALLBACK("nfc_jni_llcp_connect_callback", status);

   nfc_jni_cb_status = status;

   if(status == NFCSTATUS_SUCCESS)
   {
      LOGD("Socket connected\n");
   }
   else
   {
      LOGD("Socket not connected:");
      switch(nErrCode)
      {
         case PHFRINFC_LLCP_DM_OPCODE_SAP_NOT_ACTIVE:
            {
               LOGD("> SAP NOT ACTIVE\n");
            }break;

         case PHFRINFC_LLCP_DM_OPCODE_SAP_NOT_FOUND:
            {
               LOGD("> SAP NOT FOUND\n");
            }break;

         case PHFRINFC_LLCP_DM_OPCODE_CONNECT_REJECTED:
            {
               LOGD("> CONNECT REJECTED\n");
            }break;

         case PHFRINFC_LLCP_DM_OPCODE_CONNECT_NOT_ACCEPTED:
            {
               LOGD("> CONNECT NOT ACCEPTED\n");
            }break;

         case PHFRINFC_LLCP_DM_OPCODE_SOCKET_NOT_AVAILABLE:
            {
               LOGD("> SOCKET NOT AVAILABLE\n");
            }break;
      }
   }
   
   sem_post(&nfc_jni_llcp_sem);
} 



 
static void nfc_jni_receive_callback(void* pContext, NFCSTATUS    status)
{
   uint8_t i;
   PHNFC_UNUSED_VARIABLE(pContext);
   
   LOG_CALLBACK("nfc_jni_llcp_receive_callback", status);
   
   nfc_jni_cb_status = status;
   
   sem_post(&nfc_jni_llcp_sem);
}

static void nfc_jni_send_callback(void *pContext, NFCSTATUS status)
{
   PHNFC_UNUSED_VARIABLE(pContext);
   
   LOG_CALLBACK("nfc_jni_llcp_send_callback", status);

   nfc_jni_cb_status = status;

   sem_post(&nfc_jni_llcp_sem);
}

/*
 * Methods
 */
static jboolean com_android_nfc_NativeLlcpSocket_doConnect(JNIEnv *e, jobject o, jint nSap)
{
   NFCSTATUS ret;
   struct timespec ts;
   phLibNfc_Handle hLlcpSocket;
   
   /* Retrieve socket handle */
   hLlcpSocket = nfc_jni_get_nfc_socket_handle(e,o);
   
   LOGD("phLibNfc_Llcp_Connect(%d)",nSap);
   REENTRANCE_LOCK();
   ret = phLibNfc_Llcp_Connect(hLlcpSocket,
                               nSap,
                               nfc_jni_connect_callback,
                               (void*)hLlcpSocket);
   REENTRANCE_UNLOCK();
   if(ret != NFCSTATUS_PENDING)
   {
      LOGE("phLibNfc_Llcp_Connect(%d) returned 0x%04x[%s]", nSap, ret, nfc_jni_get_status_name(ret));
      return FALSE;
   }
   LOGD("phLibNfc_Llcp_Connect(%d) returned 0x%04x[%s]", nSap, ret, nfc_jni_get_status_name(ret));
   
   /* Wait for callback response */
   if(sem_wait(&nfc_jni_llcp_sem) == -1)
      return FALSE;
   
   if(nfc_jni_cb_status == NFCSTATUS_SUCCESS)
   {
      LOGD("LLCP Connect request OK");
      return TRUE; 
   }
   else
   {
      LOGD("LLCP Connect request KO");
      return FALSE;    
   }
}

static jboolean com_android_nfc_NativeLlcpSocket_doConnectBy(JNIEnv *e, jobject o, jstring sn)
{
   NFCSTATUS ret;
   struct timespec ts;
   phNfc_sData_t serviceName;   
   phLibNfc_Handle hLlcpSocket;
   
   /* Retrieve socket handle */
   hLlcpSocket = nfc_jni_get_nfc_socket_handle(e,o);
   
   /* Service socket */
   serviceName.buffer = (uint8_t*)e->GetStringUTFChars(sn, NULL);
   serviceName.length = (uint32_t)e->GetStringUTFLength(sn);
   
   LOGD("phLibNfc_Llcp_ConnectByUri()");
   REENTRANCE_LOCK();
   ret = phLibNfc_Llcp_ConnectByUri(hLlcpSocket,
                                    &serviceName,
                                    nfc_jni_connect_callback,
                                    (void*)hLlcpSocket);
   REENTRANCE_UNLOCK();
   if(ret != NFCSTATUS_PENDING)
   {
      LOGE("phLibNfc_Llcp_ConnectByUri() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
      return FALSE;
   }   
   LOGD("phLibNfc_Llcp_ConnectByUri() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
   
   /* Wait for callback response */
   if(sem_wait(&nfc_jni_llcp_sem) == -1)
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

static jboolean com_android_nfc_NativeLlcpSocket_doClose(JNIEnv *e, jobject o)
{
   NFCSTATUS ret;
   phLibNfc_Handle hLlcpSocket;
   
   /* Retrieve socket handle */
   hLlcpSocket = nfc_jni_get_nfc_socket_handle(e,o);
   
   LOGD("phLibNfc_Llcp_Close()");
   REENTRANCE_LOCK();
   ret = phLibNfc_Llcp_Close(hLlcpSocket);
   REENTRANCE_UNLOCK();
   if(ret != NFCSTATUS_SUCCESS)
   {
      LOGE("phLibNfc_Llcp_Close() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
      return FALSE; 
   }
   LOGD("phLibNfc_Llcp_Close() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
   return TRUE;
}

static jboolean com_android_nfc_NativeLlcpSocket_doSend(JNIEnv *e, jobject o, jbyteArray  data)
{
   NFCSTATUS ret;
   struct timespec ts;  
   phLibNfc_Handle hLlcpSocket;
   phNfc_sData_t sSendBuffer;
   
   /* Retrieve socket handle */
   hLlcpSocket = nfc_jni_get_nfc_socket_handle(e,o);
   
   sSendBuffer.buffer = (uint8_t*)e->GetByteArrayElements(data, NULL);
   sSendBuffer.length = (uint32_t)e->GetArrayLength(data);
   
   LOGD("phLibNfc_Llcp_Send()");
   REENTRANCE_LOCK();
   ret = phLibNfc_Llcp_Send(hLlcpSocket,
                            &sSendBuffer,
                            nfc_jni_send_callback,
                            (void*)hLlcpSocket);
   REENTRANCE_UNLOCK();
   if(ret != NFCSTATUS_PENDING)
   {
      LOGE("phLibNfc_Llcp_Send() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
      return FALSE;
   } 
   LOGD("phLibNfc_Llcp_Send() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
   
   /* Wait for callback response */
   if(sem_wait(&nfc_jni_llcp_sem) == -1)
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

static jint com_android_nfc_NativeLlcpSocket_doReceive(JNIEnv *e, jobject o, jbyteArray  buffer)
{
   NFCSTATUS ret;
   struct timespec ts;  
   phLibNfc_Handle hLlcpSocket;
   phNfc_sData_t sReceiveBuffer;
   
   /* Retrieve socket handle */
   hLlcpSocket = nfc_jni_get_nfc_socket_handle(e,o);
   
   sReceiveBuffer.buffer = (uint8_t*)e->GetByteArrayElements(buffer, NULL);
   sReceiveBuffer.length = (uint32_t)e->GetArrayLength(buffer);
   
   LOGD("phLibNfc_Llcp_Recv()");
   REENTRANCE_LOCK();
   ret = phLibNfc_Llcp_Recv(hLlcpSocket,
                            &sReceiveBuffer,
                            nfc_jni_receive_callback,
                            (void*)hLlcpSocket);
   REENTRANCE_UNLOCK();
   if((ret != NFCSTATUS_SUCCESS) && (ret != NFCSTATUS_PENDING))
   {
       /* Return status should be either SUCCESS or PENDING */
       LOGE("phLibNfc_Llcp_Recv() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
       return 0;
   }
   LOGD("phLibNfc_Llcp_Recv() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));

   /* Wait for callback response (happen if status is either SUCCESS or PENDING) */
   if(sem_wait(&nfc_jni_llcp_sem) == -1)
   {
      return 0;
   }

   if(nfc_jni_cb_status == NFCSTATUS_SUCCESS)
   {
      return sReceiveBuffer.length;
   }
   else
   {
      return 0;
   }
}

static jint com_android_nfc_NativeLlcpSocket_doGetRemoteSocketMIU(JNIEnv *e, jobject o)
{
   NFCSTATUS ret;
   phLibNfc_Handle hLlcpSocket;
   phLibNfc_Llcp_sSocketOptions_t   remoteSocketOption;
   
   /* Retrieve socket handle */
   hLlcpSocket = nfc_jni_get_nfc_socket_handle(e,o);   
   
   LOGD("phLibNfc_Llcp_SocketGetRemoteOptions(MIU)");
   REENTRANCE_LOCK();
   ret  = phLibNfc_Llcp_SocketGetRemoteOptions(hLlcpSocket,
                                               &remoteSocketOption);
   REENTRANCE_UNLOCK();
   if(ret == NFCSTATUS_SUCCESS)
   {
      LOGD("phLibNfc_Llcp_SocketGetRemoteOptions(MIU) returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
      return remoteSocketOption.miu;
   }
   else
   {
      LOGW("phLibNfc_Llcp_SocketGetRemoteOptions(MIU) returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
      return 0;
   }
}

static jint com_android_nfc_NativeLlcpSocket_doGetRemoteSocketRW(JNIEnv *e, jobject o)
{
   NFCSTATUS ret;
   phLibNfc_Handle hLlcpSocket;
   phLibNfc_Llcp_sSocketOptions_t   remoteSocketOption;

   /* Retrieve socket handle */
   hLlcpSocket = nfc_jni_get_nfc_socket_handle(e,o);   

   LOGD("phLibNfc_Llcp_SocketGetRemoteOptions(RW)");
   REENTRANCE_LOCK();
   ret  = phLibNfc_Llcp_SocketGetRemoteOptions(hLlcpSocket,
                                               &remoteSocketOption);
   REENTRANCE_UNLOCK();
   if(ret == NFCSTATUS_SUCCESS)
   {
      LOGD("phLibNfc_Llcp_SocketGetRemoteOptions(RW) returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
      return remoteSocketOption.rw;
   }
   else
   {
      LOGW("phLibNfc_Llcp_SocketGetRemoteOptions(RW) returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
      return 0;
   }
}


/*
 * JNI registration.
 */
static JNINativeMethod gMethods[] =
{
   {"doConnect", "(I)Z",
      (void *)com_android_nfc_NativeLlcpSocket_doConnect},

   {"doConnectBy", "(Ljava/lang/String;)Z",
      (void *)com_android_nfc_NativeLlcpSocket_doConnectBy},
      
   {"doClose", "()Z",
      (void *)com_android_nfc_NativeLlcpSocket_doClose},
      
   {"doSend", "([B)Z",
      (void *)com_android_nfc_NativeLlcpSocket_doSend},

   {"doReceive", "([B)I",
      (void *)com_android_nfc_NativeLlcpSocket_doReceive},
      
   {"doGetRemoteSocketMiu", "()I",
      (void *)com_android_nfc_NativeLlcpSocket_doGetRemoteSocketMIU},
           
   {"doGetRemoteSocketRw", "()I",
      (void *)com_android_nfc_NativeLlcpSocket_doGetRemoteSocketRW},
};


int register_com_android_nfc_NativeLlcpSocket(JNIEnv *e)
{
   if(sem_init(&nfc_jni_llcp_sem, 0, 0) == -1)
      return -1;

   return jniRegisterNativeMethods(e,
      "com/android/nfc/NativeLlcpSocket",gMethods, NELEM(gMethods));
}

} // namespace android
