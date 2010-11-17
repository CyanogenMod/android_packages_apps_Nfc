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

#include "errno.h"
#include "com_android_nfc.h"
#include "phLibNfcStatus.h"

/*
 * JNI Initialization
 */
jint JNI_OnLoad(JavaVM *jvm, void *reserved)
{
   JNIEnv *e;

   LOGD("NFC Service : loading JNI\n");

   // Check JNI version
   if(jvm->GetEnv((void **)&e, JNI_VERSION_1_6))
      return JNI_ERR;

   if (android::register_com_android_nfc_NativeNfcManager(e) == -1)
      return JNI_ERR;
   if (android::register_com_android_nfc_NativeNfcTag(e) == -1)
      return JNI_ERR;
   if (android::register_com_android_nfc_NativeP2pDevice(e) == -1)
      return JNI_ERR;
   if (android::register_com_android_nfc_NativeLlcpSocket(e) == -1)
      return JNI_ERR;
   if (android::register_com_android_nfc_NativeLlcpConnectionlessSocket(e) == -1)
      return JNI_ERR;
   if (android::register_com_android_nfc_NativeLlcpServiceSocket(e) == -1)
      return JNI_ERR;
   if (android::register_com_android_nfc_NativeNfcSecureElement(e) == -1)
      return JNI_ERR;

   return JNI_VERSION_1_6;
}

namespace android {

extern struct nfc_jni_native_data *exported_nat;

/*
 * JNI Utils
 */
int nfc_jni_cache_object(JNIEnv *e, const char *clsname,
   jobject *cached_obj)
{
   jclass cls;
   jobject obj;
   jmethodID ctor;

   cls = e->FindClass(clsname);
   if(cls == NULL)
   {
      return -1;
      LOGD("Find class error\n");
   }


   ctor = e->GetMethodID(cls, "<init>", "()V");

   obj = e->NewObject(cls, ctor);
   if(obj == NULL)
   {
      return -1;
      LOGD("Create object error\n");
   }

   *cached_obj = e->NewGlobalRef(obj);
   if(*cached_obj == NULL)
   {
      e->DeleteLocalRef(obj);
      LOGD("Global ref error\n");
      return -1;
   }

   e->DeleteLocalRef(obj);

   return 0;
}


struct nfc_jni_native_data* nfc_jni_get_nat(JNIEnv *e, jobject o)
{
   jclass c;
   jfieldID f;

