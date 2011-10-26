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

#include <errno.h>
#include <pthread.h>
#include <semaphore.h>
#include <stdlib.h>
#include <stdio.h>
#include <math.h>
#include <sys/queue.h>

#include "com_android_nfc.h"

#define ERROR_BUFFER_TOO_SMALL       -12
#define ERROR_INSUFFICIENT_RESOURCES -9
#define EEDATA_SETTINGS_NUMBER       33

extern uint32_t libnfc_llc_error_count;

static phLibNfc_sConfig_t   gDrvCfg;
void   *gHWRef;
static phNfc_sData_t gInputParam;
static phNfc_sData_t gOutputParam;

uint8_t device_connected_flag;
static bool driverConfigured = FALSE;

static phLibNfc_Handle              hLlcpHandle;
static NFCSTATUS                    lastErrorStatus = NFCSTATUS_FAILED;
static phLibNfc_Llcp_eLinkStatus_t  g_eLinkStatus = phFriNfc_LlcpMac_eLinkDefault;

static jmethodID cached_NfcManager_notifyNdefMessageListeners;
static jmethodID cached_NfcManager_notifyTransactionListeners;
static jmethodID cached_NfcManager_notifyLlcpLinkActivation;
static jmethodID cached_NfcManager_notifyLlcpLinkDeactivated;
static jmethodID cached_NfcManager_notifyTargetDeselected;

static jmethodID cached_NfcManager_notifySeFieldActivated;
static jmethodID cached_NfcManager_notifySeFieldDeactivated;

static jmethodID cached_NfcManager_notifySeApduReceived;
static jmethodID cached_NfcManager_notifySeMifareAccess;
static jmethodID cached_NfcManager_notifySeEmvCardRemoval;

