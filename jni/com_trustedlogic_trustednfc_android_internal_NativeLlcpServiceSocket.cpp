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

/**
 * File            : com_trustedlogic_trustednfc_android_internal_NativeLlcpServiceSocket.c
 * Original-Author : Trusted Logic S.A. (Daniel Tomas)
 * Created         : 04-03-2010
 */
#include <semaphore.h>

#include "trustednfc_jni.h"

static sem_t trustednfc_jni_llcp_sem;
static NFCSTATUS trustednfc_jni_cb_status = NFCSTATUS_FAILED;


namespace android {

extern phLibNfc_Handle hIncommingLlcpSocket;
extern sem_t trustednfc_jni_llcp_listen_sem;
extern void trustednfc_jni_llcp_transport_socket_err_callback(void*      pContext,
                                                              uint8_t    nErrCode);
/*
 * Callbacks
 */
static void trustednfc_jni_llcp_accept_socket_callback(void*        pContext,
                                                       NFCSTATUS    status)
{
   PHNFC_UNUSED_VARIABLE(pContext);

   LOG_CALLBACK("trustednfc_jni_llcp_accept_socket_callback", status);

   trustednfc_jni_cb_status = status;
   
   sem_post(&trustednfc_jni_llcp_sem);
}
 
 
/*
 * Methods
 */ 
static jobject com_trustedlogic_trustednfc_android_internal_NativeLlcpServiceSocket_doAccept(JNIEnv *e, jobject o, int timeout, jint miu, jint rw, jint linearBufferLength)
{
   NFCSTATUS ret;
   struct timespec ts;
   phLibNfc_Llcp_sSocketOptions_t sOptions;
   phNfc_sData_t sWorkingBuffer;
   jfieldID f;   
   jclass clsNativeLlcpSocket;
   jobject clientSocket = 0;

   /* Wait for connection request */
   if(timeout != 0)
   {
      LOGD("Accept timeout set to %d s\n", timeout);
      clock_gettime(CLOCK_REALTIME, &ts);
      ts.tv_sec  += timeout; 
  
      /* Wait for tag Notification */
      if(sem_timedwait(&trustednfc_jni_llcp_listen_sem, &ts) == -1)
      {
         return NULL;   
      }
   }
   else
   {
      /* Wait for tag Notification */
      if(sem_wait(&trustednfc_jni_llcp_listen_sem) == -1)
      {
         return NULL;   
      }   
   }
   
   /* Set socket options with the socket options of the service */
   sOptions.miu = miu;
   sOptions.rw = rw;
   
   /* Allocate Working buffer length */
   sWorkingBuffer.buffer = (uint8_t*)malloc((miu*rw)+miu+linearBufferLength);
   sWorkingBuffer.length = (miu*rw)+ miu + linearBufferLength;
   
   /* Accept the incomming socket */
   LOGD("phLibNfc_Llcp_Accept()");
   REENTRANCE_LOCK();
   ret = phLibNfc_Llcp_Accept( hIncommingLlcpSocket,
                               &sOptions,
                               &sWorkingBuffer,
                               trustednfc_jni_llcp_transport_socket_err_callback,
                               trustednfc_jni_llcp_accept_socket_callback,
                               (void*)hIncommingLlcpSocket);
   REENTRANCE_UNLOCK();
   if(ret != NFCSTATUS_PENDING)
   {
      LOGE("phLibNfc_Llcp_Accept() returned 0x%04x[%s]", ret, trustednfc_jni_get_status_name(ret));
      return NULL;
   }                                
   LOGD("phLibNfc_Llcp_Accept() returned 0x%04x[%s]", ret, trustednfc_jni_get_status_name(ret));
                               
   /* Wait for tag Notification */
   if(sem_wait(&trustednfc_jni_llcp_sem) == -1)
   {
         return NULL;   
   }
   
   if(trustednfc_jni_cb_status == NFCSTATUS_SUCCESS)
   {
      /* Create new LlcpSocket object */
      if(trustednfc_jni_cache_object(e,"com/trustedlogic/trustednfc/android/internal/NativeLlcpSocket",&(clientSocket)) == -1)
      {
         LOGD("LLCP Socket creation error");
         return NULL;           
      } 
   
      /* Get NativeConnectionOriented class object */
      clsNativeLlcpSocket = e->GetObjectClass(clientSocket);
      if(e->ExceptionCheck())
      {
         LOGD("LLCP Socket get class object error");
         return NULL;  
      }
   
      /* Set socket handle */
      f = e->GetFieldID(clsNativeLlcpSocket, "mHandle", "I");
      e->SetIntField(clientSocket, f,(jint)hIncommingLlcpSocket);
      LOGD("socket Handle = %02x\n",hIncommingLlcpSocket);  
   
      /* Set socket MIU */
      f = e->GetFieldID(clsNativeLlcpSocket, "mLocalMiu", "I");
      e->SetIntField(clientSocket, f,(jint)miu);
      LOGD("socket MIU = %d\n",miu);  
   
      /* Set socket RW */
      f = e->GetFieldID(clsNativeLlcpSocket, "mLocalRw", "I");
      e->SetIntField(clientSocket, f,(jint)rw);
      LOGD("socket RW = %d\n",rw);   
                               
                           
      return clientSocket;   
   
   }
   else
   {
      return NULL;
   } 
}

static jboolean com_trustedlogic_trustednfc_android_internal_NativeLlcpServiceSocket_doClose(JNIEnv *e, jobject o)
{
   NFCSTATUS ret;
   phLibNfc_Handle hLlcpSocket;
   LOGD("Close Service socket");
   
   /* Retrieve socket handle */
   hLlcpSocket = trustednfc_jni_get_nfc_socket_handle(e,o);

   REENTRANCE_LOCK();
   ret = phLibNfc_Llcp_Close(hLlcpSocket);
   REENTRANCE_UNLOCK();
   if(ret == NFCSTATUS_SUCCESS)
   {
      LOGD("Close Service socket OK");
      return TRUE;
   }
   else
   {
      LOGD("Close Service socket KO");
      return FALSE;
   }
}


/*
 * JNI registration.
 */
static JNINativeMethod gMethods[] =
{
   {"doAccept", "(IIII)Lcom/trustedlogic/trustednfc/android/internal/NativeLlcpSocket;",
      (void *)com_trustedlogic_trustednfc_android_internal_NativeLlcpServiceSocket_doAccept},
      
   {"doClose", "()Z",
      (void *)com_trustedlogic_trustednfc_android_internal_NativeLlcpServiceSocket_doClose},
};


int register_com_trustedlogic_trustednfc_android_internal_NativeLlcpServiceSocket(JNIEnv *e)
{
   if(sem_init(&trustednfc_jni_llcp_sem, 0, 0) == -1)
      return -1;

   return jniRegisterNativeMethods(e,
      "com/trustedlogic/trustednfc/android/internal/NativeLlcpServiceSocket",
      gMethods, NELEM(gMethods));
}

} // namespace android
