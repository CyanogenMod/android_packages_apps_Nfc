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
#include <errno.h>

#include "com_android_nfc.h"

static phLibNfc_Data_t nfc_jni_ndef_rw;
static phLibNfc_Handle handle;
uint8_t *nfc_jni_ndef_buf = NULL;
uint32_t nfc_jni_ndef_buf_len = 0;



namespace android {

extern phLibNfc_Handle storedHandle;

extern void nfc_jni_restart_discovery_locked(struct nfc_jni_native_data *nat);

/*
 * Callbacks
 */
 static void nfc_jni_tag_rw_callback(void *pContext, NFCSTATUS status)
{
   struct nfc_jni_callback_data * pCallbackData = (struct nfc_jni_callback_data *) pContext;
   LOG_CALLBACK("nfc_jni_tag_rw_callback", status);

   /* Report the callback status and wake up the caller */
   pCallbackData->status = status;
   sem_post(&pCallbackData->sem);
}

static void nfc_jni_connect_callback(void *pContext,
   phLibNfc_Handle hRemoteDev,
   phLibNfc_sRemoteDevInformation_t *psRemoteDevInfo, NFCSTATUS status)
{
   struct nfc_jni_callback_data * pCallbackData = (struct nfc_jni_callback_data *) pContext;
   LOG_CALLBACK("nfc_jni_connect_callback", status);

   /* Report the callback status and wake up the caller */
   pCallbackData->status = status;
   sem_post(&pCallbackData->sem);
}

static void nfc_jni_checkndef_callback(void *pContext,
   phLibNfc_ChkNdef_Info_t info, NFCSTATUS status)
{
   struct nfc_jni_callback_data * pCallbackData = (struct nfc_jni_callback_data *) pContext;
   LOG_CALLBACK("nfc_jni_checkndef_callback", status);

   if(status == NFCSTATUS_OK)
   {
      if(nfc_jni_ndef_buf)
      {
         free(nfc_jni_ndef_buf);
      }
      nfc_jni_ndef_buf_len = info.MaxNdefMsgLength;
      nfc_jni_ndef_buf = (uint8_t*)malloc(nfc_jni_ndef_buf_len);
   }

   /* Report the callback status and wake up the caller */
   pCallbackData->status = status;
   sem_post(&pCallbackData->sem);
}

static void nfc_jni_disconnect_callback(void *pContext,
   phLibNfc_Handle hRemoteDev, NFCSTATUS status)
{
   struct nfc_jni_callback_data * pCallbackData = (struct nfc_jni_callback_data *) pContext;
   LOG_CALLBACK("nfc_jni_disconnect_callback", status);

   if(nfc_jni_ndef_buf)
   {
      free(nfc_jni_ndef_buf);
   }
   nfc_jni_ndef_buf = NULL;
   nfc_jni_ndef_buf_len = 0;

   /* Report the callback status and wake up the caller */
   pCallbackData->status = status;
   sem_post(&pCallbackData->sem);
}

static void nfc_jni_async_disconnect_callback(void *pContext,
   phLibNfc_Handle hRemoteDev, NFCSTATUS status)
{
   LOG_CALLBACK("nfc_jni_async_disconnect_callback", status);

   if(nfc_jni_ndef_buf)
   {
      free(nfc_jni_ndef_buf);
   }
   nfc_jni_ndef_buf = NULL;
   nfc_jni_ndef_buf_len = 0;
}

static void nfc_jni_presence_check_callback(void* pContext,NFCSTATUS  status)
{
   struct nfc_jni_callback_data * pCallbackData = (struct nfc_jni_callback_data *) pContext;
   LOG_CALLBACK("nfc_jni_presence_check_callback", status);

   /* Report the callback status and wake up the caller */
   pCallbackData->status = status;
   sem_post(&pCallbackData->sem);
}

static void nfc_jni_async_presence_check_callback(void* pContext,NFCSTATUS  status)
{
   NFCSTATUS ret;
   JNIEnv* env = (JNIEnv*)pContext;

   LOG_CALLBACK("nfc_jni_async_presence_check_callback", status);

   if(status != NFCSTATUS_SUCCESS)
   {
      /* Disconnect & Restart Polling loop */
      TRACE("Tag removed from the RF Field\n");

      TRACE("phLibNfc_RemoteDev_Disconnect(async)");
      REENTRANCE_LOCK();
      ret = phLibNfc_RemoteDev_Disconnect(handle, NFC_DISCOVERY_CONTINUE, nfc_jni_async_disconnect_callback,(void*)handle);
      REENTRANCE_UNLOCK();
      if(ret != NFCSTATUS_PENDING)
      {
         LOGE("phLibNfc_RemoteDev_Disconnect() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
         /* concurrency lock held while in callback */
         nfc_jni_restart_discovery_locked(nfc_jni_get_nat_ext(env));
         return;
      }
      TRACE("phLibNfc_RemoteDev_Disconnect() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
   }
   else
   {
      TRACE("phLibNfc_RemoteDev_CheckPresence(async)");
      /* Presence Check */
      REENTRANCE_LOCK();
      ret = phLibNfc_RemoteDev_CheckPresence(handle,nfc_jni_async_presence_check_callback, (void*)env);
      REENTRANCE_UNLOCK();
      if(ret != NFCSTATUS_PENDING)
      {
         LOGE("phLibNfc_RemoteDev_CheckPresence() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
         return;
      }
      TRACE("phLibNfc_RemoteDev_CheckPresence() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
   }
}



static phNfc_sData_t *nfc_jni_transceive_buffer;

static void nfc_jni_transceive_callback(void *pContext,
   phLibNfc_Handle handle, phNfc_sData_t *pResBuffer, NFCSTATUS status)
{
   struct nfc_jni_callback_data * pCallbackData = (struct nfc_jni_callback_data *) pContext;
   LOG_CALLBACK("nfc_jni_transceive_callback", status);
  
   nfc_jni_transceive_buffer = pResBuffer;

   /* Report the callback status and wake up the caller */
   pCallbackData->status = status;
   sem_post(&pCallbackData->sem);
}

static void nfc_jni_presencecheck_callback(void *pContext, NFCSTATUS status)
{
   struct nfc_jni_callback_data * pCallbackData = (struct nfc_jni_callback_data *) pContext;
   LOG_CALLBACK("nfc_jni_presencecheck_callback", status);

   /* Report the callback status and wake up the caller */
   pCallbackData->status = status;
   sem_post(&pCallbackData->sem);
}

/* Functions */
static jbyteArray com_android_nfc_NativeNfcTag_doRead(JNIEnv *e,
   jobject o)
{
   NFCSTATUS status;
   phLibNfc_Handle handle = 0;
   jbyteArray buf = NULL;
   struct nfc_jni_callback_data cb_data;

   CONCURRENCY_LOCK();

   /* Create the local semaphore */
   if (!nfc_cb_data_init(&cb_data, NULL))
   {
      goto clean_and_return;
   }

   handle = nfc_jni_get_nfc_tag_handle(e, o);

   nfc_jni_ndef_rw.length = nfc_jni_ndef_buf_len;
   nfc_jni_ndef_rw.buffer = nfc_jni_ndef_buf;

   TRACE("phLibNfc_Ndef_Read()");
   REENTRANCE_LOCK();
   status = phLibNfc_Ndef_Read(handle, &nfc_jni_ndef_rw,
                               phLibNfc_Ndef_EBegin, 
                               nfc_jni_tag_rw_callback, 
                               (void *)&cb_data);
   REENTRANCE_UNLOCK();
   if(status != NFCSTATUS_PENDING)
   {
      LOGE("phLibNfc_Ndef_Read() returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));
      goto clean_and_return;
   }
   TRACE("phLibNfc_Ndef_Read() returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));
    
   /* Wait for callback response */
   if(sem_wait(&cb_data.sem))
   {
      LOGE("Failed to wait for semaphore (errno=0x%08x)", errno);
      goto clean_and_return;
   }

   if(cb_data.status != NFCSTATUS_SUCCESS)
   {
      goto clean_and_return;
   }

   buf = e->NewByteArray(nfc_jni_ndef_rw.length);
   e->SetByteArrayRegion(buf, 0, nfc_jni_ndef_rw.length,
      (jbyte *)nfc_jni_ndef_rw.buffer);

clean_and_return:
   nfc_cb_data_deinit(&cb_data);
   CONCURRENCY_UNLOCK();

   return buf;
}
 

static jboolean com_android_nfc_NativeNfcTag_doWrite(JNIEnv *e,
   jobject o, jbyteArray buf)
{
   NFCSTATUS   status;
   jboolean    result = JNI_FALSE;
   struct nfc_jni_callback_data cb_data;

   phLibNfc_Handle handle = nfc_jni_get_nfc_tag_handle(e, o);

   CONCURRENCY_LOCK();

   /* Create the local semaphore */
   if (!nfc_cb_data_init(&cb_data, NULL))
   {
      goto clean_and_return;
   }

   nfc_jni_ndef_rw.length = (uint32_t)e->GetArrayLength(buf);
   nfc_jni_ndef_rw.buffer = (uint8_t *)e->GetByteArrayElements(buf, NULL);

   TRACE("phLibNfc_Ndef_Write()");
   TRACE("Ndef Handle :0x%x\n",handle);
   TRACE("Ndef buffer length : %d", nfc_jni_ndef_rw.length);
   REENTRANCE_LOCK();
   status = phLibNfc_Ndef_Write(handle, &nfc_jni_ndef_rw,nfc_jni_tag_rw_callback, (void *)&cb_data);
   REENTRANCE_UNLOCK();
   if(status != NFCSTATUS_PENDING)
   {
      LOGE("phLibNfc_Ndef_Write() returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));
      goto clean_and_return;
   }
   TRACE("phLibNfc_Ndef_Write() returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));

   /* Wait for callback response */
   if(sem_wait(&cb_data.sem))
   {
      LOGE("Failed to wait for semaphore (errno=0x%08x)", errno);
      goto clean_and_return;
   }

   if(cb_data.status != NFCSTATUS_SUCCESS)
   {
      goto clean_and_return;
   }

   result = JNI_TRUE;

clean_and_return:
   if (result != JNI_TRUE)
   {
      e->ReleaseByteArrayElements(buf, (jbyte *)nfc_jni_ndef_rw.buffer, JNI_ABORT);
   }
   nfc_cb_data_deinit(&cb_data);
   CONCURRENCY_UNLOCK();
   return result;
}

static jboolean com_android_nfc_NativeNfcTag_doConnect(JNIEnv *e,
   jobject o)
{
   phLibNfc_Handle handle = 0;
   jclass cls;
   jfieldID f;
   NFCSTATUS status;
   jboolean result = JNI_FALSE;
   struct nfc_jni_callback_data cb_data;

   CONCURRENCY_LOCK();

   handle = nfc_jni_get_nfc_tag_handle(e, o);

   /* Create the local semaphore */
   if (!nfc_cb_data_init(&cb_data, NULL))
   {
      goto clean_and_return;
   }

   TRACE("phLibNfc_RemoteDev_Connect(RW)");
   REENTRANCE_LOCK();
   status = phLibNfc_RemoteDev_Connect(handle, nfc_jni_connect_callback,(void *)&cb_data);
   REENTRANCE_UNLOCK();
   if(status != NFCSTATUS_PENDING)
   {
      LOGE("phLibNfc_RemoteDev_Connect(RW) returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));
      goto clean_and_return;
   }
   TRACE("phLibNfc_RemoteDev_Connect(RW) returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));

   /* Wait for callback response */
   if(sem_wait(&cb_data.sem))
   {
      LOGE("Failed to wait for semaphore (errno=0x%08x)", errno);
      goto clean_and_return;
   }
   
   /* Connect Status */
   if(cb_data.status != NFCSTATUS_SUCCESS)
   {
      goto clean_and_return;
   }

   result = JNI_TRUE;

clean_and_return:
   nfc_cb_data_deinit(&cb_data);
   CONCURRENCY_UNLOCK();
   return result;
}

static jboolean com_android_nfc_NativeNfcTag_doDisconnect(JNIEnv *e, jobject o)
{
   phLibNfc_Handle handle = 0;
   jclass cls;
   jfieldID f;
   NFCSTATUS status;
   jboolean result = JNI_FALSE;
   struct nfc_jni_callback_data cb_data;

   CONCURRENCY_LOCK();

   handle = nfc_jni_get_nfc_tag_handle(e, o);

   /* Create the local semaphore */
   if (!nfc_cb_data_init(&cb_data, NULL))
   {
      goto clean_and_return;
   }

   /* Reset the stored handle */
   storedHandle = 0;

   /* Disconnect */
   TRACE("Disconnecting from tag (%x)", handle);
   
   /* Presence Check */
   do
   {
      TRACE("phLibNfc_RemoteDev_CheckPresence(%x)", handle);
      REENTRANCE_LOCK();
      status = phLibNfc_RemoteDev_CheckPresence(handle,nfc_jni_presence_check_callback,(void *)&cb_data);
      REENTRANCE_UNLOCK();
      if(status != NFCSTATUS_PENDING)
      {
         LOGE("phLibNfc_RemoteDev_CheckPresence(%x) returned 0x%04x[%s]", handle, status, nfc_jni_get_status_name(status));
         /* Disconnect Tag */
         break;
      }
      TRACE("phLibNfc_RemoteDev_CheckPresence(%x) returned 0x%04x[%s]", handle, status, nfc_jni_get_status_name(status));

      /* Wait for callback response */
      if(sem_wait(&cb_data.sem))
      {
         LOGE("Failed to wait for semaphore (errno=0x%08x)", errno);
         goto clean_and_return;
      }

    } while(cb_data.status == NFCSTATUS_SUCCESS);

    TRACE("Tag removed from the RF Field\n");

    TRACE("phLibNfc_RemoteDev_Disconnect(%x)", handle);
    REENTRANCE_LOCK();
    status = phLibNfc_RemoteDev_Disconnect(handle, NFC_DISCOVERY_CONTINUE,
                                          nfc_jni_disconnect_callback, (void *)&cb_data);
    REENTRANCE_UNLOCK();

    if(status == NFCSTATUS_TARGET_NOT_CONNECTED)
    {
        result = JNI_TRUE;
        TRACE("phLibNfc_RemoteDev_Disconnect() - Target already disconnected");
        goto clean_and_return;
    }
    if(status != NFCSTATUS_PENDING)
    {
        LOGE("phLibNfc_RemoteDev_Disconnect(%x) returned 0x%04x[%s]", handle, status, nfc_jni_get_status_name(status));
        nfc_jni_restart_discovery_locked(nfc_jni_get_nat_ext(e));
        goto clean_and_return;
    }
    TRACE("phLibNfc_RemoteDev_Disconnect(%x) returned 0x%04x[%s]", handle, status, nfc_jni_get_status_name(status));

    /* Wait for callback response */
    if(sem_wait(&cb_data.sem))
    {
       LOGE("Failed to wait for semaphore (errno=0x%08x)", errno);
       goto clean_and_return;
    }
   
    /* Disconnect Status */
    if(cb_data.status != NFCSTATUS_SUCCESS)
    {
        goto clean_and_return;
    }
    result = JNI_TRUE;

clean_and_return:
   nfc_cb_data_deinit(&cb_data);
   CONCURRENCY_UNLOCK();
   return result;
}

static jbyteArray com_android_nfc_NativeNfcTag_doTransceive(JNIEnv *e,
   jobject o, jbyteArray data)
{
    uint8_t offset = 0;
    uint8_t *buf;
    uint32_t buflen;
    phLibNfc_sTransceiveInfo_t transceive_info;
    jbyteArray result = NULL;
    int res;
    jstring type = nfc_jni_get_nfc_tag_type(e, o);
    const char* str = e->GetStringUTFChars(type, 0);
    phLibNfc_Handle handle = nfc_jni_get_nfc_tag_handle(e, o);
    NFCSTATUS status;
    struct nfc_jni_callback_data cb_data;

    CONCURRENCY_LOCK();

    /* Create the local semaphore */
    if (!nfc_cb_data_init(&cb_data, NULL))
    {
       goto clean_and_return;
    }

    TRACE("Tag %s\n", str);

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

    TRACE("phLibNfc_RemoteDev_Transceive()");
    REENTRANCE_LOCK();
    status = phLibNfc_RemoteDev_Transceive(handle, &transceive_info,
         nfc_jni_transceive_callback, (void *)&cb_data);
    REENTRANCE_UNLOCK();
    if(status != NFCSTATUS_PENDING)
    {
      LOGE("phLibNfc_RemoteDev_Transceive() returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));
      goto clean_and_return;
    }
    TRACE("phLibNfc_RemoteDev_Transceive() returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));

    /* Wait for callback response */
    if(sem_wait(&cb_data.sem))
    {
       LOGE("Failed to wait for semaphore (errno=0x%08x)", errno);
       goto clean_and_return;
    }

    if(cb_data.status != NFCSTATUS_SUCCESS)
    {
        goto clean_and_return;
    }

    /* Copy results back to Java */
    result = e->NewByteArray(nfc_jni_transceive_buffer->length);
    if(result != NULL)
    {
      e->SetByteArrayRegion(result, 0,
         nfc_jni_transceive_buffer->length,
         (jbyte *)nfc_jni_transceive_buffer->buffer);
    }

clean_and_return:
    if(transceive_info.sRecvData.buffer != NULL)
    {
      free(transceive_info.sRecvData.buffer);
    }

    e->ReleaseByteArrayElements(data,
      (jbyte *)transceive_info.sSendData.buffer, JNI_ABORT);

    nfc_cb_data_deinit(&cb_data);

    CONCURRENCY_UNLOCK();

    return result;
}

static jboolean com_android_nfc_NativeNfcTag_doCheckNdef(JNIEnv *e, jobject o)
{
   phLibNfc_Handle handle = 0;
   NFCSTATUS status;
   jboolean result = JNI_FALSE;
   struct nfc_jni_callback_data cb_data;

   CONCURRENCY_LOCK();

   /* Create the local semaphore */
   if (!nfc_cb_data_init(&cb_data, NULL))
   {
      goto clean_and_return;
   }

   handle = nfc_jni_get_nfc_tag_handle(e, o);

   TRACE("phLibNfc_Ndef_CheckNdef()");
   REENTRANCE_LOCK();
   status = phLibNfc_Ndef_CheckNdef(handle, nfc_jni_checkndef_callback,(void *)&cb_data);
   REENTRANCE_UNLOCK();
   if(status != NFCSTATUS_PENDING)
   {
      LOGE("phLibNfc_Ndef_CheckNdef() returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));
      goto clean_and_return;
   }
   TRACE("phLibNfc_Ndef_CheckNdef() returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));

   /* Wait for callback response */
   if(sem_wait(&cb_data.sem))
   {
      LOGE("Failed to wait for semaphore (errno=0x%08x)", errno);
      goto clean_and_return;
   }

   if (cb_data.status != NFCSTATUS_SUCCESS)
   {
      goto clean_and_return;
   }

   result = JNI_TRUE;

clean_and_return:
   nfc_cb_data_deinit(&cb_data);
   CONCURRENCY_UNLOCK();
   return result;
}

static jboolean com_android_nfc_NativeNfcTag_doPresenceCheck(JNIEnv *e, jobject o)
{
   phLibNfc_Handle handle = 0;
   NFCSTATUS status;
   jboolean result = JNI_FALSE;
   struct nfc_jni_callback_data cb_data;

   CONCURRENCY_LOCK();

   /* Create the local semaphore */
   if (!nfc_cb_data_init(&cb_data, NULL))
   {
      goto clean_and_return;
   }

   handle = nfc_jni_get_nfc_tag_handle(e, o);

   TRACE("phLibNfc_RemoteDev_CheckPresence()");
   REENTRANCE_LOCK();
   status = phLibNfc_RemoteDev_CheckPresence(handle, nfc_jni_presencecheck_callback, (void *)&cb_data);
   REENTRANCE_UNLOCK();

   if(status != NFCSTATUS_PENDING)
   {
      LOGE("phLibNfc_RemoteDev_CheckPresence() returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));
      goto clean_and_return;
   }
   TRACE("phLibNfc_RemoteDev_CheckPresence() returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));

   /* Wait for callback response */
   if(sem_wait(&cb_data.sem))
   {
      LOGE("Failed to wait for semaphore (errno=0x%08x)", errno);
      goto clean_and_return;
   }

   if (cb_data.status == NFCSTATUS_SUCCESS)
   {
       result = JNI_TRUE;
   }

clean_and_return:
   nfc_cb_data_deinit(&cb_data);
   CONCURRENCY_UNLOCK();
   return result;
}


/*
 * JNI registration.
 */
static JNINativeMethod gMethods[] =
{
   {"doConnect", "()Z",
      (void *)com_android_nfc_NativeNfcTag_doConnect},
   {"doDisconnect", "()Z",
      (void *)com_android_nfc_NativeNfcTag_doDisconnect},
   {"doTransceive", "([B)[B",
      (void *)com_android_nfc_NativeNfcTag_doTransceive},
   {"doCheckNdef", "()Z",
      (void *)com_android_nfc_NativeNfcTag_doCheckNdef},
   {"doRead", "()[B",
      (void *)com_android_nfc_NativeNfcTag_doRead},
   {"doWrite", "([B)Z",
      (void *)com_android_nfc_NativeNfcTag_doWrite},
   {"doPresenceCheck", "()Z",
      (void *)com_android_nfc_NativeNfcTag_doPresenceCheck},
};

int register_com_android_nfc_NativeNfcTag(JNIEnv *e)
{
   return jniRegisterNativeMethods(e,
      "com/android/nfc/NativeNfcTag",
      gMethods, NELEM(gMethods));
}

} // namespace android