   /* Retrieve native structure address */
   c = e->GetObjectClass(o);
   f = e->GetFieldID(c, "mNative", "I");
   return (struct nfc_jni_native_data*)e->GetIntField(o, f);
}

struct nfc_jni_native_data* nfc_jni_get_nat_ext(JNIEnv *e)
{
   return exported_nat;
}

static nfc_jni_native_monitor_t *nfc_jni_native_monitor = NULL;

nfc_jni_native_monitor_t* nfc_jni_init_monitor(void)
{

   pthread_mutexattr_t recursive_attr;

   pthread_mutexattr_init(&recursive_attr);
   pthread_mutexattr_settype(&recursive_attr, PTHREAD_MUTEX_RECURSIVE_NP);

   if(nfc_jni_native_monitor == NULL)
   {
      nfc_jni_native_monitor = (nfc_jni_native_monitor_t*)malloc(sizeof(nfc_jni_native_monitor_t));
   }

   if(nfc_jni_native_monitor != NULL)
   {
      memset(nfc_jni_native_monitor, 0, sizeof(nfc_jni_native_monitor_t));

      if(pthread_mutex_init(&nfc_jni_native_monitor->reentrance_mutex, &recursive_attr) == -1)
      {
         LOGE("NFC Manager Reentrance Mutex creation retruned 0x%08x", errno);
         return NULL;
      }

      if(pthread_mutex_init(&nfc_jni_native_monitor->concurrency_mutex, NULL) == -1)
      {
         LOGE("NFC Manager Concurrency Mutex creation retruned 0x%08x", errno);
         return NULL;
      }
   }

   return nfc_jni_native_monitor;
} 

nfc_jni_native_monitor_t* nfc_jni_get_monitor(void)
{
   return nfc_jni_native_monitor;
}
   

phLibNfc_Handle nfc_jni_get_p2p_device_handle(JNIEnv *e, jobject o)
{
   jclass c;
   jfieldID f;

   c = e->GetObjectClass(o);
   f = e->GetFieldID(c, "mHandle", "I");

   return e->GetIntField(o, f);
}

jshort nfc_jni_get_p2p_device_mode(JNIEnv *e, jobject o)
{
   jclass c;
   jfieldID f;

   c = e->GetObjectClass(o);
   f = e->GetFieldID(c, "mMode", "S");

   return e->GetShortField(o, f);
}


phLibNfc_Handle nfc_jni_get_nfc_tag_handle(JNIEnv *e, jobject o)
{
   jclass c;
   jfieldID f;

   c = e->GetObjectClass(o);
   f = e->GetFieldID(c, "mHandle", "I");

   return e->GetIntField(o, f);
}

phLibNfc_Handle nfc_jni_get_nfc_socket_handle(JNIEnv *e, jobject o)
{
   jclass c;
   jfieldID f;

   c = e->GetObjectClass(o);
   f = e->GetFieldID(c, "mHandle", "I");

   return e->GetIntField(o, f);
}

jstring nfc_jni_get_nfc_tag_type(JNIEnv *e, jobject o)
{
  jclass c;
  jfieldID f;
  jstring type;
   
  c = e->GetObjectClass(o);
  f = e->GetFieldID(c, "mType","Ljava/lang/String;");

  /* Read the instance field */
  type = (jstring)e->GetObjectField(o, f);
  
  return type;  
}



//Display status code
const char* nfc_jni_get_status_name(NFCSTATUS status)
{
   #define STATUS_ENTRY(status) { status, #status }
 
   struct status_entry {
      NFCSTATUS   code;
      const char  *name;
   };

   const struct status_entry sNameTable[] = {
      STATUS_ENTRY(NFCSTATUS_SUCCESS),
      STATUS_ENTRY(NFCSTATUS_FAILED),
      STATUS_ENTRY(NFCSTATUS_INVALID_PARAMETER),
      STATUS_ENTRY(NFCSTATUS_INSUFFICIENT_RESOURCES),
      STATUS_ENTRY(NFCSTATUS_TARGET_LOST),
      STATUS_ENTRY(NFCSTATUS_INVALID_HANDLE),
      STATUS_ENTRY(NFCSTATUS_MULTIPLE_TAGS),
      STATUS_ENTRY(NFCSTATUS_ALREADY_REGISTERED),
      STATUS_ENTRY(NFCSTATUS_FEATURE_NOT_SUPPORTED),
      STATUS_ENTRY(NFCSTATUS_SHUTDOWN),
      STATUS_ENTRY(NFCSTATUS_ABORTED),
      STATUS_ENTRY(NFCSTATUS_REJECTED ),
      STATUS_ENTRY(NFCSTATUS_NOT_INITIALISED),
      STATUS_ENTRY(NFCSTATUS_PENDING),
      STATUS_ENTRY(NFCSTATUS_BUFFER_TOO_SMALL),
      STATUS_ENTRY(NFCSTATUS_ALREADY_INITIALISED),
      STATUS_ENTRY(NFCSTATUS_BUSY),
      STATUS_ENTRY(NFCSTATUS_TARGET_NOT_CONNECTED),
      STATUS_ENTRY(NFCSTATUS_MULTIPLE_PROTOCOLS),
      STATUS_ENTRY(NFCSTATUS_DESELECTED),
      STATUS_ENTRY(NFCSTATUS_INVALID_DEVICE),
      STATUS_ENTRY(NFCSTATUS_MORE_INFORMATION),
      STATUS_ENTRY(NFCSTATUS_RF_TIMEOUT),
      STATUS_ENTRY(NFCSTATUS_RF_ERROR),
      STATUS_ENTRY(NFCSTATUS_BOARD_COMMUNICATION_ERROR),
      STATUS_ENTRY(NFCSTATUS_INVALID_STATE),
      STATUS_ENTRY(NFCSTATUS_NOT_REGISTERED),
      STATUS_ENTRY(NFCSTATUS_RELEASED),
      STATUS_ENTRY(NFCSTATUS_NOT_ALLOWED),
      STATUS_ENTRY(NFCSTATUS_INVALID_REMOTE_DEVICE),
      STATUS_ENTRY(NFCSTATUS_SMART_TAG_FUNC_NOT_SUPPORTED),
      STATUS_ENTRY(NFCSTATUS_READ_FAILED),
      STATUS_ENTRY(NFCSTATUS_WRITE_FAILED),
      STATUS_ENTRY(NFCSTATUS_NO_NDEF_SUPPORT),
      STATUS_ENTRY(NFCSTATUS_EOF_NDEF_CONTAINER_REACHED),
      STATUS_ENTRY(NFCSTATUS_INVALID_RECEIVE_LENGTH),
      STATUS_ENTRY(NFCSTATUS_INVALID_FORMAT),
      STATUS_ENTRY(NFCSTATUS_INSUFFICIENT_STORAGE),
      STATUS_ENTRY(NFCSTATUS_FORMAT_ERROR),
   };

   int i = sizeof(sNameTable)/sizeof(status_entry);
 
   while(i>0)
   {
      i--;
      if (sNameTable[i].code == PHNFCSTATUS(status))
      {
         return sNameTable[i].name;
      }
   }

   return "UNKNOWN";
}

/*
 *  Utility to get target type name from its specs
 */
int get_technology_type(phNfc_eRemDevType_t type, uint8_t sak)
{
   switch (type)
   {
      case phNfc_eISO14443_A_PICC:
      case phNfc_eISO14443_4A_PICC:
      case phNfc_eISO14443_4B_PICC:
        {
          return TARGET_TYPE_ISO14443_4;
        }break;
        
      case phNfc_eISO14443_3A_PICC:
        {
          return TARGET_TYPE_ISO14443_3A;
        }break;

      case phNfc_eISO14443_B_PICC:
        {
          /* Actually this can be -3B or -4B
           * FRI doesn't allow us to tell the diff yet
           * and the API doesn't know type 4B
           * so return 3B for now.
           */
          return TARGET_TYPE_ISO14443_3B;
        }break;
        
      case phNfc_eISO15693_PICC:
        {
          return TARGET_TYPE_ISO15693;
        }break;

      case phNfc_eMifare_PICC:
        {
          switch(sak)
          {
            case 0x00:
              // could be UL or UL-C
              return TARGET_TYPE_MIFARE_UL;
            case 0x08:
            case 0x09:
            case 0x10:
            case 0x11:
            case 0x18:
            case 0x28:
            case 0x38:
            case 0x88:
            case 0x98:
            case 0xB8:
              return TARGET_TYPE_MIFARE_CLASSIC;
            case 0x20:
              return TARGET_TYPE_MIFARE_DESFIRE;
            default:
              {
                return TARGET_TYPE_UNKNOWN;
              }break;
          }
        }break;
      case phNfc_eFelica_PICC:
        {
          return TARGET_TYPE_FELICA;
        }break; 
      case phNfc_eJewel_PICC:
        {
          return TARGET_TYPE_JEWEL;
        }break; 
      default:
        {
          return TARGET_TYPE_UNKNOWN;
        }
   }

   return TARGET_TYPE_UNKNOWN;
}

} // namespace android
