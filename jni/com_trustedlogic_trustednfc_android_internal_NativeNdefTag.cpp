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

#include "trustednfc_jni.h"

static sem_t trustednfc_jni_ndef_tag_sem;
static phLibNfc_Data_t     trustednfc_jni_ndef_rw;
static NFCSTATUS           trustednfc_jni_cb_status = NFCSTATUS_FAILED;

/* Shared with NfcTag module */
extern uint8_t    trustednfc_jni_is_ndef;
extern uint8_t *  trustednfc_jni_ndef_buf;
extern uint32_t   trustednfc_jni_ndef_buf_len;

namespace android {

static void trustednfc_jni_tag_rw_callback(void *pContext, NFCSTATUS status)
{
   LOG_CALLBACK("trustednfc_jni_tag_rw_callback", status);

   trustednfc_jni_cb_status = status;

   sem_post(&trustednfc_jni_ndef_tag_sem);
}

static jbyteArray com_android_nfc_NativeNdefTag_doRead(JNIEnv *e,
   jobject o)
{
   phLibNfc_Handle handle = 0;
   jbyteArray buf = NULL;
   NFCSTATUS         status;
   
   CONCURRENCY_LOCK();

   handle = trustednfc_jni_get_nfc_tag_handle(e, o);

   trustednfc_jni_ndef_rw.length = trustednfc_jni_ndef_buf_len;
   trustednfc_jni_ndef_rw.buffer = trustednfc_jni_ndef_buf;

   LOGD("phLibNfc_Ndef_Read()");
   REENTRANCE_LOCK();
   status = phLibNfc_Ndef_Read( handle,
                                &trustednfc_jni_ndef_rw,
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
   sem_wait(&trustednfc_jni_ndef_tag_sem);

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

static jboolean com_android_nfc_NativeNdefTag_doWrite(JNIEnv *e,
   jobject o, jbyteArray buf)
{
   NFCSTATUS      status;
   jboolean       result = JNI_FALSE;

   CONCURRENCY_LOCK();

   phLibNfc_Handle handle = trustednfc_jni_get_nfc_tag_handle(e, o);

   trustednfc_jni_ndef_rw.length = (uint32_t)e->GetArrayLength(buf);
   trustednfc_jni_ndef_rw.buffer = (uint8_t *)e->GetByteArrayElements(buf, NULL);

   LOGD("phLibNfc_Ndef_Write()");
   REENTRANCE_LOCK();
   status  = phLibNfc_Ndef_Write(handle, &trustednfc_jni_ndef_rw,trustednfc_jni_tag_rw_callback, (void *)e);
   REENTRANCE_UNLOCK();
   if(status != NFCSTATUS_PENDING)
   {
      LOGE("phLibNfc_Ndef_Write() returned 0x%04x[%s]", status, trustednfc_jni_get_status_name(status));
      goto clean_and_return;
   }
   LOGD("phLibNfc_Ndef_Write() returned 0x%04x[%s]", status, trustednfc_jni_get_status_name(status));

   /* Wait for callback response */
   sem_wait(&trustednfc_jni_ndef_tag_sem);

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

/*
 * JNI registration.
 */
static JNINativeMethod gMethods[] =
{
   {"doRead", "()[B",
      (void *)com_android_nfc_NativeNdefTag_doRead},
   {"doWrite", "([B)Z",
      (void *)com_android_nfc_NativeNdefTag_doWrite},
};

int register_com_android_nfc_NativeNdefTag(JNIEnv *e)
{
   if(sem_init(&trustednfc_jni_ndef_tag_sem, 0, 0) == -1)
      return -1;

   return jniRegisterNativeMethods(e,
      "com/android/nfc/NativeNdefTag",
      gMethods, NELEM(gMethods));
}

} // namespace android