namespace android {

phLibNfc_Handle     storedHandle = 0;

struct nfc_jni_native_data *exported_nat = NULL;

/* TODO: move product specific configuration such as this
 * antenna tuning into product specific configuration */
uint8_t EEDATA_Settings[EEDATA_SETTINGS_NUMBER][4] = {
	// DIFFERENTIAL_ANTENNA

	// RF Settings
	{0x00,0x9B,0xD1,0x0D} // Tx consumption higher than 0x0D (average 50mA)
	,{0x00,0x9B,0xD2,0x24} // GSP setting for this threshold
	,{0x00,0x9B,0xD3,0x0A} // Tx consumption higher than 0x0A (average 40mA)
	,{0x00,0x9B,0xD4,0x22} // GSP setting for this threshold
	,{0x00,0x9B,0xD5,0x08} // Tx consumption higher than 0x08 (average 30mA)
	,{0x00,0x9B,0xD6,0x1E} // GSP setting for this threshold
	,{0x00,0x9B,0xDD,0x1C} // GSP setting for this threshold
	,{0x00,0x9B,0x84,0x13} // ANACM2 setting
	,{0x00,0x99,0x81,0x7F} // ANAVMID setting PCD
	,{0x00,0x99,0x31,0x70} // ANAVMID setting PICC

	// Enable PBTF
	,{0x00,0x98,0x00,0x3F} // SECURE_ELEMENT_CONFIGURATION - No Secure Element
	,{0x00,0x9F,0x09,0x00} // SWP_PBTF_RFU
	,{0x00,0x9F,0x0A,0x05} // SWP_PBTF_RFLD  --> RFLEVEL Detector for PBTF
	,{0x00,0x9E,0xD1,0xA1} //

	// Change RF Level Detector ANARFLDWU
	,{0x00,0x99,0x23,0x00} // Default Value is 0x01

	// Low-power polling
	,{0x00,0x9E,0x74,0x80} // Default Value is 0x00, bits 0->2: sensitivity (0==maximal, 6==minimal), bits 3->6: RFU, bit 7: (0 -> disabled, 1 -> enabled)
	,{0x00,0x9F,0x28,0x10} // Default value for register 0x28 in FW 109.7

	// Polling Loop - Card Emulation Timeout
	,{0x00,0x9F,0x35,0x14} // Time for which PN544 stays in Card Emulation mode after leaving RF field
	,{0x00,0x9F,0x36,0x60} // Default value 0x0411 = 50 ms ---> New Value : 0x1460 = 250 ms

	//LLC Timer
	,{0x00,0x9C,0x31,0x00} // Guard host time-out in ms (MSB)
	,{0x00,0x9C,0x32,0xC8} // Guard host time-out in ms (LSB)
	,{0x00,0x9C,0x19,0x40} // Max RX retry (PN544=>host?)
	,{0x00,0x9C,0x1A,0x40} // Max TX retry (PN544=>host?)

	,{0x00,0x9C,0x0C,0x00} //
	,{0x00,0x9C,0x0D,0x00} //
	,{0x00,0x9C,0x12,0x00} //
	,{0x00,0x9C,0x13,0x00} //

    //WTX for LLCP communication
    ,{0x00,0x98,0xA2,0x0E} // Max value: 14 (default value: 09)

	//SE GPIO
	,{0x00, 0x98, 0x93, 0x40}

	// Set NFCT ATQA
	,{0x00, 0x98, 0x7D, 0x02}
	,{0x00, 0x98, 0x7E, 0x00}

    // Enable CEA detection mechanism
    ,{0x00, 0x9F, 0xC8, 0x01}
    // Set NFC-F poll RC=0x00
    ,{0x00, 0x9F, 0x9A, 0x00}
};

/* Internal functions declaration */
static void *nfc_jni_client_thread(void *arg);
static void nfc_jni_init_callback(void *pContext, NFCSTATUS status);
static void nfc_jni_deinit_callback(void *pContext, NFCSTATUS status);
static void nfc_jni_discover_callback(void *pContext, NFCSTATUS status);
static void nfc_jni_se_set_mode_callback(void *context,
   phLibNfc_Handle handle, NFCSTATUS status);
static void nfc_jni_llcpcfg_callback(void *pContext, NFCSTATUS status);
static void nfc_jni_start_discovery_locked(struct nfc_jni_native_data *nat);

static phLibNfc_eConfigLinkType parseLinkType(const char* link_name)
{
   struct link_name_entry {
      phLibNfc_eConfigLinkType   value;
      const char *               name;
   };
   const struct link_name_entry sLinkNameTable[] = {
      {ENUM_LINK_TYPE_COM1, "COM1"},
      {ENUM_LINK_TYPE_COM2, "COM2"},
      {ENUM_LINK_TYPE_COM3, "COM3"},
      {ENUM_LINK_TYPE_COM4, "COM4"},
      {ENUM_LINK_TYPE_COM5, "COM5"},
      {ENUM_LINK_TYPE_COM6, "COM6"},
      {ENUM_LINK_TYPE_COM7, "COM7"},
      {ENUM_LINK_TYPE_COM8, "COM8"},
      {ENUM_LINK_TYPE_I2C,  "I2C"},
      {ENUM_LINK_TYPE_USB,  "USB"},
   };
   phLibNfc_eConfigLinkType ret;
   unsigned int i;

   /* NOTE: ENUM_LINK_TYPE_NB corresponds to undefined link name  */

   if (link_name == NULL)
   {
      return ENUM_LINK_TYPE_NB;
   }

   ret = ENUM_LINK_TYPE_NB;
   for (i=0 ; i<sizeof(sLinkNameTable)/sizeof(link_name_entry) ; i++)
   {
      if (strcmp(sLinkNameTable[i].name, link_name) == 0)
      {
         ret = sLinkNameTable[i].value;
         break;
      }
   }

   return ret;
}


/*
 * Deferred callback called when client thread must be exited
 */
static void client_kill_deferred_call(void* arg)
{
   struct nfc_jni_native_data *nat = (struct nfc_jni_native_data *)arg;
   
   nat->running = FALSE;
}

static void kill_client(nfc_jni_native_data *nat)
{
   phDal4Nfc_Message_Wrapper_t  wrapper;
   phLibNfc_DeferredCall_t     *pMsg;
   
   usleep(50000);

   LOGD("Terminating client thread...");
    
   pMsg = (phLibNfc_DeferredCall_t*)malloc(sizeof(phLibNfc_DeferredCall_t));
   pMsg->pCallback = client_kill_deferred_call;
   pMsg->pParameter = (void*)nat;
   
   wrapper.msg.eMsgType = PH_LIBNFC_DEFERREDCALL_MSG;
   wrapper.msg.pMsgData = pMsg;
   wrapper.msg.Size     = sizeof(phLibNfc_DeferredCall_t);

   phDal4Nfc_msgsnd(gDrvCfg.nClientId, (struct msgbuf *)&wrapper, sizeof(phLibNfc_Message_t), 0);
}

static void nfc_jni_ioctl_callback(void *pContext, phNfc_sData_t *pOutput, NFCSTATUS status) {
   struct nfc_jni_callback_data * pCallbackData = (struct nfc_jni_callback_data *) pContext;
   LOG_CALLBACK("nfc_jni_ioctl_callback", status);

   /* Report the callback status and wake up the caller */
   pCallbackData->status = status;
   sem_post(&pCallbackData->sem);
}

static void nfc_jni_deinit_download_callback(void *pContext, NFCSTATUS status)
{
   struct nfc_jni_callback_data * pCallbackData = (struct nfc_jni_callback_data *) pContext;
   LOG_CALLBACK("nfc_jni_deinit_download_callback", status);

   /* Report the callback status and wake up the caller */
   pCallbackData->status = status;
   sem_post(&pCallbackData->sem);
}

static int nfc_jni_download(struct nfc_jni_native_data *nat, uint8_t update)
{
    uint8_t OutputBuffer[1];
    uint8_t InputBuffer[1];
    struct timespec ts;
    NFCSTATUS status = NFCSTATUS_FAILED;
    phLibNfc_StackCapabilities_t caps;
    struct nfc_jni_callback_data cb_data;

    /* Create the local semaphore */
    if (!nfc_cb_data_init(&cb_data, NULL))
    {
       goto clean_and_return;
    }

    if(update)
    {
        //deinit
        TRACE("phLibNfc_Mgt_DeInitialize() (download)");
        REENTRANCE_LOCK();
        status = phLibNfc_Mgt_DeInitialize(gHWRef, nfc_jni_deinit_download_callback, (void *)&cb_data);
        REENTRANCE_UNLOCK();
        if (status != NFCSTATUS_PENDING)
        {
            LOGE("phLibNfc_Mgt_DeInitialize() (download) returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));
        }

        clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_sec += 5;

        /* Wait for callback response */
        if(sem_timedwait(&cb_data.sem, &ts))
        {
            LOGW("Deinitialization timed out (download)");
        }

        if(cb_data.status != NFCSTATUS_SUCCESS)
        {
            LOGW("Deinitialization FAILED (download)");
        }
        TRACE("Deinitialization SUCCESS (download)");
    }

    TRACE("Go in Download Mode");
    phLibNfc_Download_Mode();

    // Download
    gInputParam.buffer  = InputBuffer;
    gInputParam.length  = 0x01;
    gOutputParam.buffer = OutputBuffer;
    gOutputParam.length = 0x01;

    LOGD("Download new Firmware");
    REENTRANCE_LOCK();
    status = phLibNfc_Mgt_IoCtl(gHWRef,NFC_FW_DOWNLOAD, &gInputParam, &gOutputParam, nfc_jni_ioctl_callback, (void *)&cb_data);
    REENTRANCE_UNLOCK();
    if(status != NFCSTATUS_PENDING)
    {
        LOGE("phLibNfc_Mgt_IoCtl() (download) returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));
        status = NFCSTATUS_FAILED;
        goto clean_and_return;
    }
    TRACE("phLibNfc_Mgt_IoCtl() (download) returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));

    /* Wait for callback response */
    if(sem_wait(&cb_data.sem))
    {
       LOGE("Failed to wait for semaphore (errno=0x%08x)", errno);
       status = NFCSTATUS_FAILED;
       goto clean_and_return;
    }

    /* NOTE: we will get NFCSTATUS_FEATURE_NOT_SUPPORTED when we
       try to download an old-style firmware on top of a new-style
       firmware.  Hence, this is expected behavior, and not an
       error condition. */
    if(cb_data.status != NFCSTATUS_SUCCESS && cb_data.status != NFCSTATUS_FEATURE_NOT_SUPPORTED)
    {
        status = cb_data.status;
        goto clean_and_return;
    }

    if(cb_data.status == NFCSTATUS_FEATURE_NOT_SUPPORTED)
    {
        LOGW("Old-style firmware not installed on top of new-style firmware. Using existing firmware in the chip.");
    }

reinit:
    TRACE("phLibNfc_HW_Reset()");
    phLibNfc_HW_Reset();

    TRACE("phLibNfc_Mgt_Initialize()");
    REENTRANCE_LOCK();
    status = phLibNfc_Mgt_Initialize(gHWRef, nfc_jni_init_callback, (void *)&cb_data);
    REENTRANCE_UNLOCK();
    if(status != NFCSTATUS_PENDING)
    {
        LOGE("phLibNfc_Mgt_Initialize() (download) returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));
        goto clean_and_return;
    }
    TRACE("phLibNfc_Mgt_Initialize() returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));

    if(sem_wait(&cb_data.sem))
    {
       LOGE("Failed to wait for semaphore (errno=0x%08x)", errno);
       status = NFCSTATUS_FAILED;
       goto clean_and_return;
    }

    /* Initialization Status */
    if(cb_data.status != NFCSTATUS_SUCCESS)
    {
        status = cb_data.status;
        goto clean_and_return;
    }

    /* ====== CAPABILITIES ======= */
    REENTRANCE_LOCK();
    status = phLibNfc_Mgt_GetstackCapabilities(&caps, (void*)nat);
    REENTRANCE_UNLOCK();
    if (status != NFCSTATUS_SUCCESS)
    {
       LOGW("phLibNfc_Mgt_GetstackCapabilities returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));
    }
    else
    {
        LOGD("NFC capabilities: HAL = %x, FW = %x, HW = %x, Model = %x, HCI = %x, Full_FW = %d, Rev = %d, FW Update Info = %d",
              caps.psDevCapabilities.hal_version,
              caps.psDevCapabilities.fw_version,
              caps.psDevCapabilities.hw_version,
              caps.psDevCapabilities.model_id,
              caps.psDevCapabilities.hci_version,
              caps.psDevCapabilities.full_version[NXP_FULL_VERSION_LEN-1],
              caps.psDevCapabilities.full_version[NXP_FULL_VERSION_LEN-2],
              caps.psDevCapabilities.firmware_update_info);
    }

    /*Download is successful*/
    status = NFCSTATUS_SUCCESS;

clean_and_return:
   nfc_cb_data_deinit(&cb_data);
   return status;
}

static int nfc_jni_configure_driver(struct nfc_jni_native_data *nat)
{
    char value[PROPERTY_VALUE_MAX];
    int result = FALSE;
    NFCSTATUS status;

    /* ====== CONFIGURE DRIVER ======= */
    /* Configure hardware link */
    gDrvCfg.nClientId = phDal4Nfc_msgget(0, 0600);

    property_get("ro.nfc.port", value, "unknown");
    gDrvCfg.nLinkType = parseLinkType(value);

    TRACE("phLibNfc_Mgt_ConfigureDriver(0x%08x, 0x%08x)", gDrvCfg.nClientId, gDrvCfg.nLinkType);
    REENTRANCE_LOCK();
    status = phLibNfc_Mgt_ConfigureDriver(&gDrvCfg, &gHWRef);
    REENTRANCE_UNLOCK();
    if(status == NFCSTATUS_ALREADY_INITIALISED) {
           LOGW("phLibNfc_Mgt_ConfigureDriver() returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));
    }
    else if(status != NFCSTATUS_SUCCESS)
    {
        LOGE("phLibNfc_Mgt_ConfigureDriver() returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));
        goto clean_and_return;
    }
    TRACE("phLibNfc_Mgt_ConfigureDriver() returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));

    if(pthread_create(&(nat->thread), NULL, nfc_jni_client_thread, nat) != 0)
    {
        LOGE("pthread_create failed");
        goto clean_and_return;
    }

    driverConfigured = TRUE;

clean_and_return:
    return result;
}

static int nfc_jni_unconfigure_driver(struct nfc_jni_native_data *nat)
{
    int result = FALSE;
    NFCSTATUS status;

    /* Unconfigure driver */
    TRACE("phLibNfc_Mgt_UnConfigureDriver()");
    REENTRANCE_LOCK();
    status = phLibNfc_Mgt_UnConfigureDriver(gHWRef);
    REENTRANCE_UNLOCK();
    if(status != NFCSTATUS_SUCCESS)
    {
        LOGE("phLibNfc_Mgt_UnConfigureDriver() returned error 0x%04x[%s] -- this should never happen", status, nfc_jni_get_status_name( status));
    }
    else
    {
       LOGD("phLibNfc_Mgt_UnConfigureDriver() returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));
       result = TRUE;
    }

    driverConfigured = FALSE;

    return result;
}

/* Initialization function */
static int nfc_jni_initialize(struct nfc_jni_native_data *nat) {
   struct timespec ts;
   uint8_t resp[16];
   NFCSTATUS status;
   phLibNfc_StackCapabilities_t caps;
   phLibNfc_SE_List_t SE_List[PHLIBNFC_MAXNO_OF_SE];
   uint8_t i, No_SE = PHLIBNFC_MAXNO_OF_SE, SmartMX_index = 0, SmartMX_detected = 0;
   phLibNfc_Llcp_sLinkParameters_t LlcpConfigInfo;
   struct nfc_jni_callback_data cb_data;
   uint8_t firmware_status;
   uint8_t update = TRUE;
   int result = JNI_FALSE;

   LOGD("Start Initialization\n");

   /* Create the local semaphore */
   if (!nfc_cb_data_init(&cb_data, NULL))
   {
      goto clean_and_return;
   }

   /* Reset device connected handle */
   device_connected_flag = 0;

   /* Reset stored handle */
   storedHandle = 0;

   /* Initialize Driver */
   if(!driverConfigured)
   {
       nfc_jni_configure_driver(nat);
   }

   /* ====== INITIALIZE ======= */

   TRACE("phLibNfc_Mgt_Initialize()");
   REENTRANCE_LOCK();
   status = phLibNfc_Mgt_Initialize(gHWRef, nfc_jni_init_callback, (void *)&cb_data);
   REENTRANCE_UNLOCK();
   if(status != NFCSTATUS_PENDING)
   {
      LOGE("phLibNfc_Mgt_Initialize() returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));
      update = FALSE;
      goto force_download;
   }
   TRACE("phLibNfc_Mgt_Initialize returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));
  
   /* Wait for callback response */
   if(sem_wait(&cb_data.sem))
   {
      LOGE("Failed to wait for semaphore (errno=0x%08x)", errno);
      goto clean_and_return;
   }

   /* Initialization Status */
   if(cb_data.status != NFCSTATUS_SUCCESS)
   {
      update = FALSE;
      goto force_download;
   }

   /* ====== CAPABILITIES ======= */

   REENTRANCE_LOCK();
   status = phLibNfc_Mgt_GetstackCapabilities(&caps, (void*)nat);
   REENTRANCE_UNLOCK();
   if (status != NFCSTATUS_SUCCESS)
   {
      LOGW("phLibNfc_Mgt_GetstackCapabilities returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));
   }
   else
   {
       LOGD("NFC capabilities: HAL = %x, FW = %x, HW = %x, Model = %x, HCI = %x, Full_FW = %d, Rev = %d, FW Update Info = %d",
             caps.psDevCapabilities.hal_version,
             caps.psDevCapabilities.fw_version,
             caps.psDevCapabilities.hw_version,
             caps.psDevCapabilities.model_id,
             caps.psDevCapabilities.hci_version,
             caps.psDevCapabilities.full_version[NXP_FULL_VERSION_LEN-1],
             caps.psDevCapabilities.full_version[NXP_FULL_VERSION_LEN-2],
             caps.psDevCapabilities.firmware_update_info);
   }

   /* ====== FIRMWARE VERSION ======= */
   if(caps.psDevCapabilities.firmware_update_info)
   {
force_download:
       for (i=0; i<3; i++)
       {
           TRACE("Firmware version not UpToDate");
           status = nfc_jni_download(nat, update);
           if(status == NFCSTATUS_SUCCESS)
           {
               LOGI("Firmware update SUCCESS");
               break;
           }
           LOGW("Firmware update FAILED");
           update = FALSE;
       }
       if(i>=3)
       {
           LOGE("Unable to update firmware, giving up");
           goto clean_and_return;
       }
   }
   else
   {
       TRACE("Firmware version UpToDate");
   }

   /* ====== EEPROM SETTINGS ======= */

   // Update EEPROM settings
   TRACE("******  START EEPROM SETTINGS UPDATE ******");
   for (i = 0; i < EEDATA_SETTINGS_NUMBER; i++)
   {
      gInputParam.buffer = EEDATA_Settings[i];
      gInputParam.length = 0x04;
      gOutputParam.buffer = resp;

      TRACE("> EEPROM SETTING: %d", i);
      REENTRANCE_LOCK();
      status = phLibNfc_Mgt_IoCtl(gHWRef, NFC_MEM_WRITE, &gInputParam, &gOutputParam, nfc_jni_ioctl_callback, (void *)&cb_data);
      REENTRANCE_UNLOCK();
      if (status != NFCSTATUS_PENDING) {
         LOGE("phLibNfc_Mgt_IoCtl() returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));
         goto clean_and_return;
      }
      /* Wait for callback response */
      if(sem_wait(&cb_data.sem))
      {
         LOGE("Failed to wait for semaphore (errno=0x%08x)", errno);
         goto clean_and_return;
      }

      /* Initialization Status */
      if (cb_data.status != NFCSTATUS_SUCCESS)
      {
         goto clean_and_return;
      }
   }
   TRACE("******  ALL EEPROM SETTINGS UPDATED  ******");

   /* ====== SECURE ELEMENTS ======= */

   REENTRANCE_LOCK();
   LOGD("phLibNfc_SE_GetSecureElementList()");
   status = phLibNfc_SE_GetSecureElementList(SE_List, &No_SE);
   REENTRANCE_UNLOCK();
   if (status != NFCSTATUS_SUCCESS)
   {
      LOGD("phLibNfc_SE_GetSecureElementList(): Error");
      goto clean_and_return;
   }

   LOGD("\n> Number of Secure Element(s) : %d\n", No_SE);
   /* Display Secure Element information */
   for (i = 0; i < No_SE; i++)
   {
      if (SE_List[i].eSE_Type == phLibNfc_SE_Type_SmartMX) {
         LOGD("phLibNfc_SE_GetSecureElementList(): SMX detected, handle=%p", (void*)SE_List[i].hSecureElement);
      } else if (SE_List[i].eSE_Type == phLibNfc_SE_Type_UICC) {
         LOGD("phLibNfc_SE_GetSecureElementList(): UICC detected, handle=%p", (void*)SE_List[i].hSecureElement);
      }

      /* Set SE mode - Off */
      REENTRANCE_LOCK();
      status = phLibNfc_SE_SetMode(SE_List[i].hSecureElement,
            phLibNfc_SE_ActModeOff, nfc_jni_se_set_mode_callback,
            (void *)&cb_data);
      REENTRANCE_UNLOCK();
      if (status != NFCSTATUS_PENDING)
      {
         LOGE("phLibNfc_SE_SetMode() returned 0x%04x[%s]", status,
               nfc_jni_get_status_name(status));
         goto clean_and_return;
      }
      LOGD("phLibNfc_SE_SetMode() returned 0x%04x[%s]", status,
            nfc_jni_get_status_name(status));

      /* Wait for callback response */
      if(sem_wait(&cb_data.sem))
      {
         LOGE("Failed to wait for semaphore (errno=0x%08x)", errno);
         goto clean_and_return;
      }
   }

   /* ====== LLCP ======= */

   /* LLCP Params */
   TRACE("******  NFC Config Mode NFCIP1 - LLCP ******");
   LlcpConfigInfo.miu    = nat->miu;
   LlcpConfigInfo.lto    = nat->lto;
   LlcpConfigInfo.wks    = nat->wks;
   LlcpConfigInfo.option = nat->opt;

   REENTRANCE_LOCK();
   status = phLibNfc_Mgt_SetLlcp_ConfigParams(&LlcpConfigInfo,
                                              nfc_jni_llcpcfg_callback,
                                              (void *)&cb_data);
   REENTRANCE_UNLOCK();
   if(status != NFCSTATUS_PENDING)
   {
      LOGE("phLibNfc_Mgt_SetLlcp_ConfigParams returned 0x%04x[%s]", status,
           nfc_jni_get_status_name(status));
      goto clean_and_return;
   }
   TRACE("phLibNfc_Mgt_SetLlcp_ConfigParams returned 0x%04x[%s]", status,
         nfc_jni_get_status_name(status));

   /* Wait for callback response */
   if(sem_wait(&cb_data.sem))
   {
      LOGE("Failed to wait for semaphore (errno=0x%08x)", errno);
      goto clean_and_return;
   }

   /* ====== END ======= */

   LOGI("NFC Initialized");

   result = TRUE;

clean_and_return:
   if (result != TRUE)
   {
      if(nat)
      {
         kill_client(nat);
      }
   }

   nfc_cb_data_deinit(&cb_data);

   return result;
}

static int is_user_build() {
    char value[PROPERTY_VALUE_MAX];
    property_get("ro.build.type", value, "");
    return !strncmp("user", value, PROPERTY_VALUE_MAX);
}

/*
 * Last-chance fallback when there is no clean way to recover
 * Performs a software reset
  */
void emergency_recovery(struct nfc_jni_native_data *nat) {
   if (!is_user_build()) {
       LOGE("emergency_recovery: force restart of NFC service");
   } else {
       // dont recover immediately, so we can debug
       unsigned int t;
       for (t=1; t < 1000000; t <<= 1) {
           LOGE("emergency_recovery: NFC stack dead-locked, please show to npelly");
           sleep(t);
       }
   }
   abort();  // force a noisy crash
}

void nfc_jni_reset_timeout_values()
{
    REENTRANCE_LOCK();
    phLibNfc_SetIsoXchgTimeout(NXP_ISO_XCHG_TIMEOUT);
    phLibNfc_SetHciTimeout(NXP_NFC_HCI_TIMEOUT);
    phLibNfc_SetFelicaTimeout(NXP_FELICA_XCHG_TIMEOUT);
    phLibNfc_SetMifareRawTimeout(NXP_MIFARE_XCHG_TIMEOUT);
    REENTRANCE_UNLOCK();
}

/*
 * Restart the polling loop when unable to perform disconnect
  */
void nfc_jni_restart_discovery_locked(struct nfc_jni_native_data *nat)
{
   int ret;
   struct nfc_jni_callback_data cb_data;

   TRACE("Restarting polling loop");
   
   /* Create the local semaphore */
   if (!nfc_cb_data_init(&cb_data, NULL))
   {
      goto clean_and_return;
   }

   /* Reset the PN544 ISO XCHG / sw watchdog timeouts */
   nfc_jni_reset_timeout_values();

   /* Reset device connected flag */
   device_connected_flag = 0;

   /* Restart Polling loop */
   TRACE("******  Start NFC Discovery ******");
   REENTRANCE_LOCK();
   ret = phLibNfc_Mgt_ConfigureDiscovery(NFC_DISCOVERY_RESUME,nat->discovery_cfg, nfc_jni_discover_callback, (void *)&cb_data);
   REENTRANCE_UNLOCK();
   TRACE("phLibNfc_Mgt_ConfigureDiscovery(%s-%s-%s-%s-%s-%s, %s-%x-%x) returned 0x%08x\n",
      nat->discovery_cfg.PollDevInfo.PollCfgInfo.EnableIso14443A==TRUE?"3A":"",
      nat->discovery_cfg.PollDevInfo.PollCfgInfo.EnableIso14443B==TRUE?"3B":"",
      nat->discovery_cfg.PollDevInfo.PollCfgInfo.EnableFelica212==TRUE?"F2":"",
      nat->discovery_cfg.PollDevInfo.PollCfgInfo.EnableFelica424==TRUE?"F4":"",
      nat->discovery_cfg.PollDevInfo.PollCfgInfo.EnableNfcActive==TRUE?"NFC":"",
      nat->discovery_cfg.PollDevInfo.PollCfgInfo.EnableIso15693==TRUE?"RFID":"",
      nat->discovery_cfg.PollDevInfo.PollCfgInfo.DisableCardEmulation==FALSE?"CE":"",
      nat->discovery_cfg.NfcIP_Mode, nat->discovery_cfg.Duration, ret);
   
   if (ret != NFCSTATUS_PENDING)
   {
      emergency_recovery(nat);
      goto clean_and_return;
   }

   /* Wait for callback response */
   if(sem_wait(&cb_data.sem))
   {
      LOGE("Failed to wait for semaphore (errno=0x%08x)", errno);
      goto clean_and_return;
   }

clean_and_return:
   nfc_cb_data_deinit(&cb_data);

}

 /*
  *  Utility to recover UID from target infos
  */
static phNfc_sData_t get_target_uid(phLibNfc_sRemoteDevInformation_t *psRemoteDevInfo)
{
    phNfc_sData_t uid;

    switch(psRemoteDevInfo->RemDevType)
    {
    case phNfc_eISO14443_A_PICC:
    case phNfc_eISO14443_4A_PICC:
    case phNfc_eISO14443_3A_PICC:
    case phNfc_eMifare_PICC:
        uid.buffer = psRemoteDevInfo->RemoteDevInfo.Iso14443A_Info.Uid;
        uid.length = psRemoteDevInfo->RemoteDevInfo.Iso14443A_Info.UidLength;
        break;
    case phNfc_eISO14443_B_PICC:
    case phNfc_eISO14443_4B_PICC:
        uid.buffer = psRemoteDevInfo->RemoteDevInfo.Iso14443B_Info.AtqB.AtqResInfo.Pupi;
        uid.length = sizeof(psRemoteDevInfo->RemoteDevInfo.Iso14443B_Info.AtqB.AtqResInfo.Pupi);
        break;
    case phNfc_eFelica_PICC:
        uid.buffer = psRemoteDevInfo->RemoteDevInfo.Felica_Info.IDm;
        uid.length = psRemoteDevInfo->RemoteDevInfo.Felica_Info.IDmLength;
        break;
    case phNfc_eJewel_PICC:
        uid.buffer = psRemoteDevInfo->RemoteDevInfo.Jewel_Info.Uid;
        uid.length = psRemoteDevInfo->RemoteDevInfo.Jewel_Info.UidLength;
        break;
    case phNfc_eISO15693_PICC:
        uid.buffer = psRemoteDevInfo->RemoteDevInfo.Iso15693_Info.Uid;
        uid.length = psRemoteDevInfo->RemoteDevInfo.Iso15693_Info.UidLength;
        break;
    case phNfc_eNfcIP1_Target:
    case phNfc_eNfcIP1_Initiator:
        uid.buffer = psRemoteDevInfo->RemoteDevInfo.NfcIP_Info.NFCID;
        uid.length = psRemoteDevInfo->RemoteDevInfo.NfcIP_Info.NFCID_Length;
        break;
    default:
        uid.buffer = NULL;
        uid.length = 0;
        break;
    }

    return uid;
}

/*
 * NFC stack message processing
 */
static void *nfc_jni_client_thread(void *arg)
{
   struct nfc_jni_native_data *nat;
   JNIEnv *e;
   JavaVMAttachArgs thread_args;
   phDal4Nfc_Message_Wrapper_t wrapper;

   nat = (struct nfc_jni_native_data *)arg;

   thread_args.name = "NFC Message Loop";
   thread_args.version = nat->env_version;
   thread_args.group = NULL;

   nat->vm->AttachCurrentThread(&e, &thread_args);
   pthread_setname_np(pthread_self(), "message");

   TRACE("NFC client started");
   nat->running = TRUE;
   while(nat->running == TRUE)
   {
      /* Fetch next message from the NFC stack message queue */
      if(phDal4Nfc_msgrcv(gDrvCfg.nClientId, (void *)&wrapper,
         sizeof(phLibNfc_Message_t), 0, 0) == -1)
      {
         LOGE("NFC client received bad message");
         continue;
      }

      switch(wrapper.msg.eMsgType)
      {
         case PH_LIBNFC_DEFERREDCALL_MSG:
         {
            phLibNfc_DeferredCall_t *msg =
               (phLibNfc_DeferredCall_t *)(wrapper.msg.pMsgData);

            REENTRANCE_LOCK();
            msg->pCallback(msg->pParameter);
            REENTRANCE_UNLOCK();

            break;
         }
      }
   }
   TRACE("NFC client stopped");
   
   nat->vm->DetachCurrentThread();

   return NULL;
}

extern uint8_t nfc_jni_is_ndef;
extern uint8_t *nfc_jni_ndef_buf;
extern uint32_t nfc_jni_ndef_buf_len;

static phLibNfc_sNfcIPCfg_t nfc_jni_nfcip1_cfg =
{
   3,
   { 0x46, 0x66, 0x6D }
}; 

/*
 * Callbacks
 */

/* P2P - LLCP callbacks */
static void nfc_jni_llcp_linkStatus_callback(void *pContext,
                                                    phFriNfc_LlcpMac_eLinkStatus_t   eLinkStatus)
{
   phFriNfc_Llcp_sLinkParameters_t  sLinkParams;
   JNIEnv *e;
   NFCSTATUS status;

   struct nfc_jni_callback_data * pContextData =  (struct nfc_jni_callback_data*)pContext;

   struct nfc_jni_native_data *nat = (nfc_jni_native_data *)pContextData->pContext;

   nfc_jni_listen_data_t * pListenData = NULL;
   nfc_jni_native_monitor * pMonitor = nfc_jni_get_monitor();

   TRACE("Callback: nfc_jni_llcp_linkStatus_callback()");

   nat->vm->GetEnv( (void **)&e, nat->env_version);
   
   /* Update link status */
   g_eLinkStatus = eLinkStatus;

   if(eLinkStatus == phFriNfc_LlcpMac_eLinkActivated)
   {
      REENTRANCE_LOCK();
      status = phLibNfc_Llcp_GetRemoteInfo(hLlcpHandle, &sLinkParams);
      REENTRANCE_UNLOCK();
      if(status != NFCSTATUS_SUCCESS)
      {
           LOGW("GetRemote Info failded - Status = %02x",status);
      }
      else
      {
           LOGI("LLCP Link activated (LTO=%d, MIU=%d, OPTION=0x%02x, WKS=0x%02x)",sLinkParams.lto,
                                                                                  sLinkParams.miu,
                                                                                  sLinkParams.option,
                                                                                  sLinkParams.wks);
           device_connected_flag = 1;
      }
   }
   else if(eLinkStatus == phFriNfc_LlcpMac_eLinkDeactivated)
   {
      LOGI("LLCP Link deactivated");
      free(pContextData);
      /* Reset device connected flag */
      device_connected_flag = 0;

      /* Reset incoming socket list */
      while (!LIST_EMPTY(&pMonitor->incoming_socket_head))
      {
         pListenData = LIST_FIRST(&pMonitor->incoming_socket_head);
         LIST_REMOVE(pListenData, entries);
         free(pListenData);
      }

      /* Notify manager that the LLCP is lost or deactivated */
      e->CallVoidMethod(nat->manager, cached_NfcManager_notifyLlcpLinkDeactivated, nat->tag);
      if(e->ExceptionCheck())
      {
         LOGE("Exception occured");
         kill_client(nat);
      } 
   }
}

static void nfc_jni_checkLlcp_callback(void *context,
                                              NFCSTATUS status)
{
   struct nfc_jni_callback_data * pContextData =  (struct nfc_jni_callback_data*)context;

   LOG_CALLBACK("nfc_jni_checkLlcp_callback", status);

   pContextData->status = status;
   sem_post(&pContextData->sem);
}

static void nfc_jni_llcpcfg_callback(void *pContext, NFCSTATUS status)
{
   struct nfc_jni_callback_data * pCallbackData = (struct nfc_jni_callback_data *) pContext;
   LOG_CALLBACK("nfc_jni_llcpcfg_callback", status);

   /* Report the callback status and wake up the caller */
   pCallbackData->status = status;
   sem_post(&pCallbackData->sem);
}

static void nfc_jni_llcp_transport_listen_socket_callback(void              *pContext,
                                                          phLibNfc_Handle   hIncomingSocket)
{
   phLibNfc_Handle hServiceSocket = (phLibNfc_Handle)pContext;
   nfc_jni_listen_data_t * pListenData = NULL;
   nfc_jni_native_monitor * pMonitor = nfc_jni_get_monitor();

   TRACE("nfc_jni_llcp_transport_listen_socket_callback socket handle = %p", (void*)hIncomingSocket);

   pthread_mutex_lock(&pMonitor->incoming_socket_mutex);

   /* Store the connection request */
   pListenData = (nfc_jni_listen_data_t*)malloc(sizeof(nfc_jni_listen_data_t));
   if (pListenData == NULL)
   {
      LOGE("Failed to create structure to handle incoming LLCP connection request");
      goto clean_and_return;
   }
   pListenData->pServerSocket = hServiceSocket;
   pListenData->pIncomingSocket = hIncomingSocket;
   LIST_INSERT_HEAD(&pMonitor->incoming_socket_head, pListenData, entries);

   /* Signal pending accept operations that the list is updated */
   pthread_cond_broadcast(&pMonitor->incoming_socket_cond);

clean_and_return:
   pthread_mutex_unlock(&pMonitor->incoming_socket_mutex);
}

void nfc_jni_llcp_transport_socket_err_callback(void*      pContext,
                                                       uint8_t    nErrCode)
{
   PHNFC_UNUSED_VARIABLE(pContext);

   TRACE("Callback: nfc_jni_llcp_transport_socket_err_callback()");

   if(nErrCode == PHFRINFC_LLCP_ERR_FRAME_REJECTED)
   {
      LOGW("Frame Rejected - Disconnected");
   }
   else if(nErrCode == PHFRINFC_LLCP_ERR_DISCONNECTED)
   {
      LOGD("Socket Disconnected");
   }
}


static void nfc_jni_discover_callback(void *pContext, NFCSTATUS status)
{
    struct nfc_jni_callback_data * pContextData =  (struct nfc_jni_callback_data*)pContext;

    LOG_CALLBACK("nfc_jni_discover_callback", status);

    pContextData->status = status;
    sem_post(&pContextData->sem);
}

static void nfc_jni_Discovery_notification_callback(void *pContext,
   phLibNfc_RemoteDevList_t *psRemoteDevList,
   uint8_t uNofRemoteDev, NFCSTATUS status)
{
   JNIEnv *e;
   NFCSTATUS ret;
   jclass tag_cls = NULL;
   jobject target_array;
   jobject tag;
   jmethodID ctor;
   jfieldID f;
   const char * typeName;
   jbyteArray tagUid;
   jbyteArray generalBytes = NULL;
   struct nfc_jni_native_data *nat;
   struct timespec ts;
   phNfc_sData_t data;
   int i;
   int target_index = 0; // Target that will be reported (if multiple can be >0)

   nat = (struct nfc_jni_native_data *)pContext;
   
   nat->vm->GetEnv( (void **)&e, nat->env_version);
   
   if(status == NFCSTATUS_DESELECTED)
   {
      LOG_CALLBACK("nfc_jni_Discovery_notification_callback: Target deselected", status); 
         
      /* Notify manager that a target was deselected */
      e->CallVoidMethod(nat->manager, cached_NfcManager_notifyTargetDeselected);
      if(e->ExceptionCheck())
      {
         LOGE("Exception occured");
         kill_client(nat);
      } 
   }
   else
   {
      LOG_CALLBACK("nfc_jni_Discovery_notification_callback", status);
      TRACE("Discovered %d tags", uNofRemoteDev);

      /* Reset device connected flag */
      device_connected_flag = 1;

      if((psRemoteDevList->psRemoteDevInfo->RemDevType == phNfc_eNfcIP1_Initiator)
          || (psRemoteDevList->psRemoteDevInfo->RemDevType == phNfc_eNfcIP1_Target))
      {
         tag_cls = e->GetObjectClass(nat->cached_P2pDevice);
         if(e->ExceptionCheck())
         {
            LOGE("Get Object Class Error"); 
            kill_client(nat);
            return;
         } 
         
         /* New target instance */
         ctor = e->GetMethodID(tag_cls, "<init>", "()V");
         tag = e->NewObject(tag_cls, ctor);
         
         /* Set P2P Target mode */
         f = e->GetFieldID(tag_cls, "mMode", "I"); 
         
         if(psRemoteDevList->psRemoteDevInfo->RemDevType == phNfc_eNfcIP1_Initiator)
         {
            LOGD("Discovered P2P Initiator");
            e->SetIntField(tag, f, (jint)MODE_P2P_INITIATOR);
         }
         else
         {    
            LOGD("Discovered P2P Target");
            e->SetIntField(tag, f, (jint)MODE_P2P_TARGET);
         }
          
         if(psRemoteDevList->psRemoteDevInfo->RemDevType == phNfc_eNfcIP1_Initiator)
         {
            /* Set General Bytes */
            f = e->GetFieldID(tag_cls, "mGeneralBytes", "[B");
   
           TRACE("General Bytes length =");
           for(i=0;i<psRemoteDevList->psRemoteDevInfo->RemoteDevInfo.NfcIP_Info.ATRInfo_Length;i++)
           {
               LOGD("%02x ", psRemoteDevList->psRemoteDevInfo->RemoteDevInfo.NfcIP_Info.ATRInfo[i]);          
           }
       
            generalBytes = e->NewByteArray(psRemoteDevList->psRemoteDevInfo->RemoteDevInfo.NfcIP_Info.ATRInfo_Length);   
             
            e->SetByteArrayRegion(generalBytes, 0, 
                                  psRemoteDevList->psRemoteDevInfo->RemoteDevInfo.NfcIP_Info.ATRInfo_Length, 
                                  (jbyte *)psRemoteDevList->psRemoteDevInfo->RemoteDevInfo.NfcIP_Info.ATRInfo);
             
            e->SetObjectField(tag, f, generalBytes);
         }

         /* Set tag handle */
         f = e->GetFieldID(tag_cls, "mHandle", "I");
         e->SetIntField(tag, f,(jint)psRemoteDevList[target_index].hTargetDev);
         TRACE("Target handle = 0x%08x",psRemoteDevList[target_index].hTargetDev);
      }
      else
      {
        tag_cls = e->GetObjectClass(nat->cached_NfcTag);
        if(e->ExceptionCheck())
        {
            kill_client(nat);
            return;
        }

        /* New tag instance */
        ctor = e->GetMethodID(tag_cls, "<init>", "()V");
        tag = e->NewObject(tag_cls, ctor);

        bool multi_protocol = false;

        if(status == NFCSTATUS_MULTIPLE_PROTOCOLS)
        {
            TRACE("Multiple Protocol TAG detected\n");
            multi_protocol = true;
        }
        else if (status == NFCSTATUS_MULTIPLE_TAGS) {
            // Only one tag will be used
            // TODO: suppose there's both a multi-proto and another
            // single-proto tag in the field: in that case, we'd want to make sure we
            // return a "complete" tag, and not just one "target", which
            // is then either half of the multi-proto tag or the complete
            // single-proto.
            target_index = 0;
        } else {
            target_index = 0;
        }

        /* Set tag UID */
        f = e->GetFieldID(tag_cls, "mUid", "[B");
        data = get_target_uid(psRemoteDevList[target_index].psRemoteDevInfo);
        tagUid = e->NewByteArray(data.length);
        if(data.length > 0)
        {
            e->SetByteArrayRegion(tagUid, 0, data.length, (jbyte *)data.buffer);
        }
        e->SetObjectField(tag, f, tagUid);

        /* Generate technology list */
        jintArray techList;
        jintArray handleList;
        jintArray typeList;
        nfc_jni_get_technology_tree(e, psRemoteDevList,
                multi_protocol ? uNofRemoteDev : 1,
                &techList, &handleList, &typeList);

        /* Push the technology list into the java object */
        f = e->GetFieldID(tag_cls, "mTechList", "[I");
        e->SetObjectField(tag, f, techList);

        f = e->GetFieldID(tag_cls, "mTechHandles", "[I");
        e->SetObjectField(tag, f, handleList);

        f = e->GetFieldID(tag_cls, "mTechLibNfcTypes", "[I");
        e->SetObjectField(tag, f, typeList);

        f = e->GetFieldID(tag_cls, "mConnectedTechIndex", "I");
        e->SetIntField(tag, f,(jint)-1);

        f = e->GetFieldID(tag_cls, "mConnectedHandle", "I");
        e->SetIntField(tag, f,(jint)-1);
      }

      storedHandle = psRemoteDevList[target_index].hTargetDev;
      if (nat->tag != NULL) {
          e->DeleteGlobalRef(nat->tag);
      }
      nat->tag = e->NewGlobalRef(tag);

      /* Notify the service */   
      TRACE("Notify Nfc Service");
      if((psRemoteDevList->psRemoteDevInfo->RemDevType == phNfc_eNfcIP1_Initiator)
          || (psRemoteDevList->psRemoteDevInfo->RemDevType == phNfc_eNfcIP1_Target))
      {
         /* Store the hanlde of the P2P device */
         hLlcpHandle = psRemoteDevList->hTargetDev;
         
         /* Notify manager that new a P2P device was found */
         e->CallVoidMethod(nat->manager, cached_NfcManager_notifyLlcpLinkActivation, tag);
         if(e->ExceptionCheck())
         {
            LOGE("Exception occured");
            kill_client(nat);
         }     
      }
      else
      {
         /* Notify manager that new a tag was found */
         e->CallVoidMethod(nat->manager, cached_NfcManager_notifyNdefMessageListeners, tag);
         if(e->ExceptionCheck())
         {
            LOGE("Exception occured");
            kill_client(nat);
         }     
      }
      e->DeleteLocalRef(tag);
   } 
}

static void nfc_jni_init_callback(void *pContext, NFCSTATUS status)
{
   struct nfc_jni_callback_data * pContextData =  (struct nfc_jni_callback_data*)pContext;

   LOG_CALLBACK("nfc_jni_init_callback", status);

   pContextData->status = status;
   sem_post(&pContextData->sem);
}

static void nfc_jni_deinit_callback(void *pContext, NFCSTATUS status)
{
   struct nfc_jni_callback_data * pContextData =  (struct nfc_jni_callback_data*)pContext;

   LOG_CALLBACK("nfc_jni_deinit_callback", status);

   pContextData->status = status;
   sem_post(&pContextData->sem);
}

/* Set Secure Element mode callback*/
static void nfc_jni_smartMX_setModeCb (void*            pContext,
                                       phLibNfc_Handle  hSecureElement,
                                       NFCSTATUS        status)
{
   struct nfc_jni_callback_data * pContextData =  (struct nfc_jni_callback_data*)pContext;

   LOG_CALLBACK("nfc_jni_smartMX_setModeCb", status);

   pContextData->status = status;
   sem_post(&pContextData->sem);
}

/* Card Emulation callback */
static void nfc_jni_transaction_callback(void *context,
   phLibNfc_eSE_EvtType_t evt_type, phLibNfc_Handle handle,
   phLibNfc_uSeEvtInfo_t *evt_info, NFCSTATUS status)
{
    JNIEnv *e;
    jobject tmp_array = NULL;
    jobject mifare_block = NULL;
    struct nfc_jni_native_data *nat;
    phNfc_sData_t *aid;
    phNfc_sData_t *mifare_command;
    struct nfc_jni_callback_data *pCallbackData;
    int i=0;

    LOG_CALLBACK("nfc_jni_transaction_callback", status);

    nat = (struct nfc_jni_native_data *)context;

    nat->vm->GetEnv( (void **)&e, nat->env_version);

    if(status == NFCSTATUS_SUCCESS)
    {
        switch(evt_type)
        {
            case phLibNfc_eSE_EvtStartTransaction:
            {
                TRACE("> SE EVT_START_TRANSACTION");
                if(evt_info->UiccEvtInfo.aid.length <= AID_MAXLEN)
                {
                    aid = &(evt_info->UiccEvtInfo.aid);

                    LOGD("> AID DETECTED");

                    if(aid != NULL)
                    {
                        char aid_str[AID_MAXLEN * 2 + 1];
                        aid_str[0] = '\0';
                        for (i = 0; i < (int) (aid->length) && i < AID_MAXLEN; i++) {
                          snprintf(&aid_str[i*2], 3, "%02x", aid->buffer[i]);
                        }
                        LOGD("> AID: %s", aid_str);

                        tmp_array = e->NewByteArray(aid->length);
                        if (tmp_array == NULL)
                        {
                            goto error;
                        }

                        e->SetByteArrayRegion((jbyteArray)tmp_array, 0, aid->length, (jbyte *)aid->buffer);
                        if(e->ExceptionCheck())
                        {
                            goto error;
                        }
                    }
                    else
                    {
                        goto error;
                    }

                    TRACE("Notify Nfc Service");
                    /* Notify manager that a new event occurred on a SE */
                    e->CallVoidMethod(nat->manager, cached_NfcManager_notifyTransactionListeners, tmp_array);
                    if(e->ExceptionCheck())
                    {
                        goto error;
                    }
                }
                else
                {
                    LOGD("> NO AID DETECTED");
                }
            }break;

            case phLibNfc_eSE_EvtApduReceived:
            {
                phNfc_sData_t *apdu = &(evt_info->UiccEvtInfo.aid);
                TRACE("> SE EVT_APDU_RECEIVED");

                if (apdu != NULL) {
                        TRACE("  APDU length=%d", apdu->length);
                        tmp_array = e->NewByteArray(apdu->length);
                        if (tmp_array == NULL) {
                            goto error;
                        }
                        e->SetByteArrayRegion((jbyteArray)tmp_array, 0, apdu->length, (jbyte *)apdu->buffer);
                        if (e->ExceptionCheck()) {
                            goto error;
                        }
                } else {
                        TRACE("  APDU EMPTY");
                }

                TRACE("Notify Nfc Service");
                e->CallVoidMethod(nat->manager, cached_NfcManager_notifySeApduReceived, tmp_array);
            }break;

            case phLibNfc_eSE_EvtCardRemoval:
            {
                TRACE("> SE EVT_EMV_CARD_REMOVAL");
                TRACE("Notify Nfc Service");
                e->CallVoidMethod(nat->manager, cached_NfcManager_notifySeEmvCardRemoval);
            }break;

            case phLibNfc_eSE_EvtMifareAccess:
            {
                TRACE("> SE EVT_MIFARE_ACCESS");
                mifare_command = &(evt_info->UiccEvtInfo.aid);
                TRACE("> MIFARE Block: %d",mifare_command->buffer[1]);
                tmp_array = e->NewByteArray(2);
                if (tmp_array == NULL)
                {
                    goto error;
                }

                e->SetByteArrayRegion((jbyteArray)tmp_array, 0, 2, (jbyte *)mifare_command->buffer);
                if(e->ExceptionCheck())
                {
                    goto error;
                }
                TRACE("Notify Nfc Service");
                e->CallVoidMethod(nat->manager, cached_NfcManager_notifySeMifareAccess, mifare_block);
            }break;

            case phLibNfc_eSE_EvtFieldOn:
            {
                TRACE("> SE EVT_FIELD_ON");
                TRACE("Notify Nfc Service");
                e->CallVoidMethod(nat->manager, cached_NfcManager_notifySeFieldActivated);
            }break;

            case phLibNfc_eSE_EvtFieldOff:
            {
                TRACE("> SE EVT_FIELD_OFF");
                TRACE("Notify Nfc Service");
                e->CallVoidMethod(nat->manager, cached_NfcManager_notifySeFieldDeactivated);
            }break;

            default:
            {
                TRACE("Unknown SE event");
            }break;
        }
    }
    else
    {
        LOGE("SE transaction notification error");
        goto error;
    }

    /* Function finished, now clean and return */
    goto clean_and_return;

 error:
    /* In case of error, just discard the notification */
    LOGE("Failed to send SE transaction notification");
    e->ExceptionClear();

 clean_and_return:
    if(tmp_array != NULL)
    {
       e->DeleteLocalRef(tmp_array);
    }
}

static void nfc_jni_se_set_mode_callback(void *pContext,
   phLibNfc_Handle handle, NFCSTATUS status)
{
   struct nfc_jni_callback_data * pContextData =  (struct nfc_jni_callback_data*)pContext;

   LOG_CALLBACK("nfc_jni_se_set_mode_callback", status);

   pContextData->status = status;
   sem_post(&pContextData->sem);
}

/*
 * NFCManager methods
 */

static short get_p2p_mode() {
    char value[PROPERTY_VALUE_MAX];
    property_get("debug.nfc.NXP_NFCI_MODE", value, "");
    if (value[0]) {
        short mode;
        mode = atoi(value);
        LOGD("debug.nfc.NXP_NFCI_MODE = %X", mode);
        return mode;
    }
    return phNfc_eP2P_ALL;  // default
}

static bool get_p2p_target_disable() {
    char value[PROPERTY_VALUE_MAX];
    property_get("debug.nfc.TARGET_DISABLE", value, "");
    if (value[0]) {
        int mode;
        mode = atoi(value);
        LOGD("debug.nfc.TARGET_DISABLE = %d", mode);
        return mode;
    }
    return FALSE;  // default
}


static void nfc_jni_start_discovery_locked(struct nfc_jni_native_data *nat)
{
   NFCSTATUS ret;
   struct nfc_jni_callback_data cb_data;

   /* Create the local semaphore */
   if (!nfc_cb_data_init(&cb_data, NULL))
   {
      goto clean_and_return;
   }
   /* Reset the PN544 ISO XCHG / sw watchdog timeouts */
   nfc_jni_reset_timeout_values();

   /* Reset device connected flag */
   device_connected_flag = 0;

   nat->discovery_cfg.NfcIP_Mode = get_p2p_mode();  //initiator
   nat->discovery_cfg.Duration = 300000; /* in ms */
   nat->discovery_cfg.NfcIP_Tgt_Disable = get_p2p_target_disable();

   TRACE("******  NFC Config Mode Reader ******");
      
   /* Register for the reader mode */
   REENTRANCE_LOCK();
   ret = phLibNfc_RemoteDev_NtfRegister(&nat->registry_info, nfc_jni_Discovery_notification_callback, (void *)nat);
   REENTRANCE_UNLOCK();
   if(ret != NFCSTATUS_SUCCESS)
   {
        LOGD("pphLibNfc_RemoteDev_NtfRegister returned 0x%02x",ret);
        goto clean_and_return;
   }
   TRACE("phLibNfc_RemoteDev_NtfRegister(%s-%s-%s-%s-%s-%s-%s-%s) returned 0x%x\n",
      nat->registry_info.Jewel==TRUE?"J":"",
      nat->registry_info.MifareUL==TRUE?"UL":"",
      nat->registry_info.MifareStd==TRUE?"Mi":"",
      nat->registry_info.Felica==TRUE?"F":"",
      nat->registry_info.ISO14443_4A==TRUE?"4A":"",
      nat->registry_info.ISO14443_4B==TRUE?"4B":"",
      nat->registry_info.NFC==TRUE?"P2P":"",
      nat->registry_info.ISO15693==TRUE?"R":"", ret);

   /* Register for the card emulation mode */
   REENTRANCE_LOCK();
   ret = phLibNfc_SE_NtfRegister(nfc_jni_transaction_callback,(void *)nat);
   REENTRANCE_UNLOCK();
   if(ret != NFCSTATUS_SUCCESS)
   {
        LOGD("pphLibNfc_RemoteDev_NtfRegister returned 0x%02x",ret);
        goto clean_and_return;
   }
   TRACE("phLibNfc_SE_NtfRegister returned 0x%x\n", ret);

   /* Start Polling loop */
   TRACE("******  Start NFC Discovery ******");
   REENTRANCE_LOCK();
   ret = phLibNfc_Mgt_ConfigureDiscovery(NFC_DISCOVERY_CONFIG,nat->discovery_cfg, nfc_jni_discover_callback, (void *)&cb_data);
   REENTRANCE_UNLOCK();
   TRACE("phLibNfc_Mgt_ConfigureDiscovery(%s-%s-%s-%s-%s-%s, %s-%x-%x) returned 0x%08x\n",
      nat->discovery_cfg.PollDevInfo.PollCfgInfo.EnableIso14443A==TRUE?"3A":"",
      nat->discovery_cfg.PollDevInfo.PollCfgInfo.EnableIso14443B==TRUE?"3B":"",
      nat->discovery_cfg.PollDevInfo.PollCfgInfo.EnableFelica212==TRUE?"F2":"",
      nat->discovery_cfg.PollDevInfo.PollCfgInfo.EnableFelica424==TRUE?"F4":"",
      nat->discovery_cfg.PollDevInfo.PollCfgInfo.EnableNfcActive==TRUE?"NFC":"",
      nat->discovery_cfg.PollDevInfo.PollCfgInfo.EnableIso15693==TRUE?"RFID":"",
      nat->discovery_cfg.PollDevInfo.PollCfgInfo.DisableCardEmulation==FALSE?"CE":"",
      nat->discovery_cfg.NfcIP_Mode, nat->discovery_cfg.Duration, ret);

   if(ret != NFCSTATUS_PENDING)
   {
      emergency_recovery(nat);
   }

   /* Wait for callback response */
   if(sem_wait(&cb_data.sem))
   {
      LOGE("Failed to wait for semaphore (errno=0x%08x)", errno);
      goto clean_and_return;
   }

clean_and_return:
   nfc_cb_data_deinit(&cb_data);
}

static void nfc_jni_stop_discovery_locked(struct nfc_jni_native_data *nat)
{
   phLibNfc_sADD_Cfg_t discovery_cfg;
   NFCSTATUS ret;
   struct nfc_jni_callback_data cb_data;

   /* Create the local semaphore */
   if (!nfc_cb_data_init(&cb_data, NULL))
   {
      goto clean_and_return;
   }

   discovery_cfg.PollDevInfo.PollEnabled = 0;
   discovery_cfg.Duration = 300000; /* in ms */
   discovery_cfg.NfcIP_Mode = phNfc_eDefaultP2PMode;
   discovery_cfg.NfcIP_Tgt_Disable = TRUE;
 
   /* Start Polling loop */
   TRACE("******  Stop NFC Discovery ******");
   REENTRANCE_LOCK();
   ret = phLibNfc_Mgt_ConfigureDiscovery(NFC_DISCOVERY_CONFIG,discovery_cfg, nfc_jni_discover_callback, (void *)&cb_data);
   REENTRANCE_UNLOCK();
   TRACE("phLibNfc_Mgt_ConfigureDiscovery(%s-%s-%s-%s-%s-%s, %s-%x-%x) returned 0x%08x\n",
      discovery_cfg.PollDevInfo.PollCfgInfo.EnableIso14443A==TRUE?"3A":"",
      discovery_cfg.PollDevInfo.PollCfgInfo.EnableIso14443B==TRUE?"3B":"",
      discovery_cfg.PollDevInfo.PollCfgInfo.EnableFelica212==TRUE?"F2":"",
      discovery_cfg.PollDevInfo.PollCfgInfo.EnableFelica424==TRUE?"F4":"",
      discovery_cfg.PollDevInfo.PollCfgInfo.EnableNfcActive==TRUE?"NFC":"",
      discovery_cfg.PollDevInfo.PollCfgInfo.EnableIso15693==TRUE?"RFID":"",
      discovery_cfg.PollDevInfo.PollCfgInfo.DisableCardEmulation==FALSE?"CE":"",
      discovery_cfg.NfcIP_Mode, discovery_cfg.Duration, ret);

   if(ret != NFCSTATUS_PENDING)
   {
      emergency_recovery(nat);
   }

   /* Wait for callback response */
   if(sem_wait(&cb_data.sem))
   {
      LOGE("Failed to wait for semaphore (errno=0x%08x)", errno);
      goto clean_and_return;
   }

clean_and_return:
   nfc_cb_data_deinit(&cb_data);
}


static void com_android_nfc_NfcManager_disableDiscovery(JNIEnv *e, jobject o)
{
    struct nfc_jni_native_data *nat;

    CONCURRENCY_LOCK();

    /* Retrieve native structure address */
    nat = nfc_jni_get_nat(e, o);
   
    nfc_jni_stop_discovery_locked(nat);

    CONCURRENCY_UNLOCK();

}
    
static void com_android_nfc_NfcManager_enableDiscovery(JNIEnv *e, jobject o) {
    NFCSTATUS ret;
    struct nfc_jni_native_data *nat;

    CONCURRENCY_LOCK();

    nat = nfc_jni_get_nat(e, o);
    nfc_jni_start_discovery_locked(nat);

    CONCURRENCY_UNLOCK();
}

static void com_android_nfc_NfcManager_doResetTimeouts( JNIEnv *e, jobject o) {
    CONCURRENCY_LOCK();
    nfc_jni_reset_timeout_values();
    CONCURRENCY_UNLOCK();
}

static void setFelicaTimeout(jint timeout) {
   // The Felica timeout is configurable in the PN544 upto a maximum of 255 ms.
   // It can be set to 0 to disable the timeout altogether, in which case we
   // use the sw watchdog as a fallback.
   if (timeout <= 255) {
       phLibNfc_SetFelicaTimeout(timeout);
   } else {
       // Disable hw timeout, use sw watchdog for timeout
       phLibNfc_SetFelicaTimeout(0);
       phLibNfc_SetHciTimeout(timeout);
   }

}
// Calculates ceiling log2 of value
static unsigned int log2(int value) {
    unsigned int ret = 0;
    bool isPowerOf2 = ((value & (value - 1)) == 0);
    while ( (value >> ret) > 1 ) ret++;
    if (!isPowerOf2) ret++;
    return ret;
}

// The Iso/Mifare Xchg timeout in PN544 is a non-linear function over X
// spanning 0 - 4.9s: timeout in seconds = (256 * 16 / 13560000) * 2 ^ X
//
// We keep the constant part of the formula in a static; note the factor
// 1000 off, which is due to the fact that the formula calculates seconds,
// but this method gets milliseconds as an argument.
static double nxp_nfc_timeout_factor = (256 * 16) / 13560.0;

static int calcTimeout(int timeout_in_ms) {
   // timeout = (256 * 16 / 13560000) * 2 ^ X
   // First find the first X for which timeout > requested timeout
   return (log2(ceil(((double) timeout_in_ms) / nxp_nfc_timeout_factor)));
}

static void setIsoDepTimeout(jint timeout) {
   if (timeout <= 4900) {
       int value = calcTimeout(timeout);
       // Then re-compute the actual timeout based on X
       double actual_timeout = nxp_nfc_timeout_factor * (1 << value);
       // Set the sw watchdog a bit longer (The PN544 timeout is very accurate,
       // but it will take some time to get back through the sw layers.
       // 500 ms should be enough).
       phLibNfc_SetHciTimeout(ceil(actual_timeout + 500));
       value |= 0x10; // bit 4 to enable timeout
       phLibNfc_SetIsoXchgTimeout(value);
   }
   else {
       // Also note that if we desire a timeout > 4.9s, the Iso Xchg timeout
       // must be disabled completely, to prevent the PN544 from aborting
       // the transaction. We reuse the HCI sw watchdog to catch the timeout
       // in that case.
       phLibNfc_SetIsoXchgTimeout(0x00);
       phLibNfc_SetHciTimeout(timeout);
   }
}

static void setNfcATimeout(jint timeout) {
   if (timeout <= 4900) {
       int value = calcTimeout(timeout);
       phLibNfc_SetMifareRawTimeout(value);
   }
   else {
       // Disable mifare raw timeout, use HCI sw watchdog instead
       phLibNfc_SetMifareRawTimeout(0x00);
       phLibNfc_SetHciTimeout(timeout);
   }
}

static bool com_android_nfc_NfcManager_doSetTimeout( JNIEnv *e, jobject o,
        jint tech, jint timeout) {
    bool success = false;
    CONCURRENCY_LOCK();
    if (timeout <= 0) {
        LOGE("Timeout must be positive.");
        return false;
    } else {
        switch (tech) {
            case TARGET_TYPE_MIFARE_CLASSIC:
            case TARGET_TYPE_MIFARE_UL:
                // Intentional fall-through, Mifare UL, Classic
                // transceive just uses raw 3A frames
            case TARGET_TYPE_ISO14443_3A:
                setNfcATimeout(timeout);
                success = true;
                break;
            case TARGET_TYPE_ISO14443_4:
                setIsoDepTimeout(timeout);
                success = true;
                break;
            case TARGET_TYPE_FELICA:
                setFelicaTimeout(timeout);
                success = true;
                break;
            default:
                LOGW("doSetTimeout: Timeout not supported for tech %d", tech);
                success = false;
        }
    }
    CONCURRENCY_UNLOCK();
    return success;
}

static jint com_android_nfc_NfcManager_doGetTimeout( JNIEnv *e, jobject o,
        jint tech) {
    int timeout = -1;
    CONCURRENCY_LOCK();
    switch (tech) {
        case TARGET_TYPE_MIFARE_CLASSIC:
        case TARGET_TYPE_MIFARE_UL:
            // Intentional fall-through, Mifare UL, Classic
            // transceive just uses raw 3A frames
        case TARGET_TYPE_ISO14443_3A:
            timeout = phLibNfc_GetMifareRawTimeout();
            if (timeout == 0) {
                timeout = phLibNfc_GetHciTimeout();
            } else {
                // Timeout returned from libnfc needs conversion to ms
                timeout = (nxp_nfc_timeout_factor * (1 << timeout));
            }
            break;
        case TARGET_TYPE_ISO14443_4:
            timeout = phLibNfc_GetIsoXchgTimeout() & 0x0F; // lower 4 bits only
            if (timeout == 0) {
                timeout = phLibNfc_GetHciTimeout();
            } else {
                // Timeout returned from libnfc needs conversion to ms
                timeout = (nxp_nfc_timeout_factor * (1 << timeout));
            }
            break;
        case TARGET_TYPE_FELICA:
            timeout = phLibNfc_GetFelicaTimeout();
            if (timeout == 0) {
                timeout = phLibNfc_GetHciTimeout();
            } else {
                // Felica timeout already in ms
            }
            break;
        default:
            LOGW("doGetTimeout: Timeout not supported for tech %d", tech);
            break;
    }
    CONCURRENCY_UNLOCK();
    return timeout;
}


static jboolean com_android_nfc_NfcManager_init_native_struc(JNIEnv *e, jobject o)
{
   NFCSTATUS status;
   struct nfc_jni_native_data *nat = NULL;
   jclass cls;
   jobject obj;
   jfieldID f;

   TRACE("******  Init Native Structure ******"); 

   /* Initialize native structure */
   nat = (nfc_jni_native_data*)malloc(sizeof(struct nfc_jni_native_data));
   if(nat == NULL)
   {
      LOGD("malloc of nfc_jni_native_data failed");
      return FALSE;   
   }
   memset(nat, 0, sizeof(*nat));
   e->GetJavaVM(&(nat->vm));
   nat->env_version = e->GetVersion();
   nat->manager = e->NewGlobalRef(o);
      
   cls = e->GetObjectClass(o);
   f = e->GetFieldID(cls, "mNative", "I");
   e->SetIntField(o, f, (jint)nat);
                 
   /* Initialize native cached references */
   cached_NfcManager_notifyNdefMessageListeners = e->GetMethodID(cls,
      "notifyNdefMessageListeners","(Lcom/android/nfc/nxp/NativeNfcTag;)V");

   cached_NfcManager_notifyTransactionListeners = e->GetMethodID(cls,
      "notifyTransactionListeners", "([B)V");
         
   cached_NfcManager_notifyLlcpLinkActivation = e->GetMethodID(cls,
      "notifyLlcpLinkActivation","(Lcom/android/nfc/nxp/NativeP2pDevice;)V");
         
   cached_NfcManager_notifyLlcpLinkDeactivated = e->GetMethodID(cls,
      "notifyLlcpLinkDeactivated","(Lcom/android/nfc/nxp/NativeP2pDevice;)V"); 
      
   cached_NfcManager_notifyTargetDeselected = e->GetMethodID(cls,
      "notifyTargetDeselected","()V");

   cached_NfcManager_notifySeFieldActivated = e->GetMethodID(cls,
      "notifySeFieldActivated", "()V");

   cached_NfcManager_notifySeFieldDeactivated = e->GetMethodID(cls,
      "notifySeFieldDeactivated", "()V");

   cached_NfcManager_notifySeApduReceived= e->GetMethodID(cls,
      "notifySeApduReceived", "([B)V");

   cached_NfcManager_notifySeMifareAccess = e->GetMethodID(cls,
      "notifySeMifareAccess", "([B)V");

   cached_NfcManager_notifySeEmvCardRemoval =  e->GetMethodID(cls,
      "notifySeEmvCardRemoval", "()V");

   if(nfc_jni_cache_object(e,"com/android/nfc/nxp/NativeNfcTag",&(nat->cached_NfcTag)) == -1)
   {
      LOGD("Native Structure initialization failed");
      return FALSE;
   }
         
   if(nfc_jni_cache_object(e,"com/android/nfc/nxp/NativeP2pDevice",&(nat->cached_P2pDevice)) == -1)
   {
      LOGD("Native Structure initialization failed");
      return FALSE;   
   }
   TRACE("****** Init Native Structure OK ******");
   return TRUE;

}

/* Init/Deinit method */
static jboolean com_android_nfc_NfcManager_initialize(JNIEnv *e, jobject o)
{
   struct nfc_jni_native_data *nat = NULL;
   int init_result = JNI_FALSE;
#ifdef TNFC_EMULATOR_ONLY
   char value[PROPERTY_VALUE_MAX];
#endif
   jboolean result;
   
   CONCURRENCY_LOCK();

#ifdef TNFC_EMULATOR_ONLY
   if (!property_get("ro.kernel.qemu", value, 0))
   {
      LOGE("NFC Initialization failed: not running in an emulator\n");
      goto clean_and_return;
   }
#endif

   /* Retrieve native structure address */
   nat = nfc_jni_get_nat(e, o);

   nat->seId = SMX_SECURE_ELEMENT_ID;

   nat->lto = 150;  // LLCP_LTO
   nat->miu = 128; // LLCP_MIU
   nat->wks = 1;  // LLCP_WKS
   nat->opt = 0;  // LLCP_OPT
   nat->discovery_cfg.PollDevInfo.PollCfgInfo.EnableIso14443A = TRUE;
   nat->discovery_cfg.PollDevInfo.PollCfgInfo.EnableIso14443B = TRUE;
   nat->discovery_cfg.PollDevInfo.PollCfgInfo.EnableFelica212 = TRUE;
   nat->discovery_cfg.PollDevInfo.PollCfgInfo.EnableFelica424 = TRUE;
   nat->discovery_cfg.PollDevInfo.PollCfgInfo.EnableIso15693 = TRUE;
   nat->discovery_cfg.PollDevInfo.PollCfgInfo.EnableNfcActive = TRUE;
   nat->discovery_cfg.PollDevInfo.PollCfgInfo.DisableCardEmulation = FALSE;

   nat->registry_info.MifareUL = TRUE;
   nat->registry_info.MifareStd = TRUE;
   nat->registry_info.ISO14443_4A = TRUE;
   nat->registry_info.ISO14443_4B = TRUE;
   nat->registry_info.Jewel = TRUE;
   nat->registry_info.Felica = TRUE;
   nat->registry_info.NFC = TRUE;
   nat->registry_info.ISO15693 = TRUE;

   exported_nat = nat;

   /* Perform the initialization */
   init_result = nfc_jni_initialize(nat);

clean_and_return:
   CONCURRENCY_UNLOCK();

   /* Convert the result and return */
   return (init_result==TRUE)?JNI_TRUE:JNI_FALSE;
}

static jboolean com_android_nfc_NfcManager_deinitialize(JNIEnv *e, jobject o)
{
   struct timespec ts;
   NFCSTATUS status;
   int result = JNI_FALSE;
   struct nfc_jni_native_data *nat;
   int bStackReset = FALSE;
   struct nfc_jni_callback_data cb_data;

   CONCURRENCY_LOCK();

   /* Retrieve native structure address */
   nat = nfc_jni_get_nat(e, o);

   /* Clear previous configuration */
   memset(&nat->discovery_cfg, 0, sizeof(phLibNfc_sADD_Cfg_t));
   memset(&nat->registry_info, 0, sizeof(phLibNfc_Registry_Info_t));

   /* Create the local semaphore */
   if (nfc_cb_data_init(&cb_data, NULL))
   {
      TRACE("phLibNfc_Mgt_DeInitialize()");
      REENTRANCE_LOCK();
      status = phLibNfc_Mgt_DeInitialize(gHWRef, nfc_jni_deinit_callback, (void *)&cb_data);
      REENTRANCE_UNLOCK();
      if (status == NFCSTATUS_PENDING)
      {
         TRACE("phLibNfc_Mgt_DeInitialize() returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));

         clock_gettime(CLOCK_REALTIME, &ts);
         ts.tv_sec += 5;

         /* Wait for callback response */
         if(sem_timedwait(&cb_data.sem, &ts) == -1)
         {
            LOGW("Operation timed out");
            bStackReset = TRUE;
         }

         if(cb_data.status != NFCSTATUS_SUCCESS)
         {
            LOGE("Failed to deinit the stack");
            bStackReset = TRUE;
         }
      }
      else
      {
         TRACE("phLibNfc_Mgt_DeInitialize() returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));
         bStackReset = TRUE;
      }
      nfc_cb_data_deinit(&cb_data);
   }
   else
   {
       LOGE("Failed to create semaphore (errno=0x%08x)", errno);
       bStackReset = TRUE;
   }

   kill_client(nat);

   if(bStackReset == TRUE)
   {
      /* Complete deinit. failed, try hard restart of NFC */
      LOGW("Reseting stack...");
      emergency_recovery(nat);
   }

   result = nfc_jni_unconfigure_driver(nat);

   TRACE("NFC Deinitialized");

   CONCURRENCY_UNLOCK();

   return TRUE;
}

/* Secure Element methods */
static jintArray com_android_nfc_NfcManager_doGetSecureElementList(JNIEnv *e, jobject o) {
    NFCSTATUS ret;
    jintArray list= NULL;
    phLibNfc_SE_List_t se_list[PHLIBNFC_MAXNO_OF_SE];
    uint8_t i, se_count = PHLIBNFC_MAXNO_OF_SE;

    TRACE("******  Get Secure Element List ******");
    
    TRACE("phLibNfc_SE_GetSecureElementList()");
    REENTRANCE_LOCK();
    ret = phLibNfc_SE_GetSecureElementList(se_list, &se_count);
    REENTRANCE_UNLOCK();
    if (ret != NFCSTATUS_SUCCESS) {
        LOGE("phLibNfc_SE_GetSecureElementList() returned 0x%04x[%s]", ret,
                nfc_jni_get_status_name(ret));
        return list;
    }
    TRACE("phLibNfc_SE_GetSecureElementList() returned 0x%04x[%s]", ret,
            nfc_jni_get_status_name(ret));

    TRACE("Nb SE: %d", se_count);
    list =e->NewIntArray(se_count);
    for (i = 0; i < se_count; i++) {
        if (se_list[i].eSE_Type == phLibNfc_SE_Type_SmartMX) {
            LOGD("phLibNfc_SE_GetSecureElementList(): SMX detected");
            LOGD("SE ID #%d: 0x%08x", i, se_list[i].hSecureElement);
        } else if(se_list[i].eSE_Type == phLibNfc_SE_Type_UICC) {
            LOGD("phLibNfc_SE_GetSecureElementList(): UICC detected");
            LOGD("SE ID #%d: 0x%08x", i, se_list[i].hSecureElement);
        }
        e->SetIntArrayRegion(list, i, 1, (jint*)&se_list[i].hSecureElement);
    }

    e->DeleteLocalRef(list);

    return list;
}

static void com_android_nfc_NfcManager_doSelectSecureElement(JNIEnv *e, jobject o) {
    NFCSTATUS ret;
    struct nfc_jni_native_data *nat;
    struct nfc_jni_callback_data cb_data;

    CONCURRENCY_LOCK();

    /* Retrieve native structure address */
    nat = nfc_jni_get_nat(e, o);

    /* Create the local semaphore */
    if (!nfc_cb_data_init(&cb_data, NULL)) {
        goto clean_and_return;
    }

    TRACE("******  Select Secure Element ******");

    TRACE("phLibNfc_SE_SetMode()");
    /* Set SE mode - Virtual */
    REENTRANCE_LOCK();
    ret = phLibNfc_SE_SetMode(nat->seId, phLibNfc_SE_ActModeVirtualVolatile, nfc_jni_se_set_mode_callback,
            (void *)&cb_data);
    REENTRANCE_UNLOCK();
    if (ret != NFCSTATUS_PENDING) {
        LOGD("phLibNfc_SE_SetMode() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
        goto clean_and_return;
    }
    TRACE("phLibNfc_SE_SetMode() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));

    /* Wait for callback response */
    if (sem_wait(&cb_data.sem)) {
        LOGE("Failed to wait for semaphore (errno=0x%08x)", errno);
        goto clean_and_return;
    }

    clean_and_return:
    nfc_cb_data_deinit(&cb_data);
    CONCURRENCY_UNLOCK();
}

static void com_android_nfc_NfcManager_doDeselectSecureElement(JNIEnv *e, jobject o) {
    NFCSTATUS ret;
    struct nfc_jni_native_data *nat;
    struct nfc_jni_callback_data cb_data;

    CONCURRENCY_LOCK();

    /* Retrieve native structure address */
    nat = nfc_jni_get_nat(e, o);

    /* Create the local semaphore */
    if (!nfc_cb_data_init(&cb_data, NULL)) {
        goto clean_and_return;
    }

    TRACE("****** Deselect Secure Element ******");

    TRACE("phLibNfc_SE_SetMode()");
    /* Set SE mode - Default */
    REENTRANCE_LOCK();
    ret = phLibNfc_SE_SetMode(nat->seId, phLibNfc_SE_ActModeDefault,
           nfc_jni_se_set_mode_callback, (void *)&cb_data);
    REENTRANCE_UNLOCK();
       
    TRACE("phLibNfc_SE_SetMode returned 0x%02x", ret);
    if (ret != NFCSTATUS_PENDING) {
        LOGE("phLibNfc_SE_SetMode() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
        goto clean_and_return;
    }
    TRACE("phLibNfc_SE_SetMode() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));

    /* Wait for callback response */
    if (sem_wait(&cb_data.sem)) {
        LOGE("Failed to wait for semaphore (errno=0x%08x)", errno);
        goto clean_and_return;
    }

    clean_and_return:
    nfc_cb_data_deinit(&cb_data);
    CONCURRENCY_UNLOCK();
}

/* Llcp methods */

static jboolean com_android_nfc_NfcManager_doCheckLlcp(JNIEnv *e, jobject o)
{
   NFCSTATUS ret;
   bool freeData = false;
   jboolean result = JNI_FALSE;
   struct nfc_jni_native_data *nat;
   struct nfc_jni_callback_data  *cb_data;


   CONCURRENCY_LOCK();

   /* Memory allocation for cb_data
    * This is on the heap because it is used by libnfc
    * even after this call has succesfully finished. It is only freed
    * upon link closure in nfc_jni_llcp_linkStatus_callback.
    */
   cb_data = (struct nfc_jni_callback_data*) malloc (sizeof(nfc_jni_callback_data));

   /* Retrieve native structure address */
   nat = nfc_jni_get_nat(e, o);

   /* Create the local semaphore */
   if (!nfc_cb_data_init(cb_data, (void*)nat))
   {
      goto clean_and_return;
   }

   /* Check LLCP compliancy */
   TRACE("phLibNfc_Llcp_CheckLlcp(hLlcpHandle=0x%08x)", hLlcpHandle);
   REENTRANCE_LOCK();
   ret = phLibNfc_Llcp_CheckLlcp(hLlcpHandle,
                                 nfc_jni_checkLlcp_callback,
                                 nfc_jni_llcp_linkStatus_callback,
                                 (void*)cb_data);
   REENTRANCE_UNLOCK();
   /* In case of a NFCIP return NFCSTATUS_SUCCESS and in case of an another protocol
    * NFCSTATUS_PENDING. In this case NFCSTATUS_SUCCESS will also cause the callback. */
   if(ret != NFCSTATUS_PENDING && ret != NFCSTATUS_SUCCESS)
   {
      LOGE("phLibNfc_Llcp_CheckLlcp() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
      freeData = true;
      goto clean_and_return;
   }
   TRACE("phLibNfc_Llcp_CheckLlcp() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));

   /* Wait for callback response */
   if(sem_wait(&cb_data->sem))
   {
      LOGE("Failed to wait for semaphore (errno=0x%08x)", errno);
      goto clean_and_return;
   }

   if(cb_data->status == NFCSTATUS_SUCCESS)
   {
      result = JNI_TRUE;
   }

clean_and_return:
   nfc_cb_data_deinit(cb_data);
   if (freeData) {
       free(cb_data);
   }
   CONCURRENCY_UNLOCK();
   return result;
}

static jboolean com_android_nfc_NfcManager_doActivateLlcp(JNIEnv *e, jobject o)
{
   NFCSTATUS ret;
   TRACE("phLibNfc_Llcp_Activate(hRemoteDevice=0x%08x)", hLlcpHandle);
   REENTRANCE_LOCK();
   ret = phLibNfc_Llcp_Activate(hLlcpHandle);
   REENTRANCE_UNLOCK();
   if(ret == NFCSTATUS_SUCCESS)
   {
      TRACE("phLibNfc_Llcp_Activate() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
      return JNI_TRUE;
   }
   else
   {
      LOGE("phLibNfc_Llcp_Activate() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
      return JNI_FALSE;   
   }
}



static jobject com_android_nfc_NfcManager_doCreateLlcpConnectionlessSocket(JNIEnv *e, jobject o, jint nSap)
{
   NFCSTATUS ret;
   jobject connectionlessSocket = NULL;
   phLibNfc_Handle hLlcpSocket;
   struct nfc_jni_native_data *nat;
   jclass clsNativeConnectionlessSocket;
   jfieldID f;
   
   /* Retrieve native structure address */
   nat = nfc_jni_get_nat(e, o); 
   
   /* Create socket */
   TRACE("phLibNfc_Llcp_Socket(eType=phFriNfc_LlcpTransport_eConnectionLess, ...)");
   REENTRANCE_LOCK();
   ret = phLibNfc_Llcp_Socket(phFriNfc_LlcpTransport_eConnectionLess,
                              NULL,
                              NULL,
                              &hLlcpSocket,
                              nfc_jni_llcp_transport_socket_err_callback,
                              (void*)nat);
   REENTRANCE_UNLOCK();
 
   if(ret != NFCSTATUS_SUCCESS)
   {
      lastErrorStatus = ret;
      LOGE("phLibNfc_Llcp_Socket() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
      return NULL;
   }
   TRACE("phLibNfc_Llcp_Socket() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
   
   
   /* Bind socket */
   TRACE("phLibNfc_Llcp_Bind(hSocket=0x%08x, nSap=0x%02x)", hLlcpSocket, nSap);
   REENTRANCE_LOCK();
   ret = phLibNfc_Llcp_Bind(hLlcpSocket,nSap);
   REENTRANCE_UNLOCK();
   if(ret != NFCSTATUS_SUCCESS)
   {
      lastErrorStatus = ret;
      LOGE("phLibNfc_Llcp_Bind() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
      /* Close socket created */
      REENTRANCE_LOCK();
      ret = phLibNfc_Llcp_Close(hLlcpSocket); 
      REENTRANCE_UNLOCK();
      return NULL;
   }
   TRACE("phLibNfc_Llcp_Bind() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
 
   
   /* Create new NativeLlcpConnectionlessSocket object */
   if(nfc_jni_cache_object(e,"com/android/nfc/nxp/NativeLlcpConnectionlessSocket",&(connectionlessSocket)) == -1)
   {
      return NULL;           
   } 
   
   /* Get NativeConnectionless class object */
   clsNativeConnectionlessSocket = e->GetObjectClass(connectionlessSocket);
   if(e->ExceptionCheck())
   {
      return NULL;  
   }
   
   /* Set socket handle */
   f = e->GetFieldID(clsNativeConnectionlessSocket, "mHandle", "I");
   e->SetIntField(connectionlessSocket, f,(jint)hLlcpSocket);
   TRACE("Connectionless socket Handle = %02x\n",hLlcpSocket);  
   
   /* Set the miu link of the connectionless socket */
   f = e->GetFieldID(clsNativeConnectionlessSocket, "mLinkMiu", "I");
   e->SetIntField(connectionlessSocket, f,(jint)PHFRINFC_LLCP_MIU_DEFAULT);
   TRACE("Connectionless socket Link MIU = %d\n",PHFRINFC_LLCP_MIU_DEFAULT);  
   
   /* Set socket SAP */
   f = e->GetFieldID(clsNativeConnectionlessSocket, "mSap", "I");
   e->SetIntField(connectionlessSocket, f,(jint)nSap);
   TRACE("Connectionless socket SAP = %d\n",nSap);  
   
   return connectionlessSocket;
}

static jobject com_android_nfc_NfcManager_doCreateLlcpServiceSocket(JNIEnv *e, jobject o, jint nSap, jstring sn, jint miu, jint rw, jint linearBufferLength)
{
   NFCSTATUS ret;
   phLibNfc_Handle hLlcpSocket;
   phLibNfc_Llcp_sSocketOptions_t sOptions;
   phNfc_sData_t sWorkingBuffer;
   phNfc_sData_t serviceName;
   struct nfc_jni_native_data *nat;
   jobject serviceSocket = NULL;
   jclass clsNativeLlcpServiceSocket;
   jfieldID f;  
  
   /* Retrieve native structure address */
   nat = nfc_jni_get_nat(e, o); 
   
   /* Set Connection Oriented socket options */
   sOptions.miu = miu;
   sOptions.rw  = rw;  
  
   /* Allocate Working buffer length */
   sWorkingBuffer.length = (miu*rw)+ miu + linearBufferLength;
   sWorkingBuffer.buffer = (uint8_t*)malloc(sWorkingBuffer.length);

   
   /* Create socket */
   TRACE("phLibNfc_Llcp_Socket(hRemoteDevice=0x%08x, eType=phFriNfc_LlcpTransport_eConnectionOriented, ...)", hLlcpHandle);
   REENTRANCE_LOCK();
   ret = phLibNfc_Llcp_Socket(phFriNfc_LlcpTransport_eConnectionOriented,
                              &sOptions,
                              &sWorkingBuffer,
                              &hLlcpSocket,
                              nfc_jni_llcp_transport_socket_err_callback,
                              (void*)nat);
   REENTRANCE_UNLOCK();
                                                     
   if(ret != NFCSTATUS_SUCCESS)
   {
      LOGE("phLibNfc_Llcp_Socket() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
      lastErrorStatus = ret;
      return NULL;
   }
   TRACE("phLibNfc_Llcp_Socket() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
   
   /* Bind socket */
   TRACE("phLibNfc_Llcp_Bind(hSocket=0x%08x, nSap=0x%02x)", hLlcpSocket, nSap);
   REENTRANCE_LOCK();
   ret = phLibNfc_Llcp_Bind(hLlcpSocket,nSap);
   REENTRANCE_UNLOCK();
   if(ret != NFCSTATUS_SUCCESS)
   {
      lastErrorStatus = ret;
      LOGE("phLibNfc_Llcp_Bind() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
      /* Close socket created */
      ret = phLibNfc_Llcp_Close(hLlcpSocket); 
      return NULL;
   }
   TRACE("phLibNfc_Llcp_Bind() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));

   /* Service socket */
   if (sn == NULL) {
       serviceName.buffer = NULL;
       serviceName.length = 0;
   } else {
       serviceName.buffer = (uint8_t*)e->GetStringUTFChars(sn, NULL);
       serviceName.length = (uint32_t)e->GetStringUTFLength(sn);
   }

   TRACE("phLibNfc_Llcp_Listen(hSocket=0x%08x, ...)", hLlcpSocket);
   REENTRANCE_LOCK();
   ret = phLibNfc_Llcp_Listen( hLlcpSocket,
                               &serviceName,
                               nfc_jni_llcp_transport_listen_socket_callback,
                               (void*)hLlcpSocket);
   REENTRANCE_UNLOCK();
                               
   if(ret != NFCSTATUS_SUCCESS)
   {
      LOGE("phLibNfc_Llcp_Listen() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
      lastErrorStatus = ret;
      /* Close created socket */
      REENTRANCE_LOCK();
      ret = phLibNfc_Llcp_Close(hLlcpSocket); 
      REENTRANCE_UNLOCK();
      return NULL;
   }                         
   TRACE("phLibNfc_Llcp_Listen() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
   
   /* Create new NativeLlcpServiceSocket object */
   if(nfc_jni_cache_object(e,"com/android/nfc/nxp/NativeLlcpServiceSocket",&(serviceSocket)) == -1)
   {
      LOGE("Llcp Socket object creation error");
      return NULL;           
   } 
   
   /* Get NativeLlcpServiceSocket class object */
   clsNativeLlcpServiceSocket = e->GetObjectClass(serviceSocket);
   if(e->ExceptionCheck())
   {
      LOGE("Llcp Socket get object class error");
      return NULL;  
   } 
   
   /* Set socket handle */
   f = e->GetFieldID(clsNativeLlcpServiceSocket, "mHandle", "I");
   e->SetIntField(serviceSocket, f,(jint)hLlcpSocket);
   TRACE("Service socket Handle = %02x\n",hLlcpSocket);  
   
   /* Set socket linear buffer length */
   f = e->GetFieldID(clsNativeLlcpServiceSocket, "mLocalLinearBufferLength", "I");
   e->SetIntField(serviceSocket, f,(jint)linearBufferLength);
   TRACE("Service socket Linear buffer length = %02x\n",linearBufferLength);  
   
   /* Set socket MIU */
   f = e->GetFieldID(clsNativeLlcpServiceSocket, "mLocalMiu", "I");
   e->SetIntField(serviceSocket, f,(jint)miu);
   TRACE("Service socket MIU = %d\n",miu);  
   
   /* Set socket RW */
   f = e->GetFieldID(clsNativeLlcpServiceSocket, "mLocalRw", "I");
   e->SetIntField(serviceSocket, f,(jint)rw);
   TRACE("Service socket RW = %d\n",rw);   
  
   return serviceSocket;
}

static jobject com_android_nfc_NfcManager_doCreateLlcpSocket(JNIEnv *e, jobject o, jint nSap, jint miu, jint rw, jint linearBufferLength)
{
   jobject clientSocket = NULL;
   NFCSTATUS ret;
   phLibNfc_Handle hLlcpSocket;
   phLibNfc_Llcp_sSocketOptions_t sOptions;
   phNfc_sData_t sWorkingBuffer;
   struct nfc_jni_native_data *nat;
   jclass clsNativeLlcpSocket;
   jfieldID f;
   
   /* Retrieve native structure address */
   nat = nfc_jni_get_nat(e, o); 
   
   /* Set Connection Oriented socket options */
   sOptions.miu = miu;
   sOptions.rw  = rw;
   
   /* Allocate Working buffer length */
   sWorkingBuffer.length = (miu*rw)+ miu + linearBufferLength;
   sWorkingBuffer.buffer = (uint8_t*)malloc(sWorkingBuffer.length);

   /* Create socket */
   TRACE("phLibNfc_Llcp_Socket(eType=phFriNfc_LlcpTransport_eConnectionOriented, ...)");
   REENTRANCE_LOCK();
   ret = phLibNfc_Llcp_Socket(phFriNfc_LlcpTransport_eConnectionOriented,
                              &sOptions,
                              &sWorkingBuffer,
                              &hLlcpSocket,
                              nfc_jni_llcp_transport_socket_err_callback,
                              (void*)nat);
   REENTRANCE_UNLOCK();

   if(ret != NFCSTATUS_SUCCESS)
   {
      LOGE("phLibNfc_Llcp_Socket() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
      lastErrorStatus = ret;
      return NULL;
   }
   TRACE("phLibNfc_Llcp_Socket() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
   
   /* Create new NativeLlcpSocket object */
   if(nfc_jni_cache_object(e,"com/android/nfc/nxp/NativeLlcpSocket",&(clientSocket)) == -1)
   {
      LOGE("Llcp socket object creation error");  
      return NULL;           
   } 
   
   /* Get NativeConnectionless class object */
   clsNativeLlcpSocket = e->GetObjectClass(clientSocket);
   if(e->ExceptionCheck())
   {
      LOGE("Get class object error");    
      return NULL;  
   }
   
   /* Test if an SAP number is present */
   if(nSap != 0)
   {
      /* Bind socket */
      TRACE("phLibNfc_Llcp_Bind(hSocket=0x%08x, nSap=0x%02x)", hLlcpSocket, nSap);
      REENTRANCE_LOCK();
      ret = phLibNfc_Llcp_Bind(hLlcpSocket,nSap);
      REENTRANCE_UNLOCK();
      if(ret != NFCSTATUS_SUCCESS)
      {
         lastErrorStatus = ret;
         LOGE("phLibNfc_Llcp_Bind() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
         /* Close socket created */
         REENTRANCE_LOCK();
         ret = phLibNfc_Llcp_Close(hLlcpSocket); 
         REENTRANCE_UNLOCK();
         return NULL;
      }
      TRACE("phLibNfc_Llcp_Bind() returned 0x%04x[%s]", ret, nfc_jni_get_status_name(ret));
      
      /* Set socket SAP */
      f = e->GetFieldID(clsNativeLlcpSocket, "mSap", "I");
      e->SetIntField(clientSocket, f,(jint)nSap);
      TRACE("socket SAP = %d\n",nSap);    
   }  
      
   /* Set socket handle */
   f = e->GetFieldID(clsNativeLlcpSocket, "mHandle", "I");
   e->SetIntField(clientSocket, f,(jint)hLlcpSocket);
   TRACE("socket Handle = %02x\n",hLlcpSocket);  
   
   /* Set socket MIU */
   f = e->GetFieldID(clsNativeLlcpSocket, "mLocalMiu", "I");
   e->SetIntField(clientSocket, f,(jint)miu);
   TRACE("socket MIU = %d\n",miu);  
   
   /* Set socket RW */
   f = e->GetFieldID(clsNativeLlcpSocket, "mLocalRw", "I");
   e->SetIntField(clientSocket, f,(jint)rw);
   TRACE("socket RW = %d\n",rw);   
   
  
   return clientSocket;
}

static jint com_android_nfc_NfcManager_doGetLastError(JNIEnv *e, jobject o)
{
   TRACE("Last Error Status = 0x%02x",lastErrorStatus);
   
   if(lastErrorStatus == NFCSTATUS_BUFFER_TOO_SMALL)
   {
      return ERROR_BUFFER_TOO_SMALL;
   }
   else if(lastErrorStatus == NFCSTATUS_INSUFFICIENT_RESOURCES)
   {
      return  ERROR_INSUFFICIENT_RESOURCES;
   }
   else
   {
      return lastErrorStatus;
   }
}

static void com_android_nfc_NfcManager_doAbort(JNIEnv *e, jobject o)
{
    emergency_recovery(NULL);
}

static jboolean com_android_nfc_NfcManager_doDownload(JNIEnv *e, jobject o)
{
    char* firmware_version;
    jboolean result = FALSE;
    int load_result;
    int unconfigure_status;
    bool drive_state = FALSE;
    uint8_t OutputBuffer[1];
    uint8_t InputBuffer[1];
    struct timespec ts;
    NFCSTATUS status = NFCSTATUS_FAILED;
    struct nfc_jni_callback_data cb_data;
    struct nfc_jni_native_data *nat = NULL;
    char value[PROPERTY_VALUE_MAX];

    /* Create the local semaphore */
    if (!nfc_cb_data_init(&cb_data, NULL))
    {
       result = FALSE;
       goto clean_and_return;
    }

    /* Retrieve native structure address */
    nat = nfc_jni_get_nat(e, o);

    CONCURRENCY_LOCK();

    /* Initialize Driver */
    if(!driverConfigured)
    {
        result = nfc_jni_configure_driver(nat);
        drive_state = TRUE;
    }

    TRACE("com_android_nfc_NfcManager_doDownload()");

    TRACE("Go in Download Mode");
    phLibNfc_Download_Mode();

    TRACE("Load new Firmware Image");
    load_result = phLibNfc_Load_Firmware_Image();
    if(load_result != 0)
    {
        TRACE("Load new Firmware Image - status = %d",load_result);
        result = FALSE;
        goto clean_and_return;
    }

    // Download
    gInputParam.buffer  = InputBuffer;
    gInputParam.length  = 0x01;
    gOutputParam.buffer = OutputBuffer;
    gOutputParam.length = 0x01;

    LOGD("Download new Firmware");
    REENTRANCE_LOCK();
    status = phLibNfc_Mgt_IoCtl(gHWRef,NFC_FW_DOWNLOAD, &gInputParam, &gOutputParam, nfc_jni_ioctl_callback, (void *)&cb_data);
    REENTRANCE_UNLOCK();
    if(status != NFCSTATUS_PENDING)
    {
        LOGE("phLibNfc_Mgt_IoCtl() (download) returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));
        result = FALSE;
        goto clean_and_return;
    }
    TRACE("phLibNfc_Mgt_IoCtl() (download) returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));

    /* Wait for callback response */
    if(sem_wait(&cb_data.sem))
    {
       LOGE("Failed to wait for semaphore (errno=0x%08x)", errno);
       result = FALSE;
       goto clean_and_return;
    }

    /* NOTE: we will get NFCSTATUS_FEATURE_NOT_SUPPORTED when we
       try to download an old-style firmware on top of a new-style
       firmware.  Hence, this is expected behavior, and not an
       error condition. */
    if(cb_data.status != NFCSTATUS_SUCCESS && cb_data.status != NFCSTATUS_FEATURE_NOT_SUPPORTED)
    {
        TRACE("phLibNfc_Mgt_IoCtl() (download) returned 0x%04x[%s]", status, nfc_jni_get_status_name(status));
        result = FALSE;
        goto clean_and_return;
    }

    if(cb_data.status == NFCSTATUS_FEATURE_NOT_SUPPORTED)
    {
        LOGW("Old-style firmware not installed on top of new-style firmware. Using existing firmware in the chip.");
    }

    /*Download is successful*/
    result = TRUE;

clean_and_return:
    TRACE("phLibNfc_HW_Reset()");
    phLibNfc_HW_Reset();
    /* Deinitialize Driver */
    if(drive_state)
    {
        result = nfc_jni_unconfigure_driver(nat);
    }
    CONCURRENCY_UNLOCK();
    nfc_cb_data_deinit(&cb_data);
    return result;
}

static jstring com_android_nfc_NfcManager_doDump(JNIEnv *e, jobject o)
{
    char buffer[100];
    snprintf(buffer, sizeof(buffer), "libnfc llc error_count=%u", libnfc_llc_error_count);
    return e->NewStringUTF(buffer);
}

/*
 * JNI registration.
 */
static JNINativeMethod gMethods[] =
{
   {"doDownload", "()Z",
        (void *)com_android_nfc_NfcManager_doDownload},

   {"initializeNativeStructure", "()Z",
      (void *)com_android_nfc_NfcManager_init_native_struc},

   {"initialize", "()Z",
      (void *)com_android_nfc_NfcManager_initialize},
 
   {"deinitialize", "()Z",
      (void *)com_android_nfc_NfcManager_deinitialize},
      
   {"enableDiscovery", "()V",
      (void *)com_android_nfc_NfcManager_enableDiscovery},

   {"doGetSecureElementList", "()[I",
      (void *)com_android_nfc_NfcManager_doGetSecureElementList},
      
   {"doSelectSecureElement", "()V",
      (void *)com_android_nfc_NfcManager_doSelectSecureElement},
      
   {"doDeselectSecureElement", "()V",
      (void *)com_android_nfc_NfcManager_doDeselectSecureElement},
      
   {"doCheckLlcp", "()Z",
      (void *)com_android_nfc_NfcManager_doCheckLlcp},
      
   {"doActivateLlcp", "()Z",
      (void *)com_android_nfc_NfcManager_doActivateLlcp},
            
   {"doCreateLlcpConnectionlessSocket", "(I)Lcom/android/nfc/nxp/NativeLlcpConnectionlessSocket;",
      (void *)com_android_nfc_NfcManager_doCreateLlcpConnectionlessSocket},
        
   {"doCreateLlcpServiceSocket", "(ILjava/lang/String;III)Lcom/android/nfc/nxp/NativeLlcpServiceSocket;",
      (void *)com_android_nfc_NfcManager_doCreateLlcpServiceSocket},
      
   {"doCreateLlcpSocket", "(IIII)Lcom/android/nfc/nxp/NativeLlcpSocket;",
      (void *)com_android_nfc_NfcManager_doCreateLlcpSocket},
      
   {"doGetLastError", "()I",
      (void *)com_android_nfc_NfcManager_doGetLastError},

   {"disableDiscovery", "()V",
      (void *)com_android_nfc_NfcManager_disableDiscovery},

   {"doSetTimeout", "(II)Z",
      (void *)com_android_nfc_NfcManager_doSetTimeout},

   {"doGetTimeout", "(I)I",
      (void *)com_android_nfc_NfcManager_doGetTimeout},

   {"doResetTimeouts", "()V",
      (void *)com_android_nfc_NfcManager_doResetTimeouts},

   {"doAbort", "()V",
      (void *)com_android_nfc_NfcManager_doAbort},

   {"doDump", "()Ljava/lang/String;",
      (void *)com_android_nfc_NfcManager_doDump},
};   
  
      
int register_com_android_nfc_NativeNfcManager(JNIEnv *e)
{
    nfc_jni_native_monitor_t *nfc_jni_native_monitor;

   nfc_jni_native_monitor = nfc_jni_init_monitor();
   if(nfc_jni_native_monitor == NULL)
   {
      LOGE("NFC Manager cannot recover native monitor %x\n", errno);
      return -1;
   }

   return jniRegisterNativeMethods(e,
      "com/android/nfc/nxp/NativeNfcManager",
      gMethods, NELEM(gMethods));
}

} /* namespace android */
