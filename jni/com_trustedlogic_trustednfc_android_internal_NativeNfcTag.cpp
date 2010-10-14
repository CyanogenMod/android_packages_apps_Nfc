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
 * File            : com_trustedlogic_trustednfc_android_internal_NativeNfcTag.c
 * Original-Author : Trusted Logic S.A. (Jeremie Corbier)
 * Created         : 27-08-2009
 */
#include <semaphore.h>

#include "trustednfc_jni.h"

static sem_t trustednfc_jni_tag_sem;
static NFCSTATUS trustednfc_jni_cb_status = NFCSTATUS_FAILED;
static phLibNfc_Data_t trustednfc_jni_ndef_rw;
static phLibNfc_Handle handle;
uint8_t trustednfc_jni_is_ndef = -1;
uint8_t *trustednfc_jni_ndef_buf = NULL;
uint32_t trustednfc_jni_ndef_buf_len = 0;





namespace android {

extern void trustednfc_jni_restart_discovery(struct trustednfc_jni_native_data *nat);

/*
 * Callbacks
 */
 static void trustednfc_jni_tag_rw_callback(void *pContext, NFCSTATUS status)
{
   LOG_CALLBACK("trustednfc_jni_tag_rw_callback", status);

   trustednfc_jni_cb_status = status;

   sem_post(&trustednfc_jni_tag_sem);
}

static void trustednfc_jni_connect_callback(void *pContext,
   phLibNfc_Handle hRemoteDev,
   phLibNfc_sRemoteDevInformation_t *psRemoteDevInfo, NFCSTATUS status)
{
   LOG_CALLBACK("trustednfc_jni_connect_callback", status);

   trustednfc_jni_cb_status = status;

   sem_post(&trustednfc_jni_tag_sem);
}

static void trustednfc_jni_checkndef_callback(void *pContext,
   phLibNfc_ChkNdef_Info_t info, NFCSTATUS status)
{
   LOG_CALLBACK("trustednfc_jni_checkndef_callback", status);

   if(status == NFCSTATUS_OK)
   {
      if(trustednfc_jni_ndef_buf)
      {
         free(trustednfc_jni_ndef_buf);
      }
      trustednfc_jni_ndef_buf_len = info.MaxNdefMsgLength;
      trustednfc_jni_ndef_buf = (uint8_t*)malloc(trustednfc_jni_ndef_buf_len);
      trustednfc_jni_is_ndef = 1;
   }
   else
   {
      trustednfc_jni_is_ndef = 0;   
   }

   sem_post(&trustednfc_jni_tag_sem);
}

static void trustednfc_jni_disconnect_callback(void *pContext,
   phLibNfc_Handle hRemoteDev, NFCSTATUS status)
{
   LOG_CALLBACK("trustednfc_jni_disconnect_callback", status);

   if(trustednfc_jni_ndef_buf)
   {
      free(trustednfc_jni_ndef_buf);
   }
   trustednfc_jni_ndef_buf = NULL;
   trustednfc_jni_ndef_buf_len = 0;
   trustednfc_jni_is_ndef = -1;
   
   trustednfc_jni_cb_status = status;

   sem_post(&trustednfc_jni_tag_sem);
}

static void trustednfc_jni_async_disconnect_callback(void *pContext,
   phLibNfc_Handle hRemoteDev, NFCSTATUS status)
{
   LOG_CALLBACK("trustednfc_jni_async_disconnect_callback", status);

   if(trustednfc_jni_ndef_buf)
   {
      free(trustednfc_jni_ndef_buf);
   }
   trustednfc_jni_ndef_buf = NULL;
   trustednfc_jni_ndef_buf_len = 0;
   trustednfc_jni_is_ndef = -1;
}

static void trustednfc_jni_presence_check_callback(void* pContext,NFCSTATUS  status)
{   
   LOG_CALLBACK("trustednfc_jni_presence_check_callback", status);

   trustednfc_jni_cb_status = status;

   sem_post(&trustednfc_jni_tag_sem);
}

static void trustednfc_jni_async_presence_check_callback(void* pContext,NFCSTATUS  status)
{
   NFCSTATUS ret;
   JNIEnv* env = (JNIEnv*)pContext;

   LOG_CALLBACK("trustednfc_jni_async_presence_check_callback", status);

   if(status != NFCSTATUS_SUCCESS)
   {
      /* Disconnect & Restart Polling loop */
      LOGI("Tag removed from the RF Field\n");

      LOGD("phLibNfc_RemoteDev_Disconnect(async)");
      REENTRANCE_LOCK();
      ret = phLibNfc_RemoteDev_Disconnect(handle, NFC_DISCOVERY_CONTINUE, trustednfc_jni_async_disconnect_callback,(void*)handle);
      REENTRANCE_UNLOCK();
      if(ret != NFCSTATUS_PENDING)
      {
         LOGE("phLibNfc_RemoteDev_Disconnect() returned 0x%04x[%s]", ret, trustednfc_jni_get_status_name(ret));
         trustednfc_jni_restart_discovery(trustednfc_jni_get_nat_ext(env));
         return;
      }
      LOGD("phLibNfc_RemoteDev_Disconnect() returned 0x%04x[%s]", ret, trustednfc_jni_get_status_name(ret));
   }
   else
   {
//      usleep(100000);
      LOGD("phLibNfc_RemoteDev_CheckPresence(async)");
      /* Presence Check */
      REENTRANCE_LOCK();
      ret = phLibNfc_RemoteDev_CheckPresence(handle,trustednfc_jni_async_presence_check_callback, (void*)env);
      REENTRANCE_UNLOCK();
      if(ret != NFCSTATUS_PENDING)
      {
         LOGE("phLibNfc_RemoteDev_CheckPresence() returned 0x%04x[%s]", ret, trustednfc_jni_get_status_name(ret));
         return;
      }
      LOGD("phLibNfc_RemoteDev_CheckPresence() returned 0x%04x[%s]", ret, trustednfc_jni_get_status_name(ret));
   }
}



static phNfc_sData_t *trustednfc_jni_transceive_buffer;

static void trustednfc_jni_transceive_callback(void *pContext,
   phLibNfc_Handle handle, phNfc_sData_t *pResBuffer, NFCSTATUS status)
{
   LOG_CALLBACK("trustednfc_jni_transceive_callback", status);
  
   trustednfc_jni_cb_status = status;
   trustednfc_jni_transceive_buffer = pResBuffer;

   sem_post(&trustednfc_jni_tag_sem);
}

/* Functions */
static jbyteArray com_trustedlogic_trustednfc_android_internal_NativeNfcTag_doRead(JNIEnv *e,
   jobject o)
{
   NFCSTATUS status;
   phLibNfc_Handle handle = 0;
   jbyteArray buf = NULL;

   CONCURRENCY_LOCK();

   handle = trustednfc_jni_get_nfc_tag_handle(e, o);

   trustednfc_jni_ndef_rw.length = trustednfc_jni_ndef_buf_len;
   trustednfc_jni_ndef_rw.buffer = trustednfc_jni_ndef_buf;

   LOGD("phLibNfc_Ndef_Read()");
   REENTRANCE_LOCK();
   status = phLibNfc_Ndef_Read(handle, &trustednfc_jni_ndef_rw,
                               phLibNfc_Ndef_EBegin, 
                               trustednfc_jni_tag_rw_callback, 
                               (void *)e);
   REENTRANCE_UNLOCK();
   if(status != NFCSTATUS_PENDING)
   {
      LOGE("phLibNfc_Ndef_Read() returned 0x%04x[%s]", status, trustednfc_jni_get_status_name(status));
      goto clean_and_return;
   }
   LOGD("phLibNfc_Ndef_Read() returned 0x%04x[%s]", status, trustednfc_jni_get_status_name(status));
    
   /* Wait for callback response */
   sem_wait(&trustednfc_jni_tag_sem);

   if(trustednfc_jni_cb_status != NFCSTATUS_SUCCESS)
   {
      goto clean_and_return;
   }

   buf = e->NewByteArray(trustednfc_jni_ndef_rw.length);
   e->SetByteArrayRegion(buf, 0, trustednfc_jni_ndef_rw.length,
      (jbyte *)trustednfc_jni_ndef_rw.buffer);

clean_and_return:

   CONCURRENCY_UNLOCK();

   return buf;
}
 

static jboolean com_trustedlogic_trustednfc_android_internal_NativeNfcTag_doWrite(JNIEnv *e,
   jobject o, jbyteArray buf)
{
   NFCSTATUS   status;
   jboolean    result = JNI_FALSE;

   phLibNfc_Handle handle = trustednfc_jni_get_nfc_tag_handle(e, o);

   CONCURRENCY_LOCK();

   trustednfc_jni_ndef_rw.length = (uint32_t)e->GetArrayLength(buf);
   trustednfc_jni_ndef_rw.buffer = (uint8_t *)e->GetByteArrayElements(buf, NULL);

   LOGD("phLibNfc_Ndef_Write()");
   LOGD("Ndef Handle :0x%x\n",handle);
   LOGD("Ndef buffer length : %d", trustednfc_jni_ndef_rw.length);
   REENTRANCE_LOCK();
   status = phLibNfc_Ndef_Write(handle, &trustednfc_jni_ndef_rw,trustednfc_jni_tag_rw_callback, (void *)e);
   REENTRANCE_UNLOCK();
   if(status != NFCSTATUS_PENDING)
   {
      LOGE("phLibNfc_Ndef_Write() returned 0x%04x[%s]", status, trustednfc_jni_get_status_name(status));
      goto clean_and_return;
   }
   LOGD("phLibNfc_Ndef_Write() returned 0x%04x[%s]", status, trustednfc_jni_get_status_name(status));

   /* Wait for callback response */
   sem_wait(&trustednfc_jni_tag_sem);

   if(trustednfc_jni_cb_status != NFCSTATUS_SUCCESS)
   {
      goto clean_and_return;
   }

   result = JNI_TRUE;

clean_and_return:
   if (result != JNI_TRUE)
   {
      e->ReleaseByteArrayElements(buf, (jbyte *)trustednfc_jni_ndef_rw.buffer, JNI_ABORT);
   }
   CONCURRENCY_UNLOCK();
   return result;
}

static jboolean com_trustedlogic_trustednfc_android_internal_NativeNfcTag_doConnect(JNIEnv *e,
   jobject o)
{
   phLibNfc_Handle handle = 0;
   jclass cls;
   jfieldID f;
   NFCSTATUS status;
   jboolean result = JNI_FALSE;

   CONCURRENCY_LOCK();

   handle = trustednfc_jni_get_nfc_tag_handle(e, o);

   LOGD("phLibNfc_RemoteDev_Connect(RW)");
   REENTRANCE_LOCK();
   status = phLibNfc_RemoteDev_Connect(handle, trustednfc_jni_connect_callback,(void *)e);
   REENTRANCE_UNLOCK();
   if(status != NFCSTATUS_PENDING)
   {
      LOGE("phLibNfc_RemoteDev_Connect(RW) returned 0x%04x[%s]", status, trustednfc_jni_get_status_name(status));
      goto clean_and_return;
   }
   LOGD("phLibNfc_RemoteDev_Connect(RW) returned 0x%04x[%s]", status, trustednfc_jni_get_status_name(status));

   /* Wait for callback response */
   sem_wait(&trustednfc_jni_tag_sem);
   
   /* Connect Status */
   if(trustednfc_jni_cb_status != NFCSTATUS_SUCCESS)
   {
      goto clean_and_return;
   }

   result = JNI_TRUE;

clean_and_return:
   CONCURRENCY_UNLOCK();
   return result;
}

static jboolean com_trustedlogic_trustednfc_android_internal_NativeNfcTag_doDisconnect(JNIEnv *e, jobject o)
{
   phLibNfc_Handle handle = 0;
   jclass cls;
   jfieldID f;
   NFCSTATUS status;
   jboolean result = JNI_FALSE;

   CONCURRENCY_LOCK();

   handle = trustednfc_jni_get_nfc_tag_handle(e, o);
   
   /* Disconnect */
   LOGI("Disconnecting from target (handle = 0x%x)", handle);
   
   /* Presence Check */
   do
   {
//      usleep(100000);
      LOGD("phLibNfc_RemoteDev_CheckPresence()");
      REENTRANCE_LOCK();
      status = phLibNfc_RemoteDev_CheckPresence(handle,trustednfc_jni_presence_check_callback,(void *)e);
      REENTRANCE_UNLOCK();
      if(status != NFCSTATUS_PENDING)
      {
         LOGE("phLibNfc_RemoteDev_CheckPresence() returned 0x%04x[%s]", status, trustednfc_jni_get_status_name(status));
         /* TODO: handle error */
         goto clean_and_return;
      }
      LOGD("phLibNfc_RemoteDev_CheckPresence() returned 0x%04x[%s]", status, trustednfc_jni_get_status_name(status));

      /* Wait for callback response */
      sem_wait(&trustednfc_jni_tag_sem);
   }
   while(trustednfc_jni_cb_status == NFCSTATUS_SUCCESS);

   LOGI("Tag removed from the RF Field\n");

   LOGD("phLibNfc_RemoteDev_Disconnect()");
   REENTRANCE_LOCK();
   status = phLibNfc_RemoteDev_Disconnect(handle, NFC_DISCOVERY_CONTINUE,
                                          trustednfc_jni_disconnect_callback, (void *)e);
   REENTRANCE_UNLOCK();
   if(status != NFCSTATUS_PENDING)
   {
      LOGE("phLibNfc_RemoteDev_Disconnect() returned 0x%04x[%s]", status, trustednfc_jni_get_status_name(status));
      trustednfc_jni_restart_discovery(trustednfc_jni_get_nat_ext(e));
      goto clean_and_return;
   }
   LOGD("phLibNfc_RemoteDev_Disconnect() returned 0x%04x[%s]", status, trustednfc_jni_get_status_name(status));

   /* Wait for callback response */
   sem_wait(&trustednfc_jni_tag_sem);
   
   /* Connect Status */
   if(trustednfc_jni_cb_status != NFCSTATUS_SUCCESS)
   {
      goto clean_and_return;
   }

   result = JNI_TRUE;

clean_and_return:
   CONCURRENCY_UNLOCK();
   return result;
}

static void com_trustedlogic_trustednfc_android_internal_NativeNfcTag_doAsyncDisconnect(JNIEnv *e, jobject o)
{
   NFCSTATUS status;

   handle = trustednfc_jni_get_nfc_tag_handle(e, o);
   
   /* Disconnect */
   LOGI("Disconnecting Asynchronously from target (handle = 0x%x)", handle);

   LOGD("phLibNfc_RemoteDev_CheckPresence(async)");
   REENTRANCE_LOCK();
   status = phLibNfc_RemoteDev_CheckPresence(handle,trustednfc_jni_async_presence_check_callback,(void*)e);
   REENTRANCE_UNLOCK();
   if(status != NFCSTATUS_PENDING)
   {
      LOGE("phLibNfc_RemoteDev_CheckPresence(async) returned 0x%04x[%s]", status, trustednfc_jni_get_status_name(status));
      goto disconnect_on_failure;
   }
   LOGD("phLibNfc_RemoteDev_CheckPresence(async) returned 0x%04x[%s]", status, trustednfc_jni_get_status_name(status));

   return;

disconnect_on_failure:
   LOGD("phLibNfc_RemoteDev_Disconnect(async)");
   REENTRANCE_LOCK();
   status = phLibNfc_RemoteDev_Disconnect(handle, NFC_DISCOVERY_CONTINUE, trustednfc_jni_async_disconnect_callback,(void*)handle);
   REENTRANCE_UNLOCK();
   if(status != NFCSTATUS_PENDING)
   {
      LOGE("phLibNfc_RemoteDev_Disconnect() returned 0x%04x[%s]", status, trustednfc_jni_get_status_name(status));
      trustednfc_jni_restart_discovery(trustednfc_jni_get_nat_ext(e));
      return;
   }
   LOGD("phLibNfc_RemoteDev_Disconnect() returned 0x%04x[%s]", status, trustednfc_jni_get_status_name(status));
}

static jbyteArray com_trustedlogic_trustednfc_android_internal_NativeNfcTag_doTransceive(JNIEnv *e,
   jobject o, jbyteArray data)
{
   uint8_t offset = 0;
   uint8_t *buf;
   uint32_t buflen;
   phLibNfc_sTransceiveInfo_t transceive_info;
   jbyteArray result = NULL;
   int res;
   jstring type = trustednfc_jni_get_nfc_tag_type(e, o); 
   const char* str = e->GetStringUTFChars(type, 0);   
   phLibNfc_Handle handle = trustednfc_jni_get_nfc_tag_handle(e, o);
   NFCSTATUS status;

   CONCURRENCY_LOCK();

   LOGD("Tag %s\n", str);

   buf = (uint8_t *)e->GetByteArrayElements(data, NULL);
   buflen = (uint32_t)e->GetArrayLength(data);
 
   /* Prepare transceive info structure */
   if((res = strcmp(str, "Mifare1K") == 0) || (res = strcmp(str, "Mifare4K") == 0) || (res = strcmp(str, "MifareUL") == 0))
   {
      offset = 2;
      transceive_info.cmd.MfCmd = (phNfc_eMifareCmdList_t)buf[0];
      transceive_info.addr = (uint8_t)buf[1];
   }
   else if((res = strcmp(str, "Felica") == 0))
   {
      transceive_info.cmd.FelCmd = phNfc_eFelica_Raw;
      transceive_info.addr = 0;
   }
   else if((res = strcmp(str, "Iso14443") == 0))
   {
      transceive_info.cmd.Iso144434Cmd = phNfc_eIso14443_4_Raw;
      transceive_info.addr = 0;
   }
   else if((res = strcmp(str, "Jewel") == 0))
   {
      transceive_info.cmd.JewelCmd = phNfc_eJewel_Raw;
      transceive_info.addr = 0;
   }

   /* Free memory */ 
   e->ReleaseStringUTFChars(type, str); 

   transceive_info.sSendData.buffer = buf + offset;
   transceive_info.sSendData.length = buflen - offset;
   transceive_info.sRecvData.buffer = (uint8_t*)malloc(1024);
   transceive_info.sRecvData.length = 1024;

   if(transceive_info.sRecvData.buffer == NULL)
   {
      goto clean_and_return;
   }

   LOGD("phLibNfc_RemoteDev_Transceive()");
   REENTRANCE_LOCK();
   status = phLibNfc_RemoteDev_Transceive(handle, &transceive_info,
         trustednfc_jni_transceive_callback, (void *)e);
   REENTRANCE_UNLOCK();
   if(status != NFCSTATUS_PENDING)
   {
      LOGE("phLibNfc_RemoteDev_Transceive() returned 0x%04x[%s]", status, trustednfc_jni_get_status_name(status));
      goto clean_and_return;
   }
   LOGD("phLibNfc_RemoteDev_Transceive() returned 0x%04x[%s]", status, trustednfc_jni_get_status_name(status));

   /* Wait for callback response */
   sem_wait(&trustednfc_jni_tag_sem);

   if(trustednfc_jni_cb_status != NFCSTATUS_SUCCESS)
   {
      goto clean_and_return;
   }

   /* Copy results back to Java */
   result = e->NewByteArray(trustednfc_jni_transceive_buffer->length);
   if(result != NULL)
   {
      e->SetByteArrayRegion(result, 0,
         trustednfc_jni_transceive_buffer->length,
         (jbyte *)trustednfc_jni_transceive_buffer->buffer);
   }

clean_and_return:
   if(transceive_info.sRecvData.buffer != NULL)
   {
      free(transceive_info.sRecvData.buffer);
   }

   e->ReleaseByteArrayElements(data,
      (jbyte *)transceive_info.sSendData.buffer, JNI_ABORT);

   CONCURRENCY_UNLOCK();

   return result;
}

static jboolean com_trustedlogic_trustednfc_android_internal_NativeNfcTag_checkNDEF(JNIEnv *e, jobject o)
{
   phLibNfc_Handle handle = 0;
   NFCSTATUS status;
   jboolean result = JNI_FALSE;

   CONCURRENCY_LOCK();

   handle = trustednfc_jni_get_nfc_tag_handle(e, o);

   LOGD("phLibNfc_Ndef_CheckNdef()");
   REENTRANCE_LOCK();
   status = phLibNfc_Ndef_CheckNdef(handle, trustednfc_jni_checkndef_callback,(void *)e);
   REENTRANCE_UNLOCK();
   if(status != NFCSTATUS_PENDING)
   {
      LOGE("phLibNfc_Ndef_CheckNdef() returned 0x%04x[%s]", status, trustednfc_jni_get_status_name(status));
      goto clean_and_return;
   }
   LOGD("phLibNfc_Ndef_CheckNdef() returned 0x%04x[%s]", status, trustednfc_jni_get_status_name(status));

   /* Wait for callback response */
   sem_wait(&trustednfc_jni_tag_sem);

   if (trustednfc_jni_is_ndef == 0)
   {
      goto clean_and_return;
   }

   result = JNI_TRUE;

clean_and_return:
   CONCURRENCY_UNLOCK();
   return result;
}



/*
 * JNI registration.
 */
static JNINativeMethod gMethods[] =
{
   {"doConnect", "()Z",
      (void *)com_trustedlogic_trustednfc_android_internal_NativeNfcTag_doConnect},
   {"doDisconnect", "()Z",
      (void *)com_trustedlogic_trustednfc_android_internal_NativeNfcTag_doDisconnect},
   {"doAsyncDisconnect", "()V",
      (void *)com_trustedlogic_trustednfc_android_internal_NativeNfcTag_doAsyncDisconnect},
   {"doTransceive", "([B)[B",
      (void *)com_trustedlogic_trustednfc_android_internal_NativeNfcTag_doTransceive},
   {"checkNDEF", "()Z",
      (void *)com_trustedlogic_trustednfc_android_internal_NativeNfcTag_checkNDEF},
   {"doRead", "()[B",
      (void *)com_trustedlogic_trustednfc_android_internal_NativeNfcTag_doRead},
   {"doWrite", "([B)Z",
      (void *)com_trustedlogic_trustednfc_android_internal_NativeNfcTag_doWrite},
};

int register_com_trustedlogic_trustednfc_android_internal_NativeNfcTag(JNIEnv *e)
{
   if(sem_init(&trustednfc_jni_tag_sem, 0, 0) == -1)
      return -1;

   return jniRegisterNativeMethods(e,
      "com/trustedlogic/trustednfc/android/internal/NativeNfcTag",
      gMethods, NELEM(gMethods));
}

} // namespace android
