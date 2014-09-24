/*
 * Copyright (C) 2013 The Android Open Source Project
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

/*
 *  Manage the listen-mode routing table.
 */

#include <cutils/log.h>
#include <ScopedLocalRef.h>
#include <JNIHelp.h>
#include "config.h"
#include "JavaClassConstants.h"
#include "RoutingManager.h"

extern "C"
{
    #include "nfa_ee_api.h"
    #include "nfa_ce_api.h"
}
extern bool gActivated;
extern SyncEvent gDeactivatedEvent;


const JNINativeMethod RoutingManager::sMethods [] =
{
    {"doGetDefaultRouteDestination", "()I", (void*) RoutingManager::com_android_nfc_cardemulation_doGetDefaultRouteDestination},
    {"doGetDefaultOffHostRouteDestination", "()I", (void*) RoutingManager::com_android_nfc_cardemulation_doGetDefaultOffHostRouteDestination},
    {"doGetAidMatchingMode", "()I", (void*) RoutingManager::com_android_nfc_cardemulation_doGetAidMatchingMode}
};

static const int MAX_NUM_EE = 5;

RoutingManager::RoutingManager ()
{
    static const char fn [] = "RoutingManager::RoutingManager()";
    unsigned long num = 0;

    // Get the active SE
    if (GetNumValue("ACTIVE_SE", &num, sizeof(num)))
        mActiveSe = num;
    else
        mActiveSe = 0x00;

    // Get the "default" route
    if (GetNumValue("DEFAULT_ISODEP_ROUTE", &num, sizeof(num)))
        mDefaultEe = num;
    else
        mDefaultEe = 0x00;
    ALOGD("%s: default route is 0x%02X", fn, mDefaultEe);

    // Get the default "off-host" route.  This is hard-coded at the Java layer
    // but we can override it here to avoid forcing Java changes.
    if (GetNumValue("DEFAULT_OFFHOST_ROUTE", &num, sizeof(num)))
        mOffHostEe = num;
    else
        mOffHostEe = 0xf4;

    if (GetNumValue("AID_MATCHING_MODE", &num, sizeof(num)))
        mAidMatchingMode = num;
    else
        mAidMatchingMode = AID_MATCHING_EXACT_ONLY;

    ALOGD("%s: mOffHostEe=0x%02X", fn, mOffHostEe);

    memset (&mEeInfo, 0, sizeof(mEeInfo));
    mReceivedEeInfo = false;
    mSeTechMask = 0x00;
}

RoutingManager::~RoutingManager ()
{
    NFA_EeDeregister (nfaEeCallback);
}

bool RoutingManager::initialize (nfc_jni_native_data* native)
{
    static const char fn [] = "RoutingManager::initialize()";
    mNativeData = native;

    tNFA_STATUS nfaStat;
    {
        SyncEventGuard guard (mEeRegisterEvent);
        ALOGD ("%s: try ee register", fn);
        nfaStat = NFA_EeRegister (nfaEeCallback);
        if (nfaStat != NFA_STATUS_OK)
        {
            ALOGE ("%s: fail ee register; error=0x%X", fn, nfaStat);
            return false;
        }
        mEeRegisterEvent.wait ();
    }

    mRxDataBuffer.clear ();

    if (mActiveSe != 0) {
        {
            // Wait for EE info if needed
            SyncEventGuard guard (mEeInfoEvent);
            if (!mReceivedEeInfo)
            {
                ALOGE("Waiting for EE info");
                mEeInfoEvent.wait();
            }
        }
        for (UINT8 i = 0; i < mEeInfo.num_ee; i++)
        {
             ALOGD ("%s   EE[%u] Handle: 0x%04x  techA: 0x%02x  techB: 0x%02x  techF: 0x%02x  techBprime: 0x%02x",
                fn, i, mEeInfo.ee_disc_info[i].ee_handle,
                mEeInfo.ee_disc_info[i].la_protocol,
                mEeInfo.ee_disc_info[i].lb_protocol,
                mEeInfo.ee_disc_info[i].lf_protocol,
                mEeInfo.ee_disc_info[i].lbp_protocol);
             if (mEeInfo.ee_disc_info[i].ee_handle == (mActiveSe | NFA_HANDLE_GROUP_EE))
             {
                 if (mEeInfo.ee_disc_info[i].la_protocol != 0) mSeTechMask |= NFA_TECHNOLOGY_MASK_A;

                 if (mSeTechMask != 0x00)
                 {
                     ALOGD("Configuring tech mask 0x%02x on EE 0x%04x", mSeTechMask, mEeInfo.ee_disc_info[i].ee_handle);
                     nfaStat = NFA_CeConfigureUiccListenTech(mEeInfo.ee_disc_info[i].ee_handle, mSeTechMask);
                     if (nfaStat != NFA_STATUS_OK)
                         ALOGE ("Failed to configure UICC listen technologies.");
                     // Set technology routes to UICC if it's there
                     nfaStat = NFA_EeSetDefaultTechRouting(mEeInfo.ee_disc_info[i].ee_handle, mSeTechMask, mSeTechMask,
                             mSeTechMask);
                     if (nfaStat != NFA_STATUS_OK)
                         ALOGE ("Failed to configure UICC technology routing.");
                 }
             }
        }
    }

    // Tell the host-routing to only listen on Nfc-A
    nfaStat = NFA_CeSetIsoDepListenTech(NFA_TECHNOLOGY_MASK_A);
    if (nfaStat != NFA_STATUS_OK)
        ALOGE ("Failed to configure CE IsoDep technologies");

    // Register a wild-card for AIDs routed to the host
    nfaStat = NFA_CeRegisterAidOnDH (NULL, 0, stackCallback);
    if (nfaStat != NFA_STATUS_OK)
        ALOGE("Failed to register wildcard AID for DH");

    return true;
}

RoutingManager& RoutingManager::getInstance ()
{
    static RoutingManager manager;
    return manager;
}

void RoutingManager::enableRoutingToHost()
{
    tNFA_STATUS nfaStat;

    {
        SyncEventGuard guard (mRoutingEvent);

        // Route Nfc-A to host if we don't have a SE
        if (mSeTechMask == 0)
        {
            nfaStat = NFA_EeSetDefaultTechRouting (mDefaultEe, NFA_TECHNOLOGY_MASK_A, 0, 0);
            if (nfaStat == NFA_STATUS_OK)
                mRoutingEvent.wait ();
            else
                ALOGE ("Fail to set default tech routing");
        }

        // Default routing for IsoDep protocol
        nfaStat = NFA_EeSetDefaultProtoRouting(mDefaultEe, NFA_PROTOCOL_MASK_ISO_DEP, 0, 0);
        if (nfaStat == NFA_STATUS_OK)
            mRoutingEvent.wait ();
        else
            ALOGE ("Fail to set default proto routing");
    }
}

void RoutingManager::disableRoutingToHost()
{
    tNFA_STATUS nfaStat;

    {
        SyncEventGuard guard (mRoutingEvent);
        // Default routing for NFC-A technology if we don't have a SE
        if (mSeTechMask == 0)
        {
            nfaStat = NFA_EeSetDefaultTechRouting (mDefaultEe, 0, 0, 0);
            if (nfaStat == NFA_STATUS_OK)
                mRoutingEvent.wait ();
            else
                ALOGE ("Fail to set default tech routing");
        }

        // Default routing for IsoDep protocol
        nfaStat = NFA_EeSetDefaultProtoRouting(mDefaultEe, 0, 0, 0);
        if (nfaStat == NFA_STATUS_OK)
            mRoutingEvent.wait ();
        else
            ALOGE ("Fail to set default proto routing");
    }
}

bool RoutingManager::addAidRouting(const UINT8* aid, UINT8 aidLen, int route)
{
    static const char fn [] = "RoutingManager::addAidRouting";
    ALOGD ("%s: enter", fn);
    tNFA_STATUS nfaStat = NFA_EeAddAidRouting(route, aidLen, (UINT8*) aid, 0x01);
    if (nfaStat == NFA_STATUS_OK)
    {
        ALOGD ("%s: routed AID", fn);
        return true;
    } else
    {
        ALOGE ("%s: failed to route AID", fn);
        return false;
    }
}

bool RoutingManager::removeAidRouting(const UINT8* aid, UINT8 aidLen)
{
    static const char fn [] = "RoutingManager::removeAidRouting";
    ALOGD ("%s: enter", fn);
    tNFA_STATUS nfaStat = NFA_EeRemoveAidRouting(aidLen, (UINT8*) aid);
    if (nfaStat == NFA_STATUS_OK)
    {
        ALOGD ("%s: removed AID", fn);
        return true;
    } else
    {
        ALOGE ("%s: failed to remove AID", fn);
        return false;
    }
}

bool RoutingManager::commitRouting()
{
    static const char fn [] = "RoutingManager::commitRouting";
    tNFA_STATUS nfaStat = 0;
    ALOGD ("%s", fn);
    {
        SyncEventGuard guard (mEeUpdateEvent);
        nfaStat = NFA_EeUpdateNow();
        if (nfaStat == NFA_STATUS_OK)
        {
            mEeUpdateEvent.wait (); //wait for NFA_EE_UPDATED_EVT
        }
    }
    return (nfaStat == NFA_STATUS_OK);
}

void RoutingManager::onNfccShutdown ()
{
    static const char fn [] = "RoutingManager:onNfccShutdown";
    if (mActiveSe == 0x00) return;

    tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
    UINT8 actualNumEe = MAX_NUM_EE;
    tNFA_EE_INFO eeInfo[MAX_NUM_EE];

    memset (&eeInfo, 0, sizeof(eeInfo));
    if ((nfaStat = NFA_EeGetInfo (&actualNumEe, eeInfo)) != NFA_STATUS_OK)
    {
        ALOGE ("%s: fail get info; error=0x%X", fn, nfaStat);
        return;
    }
    if (actualNumEe != 0)
    {
        for (UINT8 xx = 0; xx < actualNumEe; xx++)
        {
            if ((eeInfo[xx].num_interface != 0)
                && (eeInfo[xx].ee_interface[0] != NCI_NFCEE_INTERFACE_HCI_ACCESS)
                && (eeInfo[xx].ee_status == NFA_EE_STATUS_ACTIVE))
            {
                ALOGD ("%s: Handle: 0x%04x Change Status Active to Inactive", fn, eeInfo[xx].ee_handle);
                SyncEventGuard guard (mEeSetModeEvent);
                if ((nfaStat = NFA_EeModeSet (eeInfo[xx].ee_handle, NFA_EE_MD_DEACTIVATE)) == NFA_STATUS_OK)
                {
                    mEeSetModeEvent.wait (); //wait for NFA_EE_MODE_SET_EVT
                }
                else
                {
                    ALOGE ("Failed to set EE inactive");
                }
            }
        }
    }
    else
    {
        ALOGD ("%s: No active EEs found", fn);
    }
}

void RoutingManager::notifyActivated ()
{
    JNIEnv* e = NULL;
    ScopedAttach attach(mNativeData->vm, &e);
    if (e == NULL)
    {
        ALOGE ("jni env is null");
        return;
    }

    e->CallVoidMethod (mNativeData->manager, android::gCachedNfcManagerNotifyHostEmuActivated);
    if (e->ExceptionCheck())
    {
        e->ExceptionClear();
        ALOGE ("fail notify");
    }
}

void RoutingManager::notifyDeactivated ()
{
    mRxDataBuffer.clear();
    JNIEnv* e = NULL;
    ScopedAttach attach(mNativeData->vm, &e);
    if (e == NULL)
    {
        ALOGE ("jni env is null");
        return;
    }

    e->CallVoidMethod (mNativeData->manager, android::gCachedNfcManagerNotifyHostEmuDeactivated);
    if (e->ExceptionCheck())
    {
        e->ExceptionClear();
        ALOGE ("fail notify");
    }
}

void RoutingManager::handleData (const UINT8* data, UINT32 dataLen, tNFA_STATUS status)
{
    if (dataLen <= 0)
    {
        ALOGE("no data");
        goto TheEnd;
    }

    if (status == NFA_STATUS_CONTINUE)
    {
        mRxDataBuffer.insert (mRxDataBuffer.end(), &data[0], &data[dataLen]); //append data; more to come
        return; //expect another NFA_CE_DATA_EVT to come
    }
    else if (status == NFA_STATUS_OK)
    {
        mRxDataBuffer.insert (mRxDataBuffer.end(), &data[0], &data[dataLen]); //append data
        //entire data packet has been received; no more NFA_CE_DATA_EVT
    }
    else if (status == NFA_STATUS_FAILED)
    {
        ALOGE("RoutingManager::handleData: read data fail");
        goto TheEnd;
    }

    {
        JNIEnv* e = NULL;
        ScopedAttach attach(mNativeData->vm, &e);
        if (e == NULL)
        {
            ALOGE ("jni env is null");
            goto TheEnd;
        }

        ScopedLocalRef<jobject> dataJavaArray(e, e->NewByteArray(mRxDataBuffer.size()));
        if (dataJavaArray.get() == NULL)
        {
            ALOGE ("fail allocate array");
            goto TheEnd;
        }

        e->SetByteArrayRegion ((jbyteArray)dataJavaArray.get(), 0, mRxDataBuffer.size(),
                (jbyte *)(&mRxDataBuffer[0]));
        if (e->ExceptionCheck())
        {
            e->ExceptionClear();
            ALOGE ("fail fill array");
            goto TheEnd;
        }

        e->CallVoidMethod (mNativeData->manager, android::gCachedNfcManagerNotifyHostEmuData, dataJavaArray.get());
        if (e->ExceptionCheck())
        {
            e->ExceptionClear();
            ALOGE ("fail notify");
        }
    }
TheEnd:
    mRxDataBuffer.clear();
}

void RoutingManager::stackCallback (UINT8 event, tNFA_CONN_EVT_DATA* eventData)
{
    static const char fn [] = "RoutingManager::stackCallback";
    ALOGD("%s: event=0x%X", fn, event);
    RoutingManager& routingManager = RoutingManager::getInstance();

    switch (event)
    {
    case NFA_CE_REGISTERED_EVT:
        {
            tNFA_CE_REGISTERED& ce_registered = eventData->ce_registered;
            ALOGD("%s: NFA_CE_REGISTERED_EVT; status=0x%X; h=0x%X", fn, ce_registered.status, ce_registered.handle);
        }
        break;

    case NFA_CE_DEREGISTERED_EVT:
        {
            tNFA_CE_DEREGISTERED& ce_deregistered = eventData->ce_deregistered;
            ALOGD("%s: NFA_CE_DEREGISTERED_EVT; h=0x%X", fn, ce_deregistered.handle);
        }
        break;

    case NFA_CE_ACTIVATED_EVT:
        {
            routingManager.notifyActivated();
        }
        break;

    case NFA_DEACTIVATED_EVT:
    case NFA_CE_DEACTIVATED_EVT:
        {
            ALOGD("%s: NFA_DEACTIVATED_EVT, NFA_CE_DEACTIVATED_EVT", fn);
            routingManager.notifyDeactivated();
            SyncEventGuard g (gDeactivatedEvent);
            gActivated = false; //guard this variable from multi-threaded access
            gDeactivatedEvent.notifyOne ();
        }
        break;

    case NFA_CE_DATA_EVT:
        {
            tNFA_CE_DATA& ce_data = eventData->ce_data;
            ALOGD("%s: NFA_CE_DATA_EVT; stat=0x%X; h=0x%X; data len=%u", fn, ce_data.status, ce_data.handle, ce_data.len);
            getInstance().handleData(ce_data.p_data, ce_data.len, ce_data.status);
        }
        break;
    }
}
/*******************************************************************************
**
** Function:        nfaEeCallback
**
** Description:     Receive execution environment-related events from stack.
**                  event: Event code.
**                  eventData: Event data.
**
** Returns:         None
**
*******************************************************************************/
void RoutingManager::nfaEeCallback (tNFA_EE_EVT event, tNFA_EE_CBACK_DATA* eventData)
{
    static const char fn [] = "RoutingManager::nfaEeCallback";

    RoutingManager& routingManager = RoutingManager::getInstance();

    switch (event)
    {
    case NFA_EE_REGISTER_EVT:
        {
            SyncEventGuard guard (routingManager.mEeRegisterEvent);
            ALOGD ("%s: NFA_EE_REGISTER_EVT; status=%u", fn, eventData->ee_register);
            routingManager.mEeRegisterEvent.notifyOne();
        }
        break;

    case NFA_EE_MODE_SET_EVT:
        {
            SyncEventGuard guard (routingManager.mEeSetModeEvent);
            ALOGD ("%s: NFA_EE_MODE_SET_EVT; status: 0x%04X  handle: 0x%04X  ", fn,
                    eventData->mode_set.status, eventData->mode_set.ee_handle);
            routingManager.mEeSetModeEvent.notifyOne();
        }
        break;

    case NFA_EE_SET_TECH_CFG_EVT:
        {
            ALOGD ("%s: NFA_EE_SET_TECH_CFG_EVT; status=0x%X", fn, eventData->status);
            SyncEventGuard guard(routingManager.mRoutingEvent);
            routingManager.mRoutingEvent.notifyOne();
        }
        break;

    case NFA_EE_SET_PROTO_CFG_EVT:
        {
            ALOGD ("%s: NFA_EE_SET_PROTO_CFG_EVT; status=0x%X", fn, eventData->status);
            SyncEventGuard guard(routingManager.mRoutingEvent);
            routingManager.mRoutingEvent.notifyOne();
        }
        break;

    case NFA_EE_ACTION_EVT:
        {
            tNFA_EE_ACTION& action = eventData->action;
            if (action.trigger == NFC_EE_TRIG_SELECT)
                ALOGD ("%s: NFA_EE_ACTION_EVT; h=0x%X; trigger=select (0x%X)", fn, action.ee_handle, action.trigger);
            else if (action.trigger == NFC_EE_TRIG_APP_INIT)
            {
                tNFC_APP_INIT& app_init = action.param.app_init;
                ALOGD ("%s: NFA_EE_ACTION_EVT; h=0x%X; trigger=app-init (0x%X); aid len=%u; data len=%u", fn,
                        action.ee_handle, action.trigger, app_init.len_aid, app_init.len_data);
            }
            else if (action.trigger == NFC_EE_TRIG_RF_PROTOCOL)
                ALOGD ("%s: NFA_EE_ACTION_EVT; h=0x%X; trigger=rf protocol (0x%X)", fn, action.ee_handle, action.trigger);
            else if (action.trigger == NFC_EE_TRIG_RF_TECHNOLOGY)
                ALOGD ("%s: NFA_EE_ACTION_EVT; h=0x%X; trigger=rf tech (0x%X)", fn, action.ee_handle, action.trigger);
            else
                ALOGE ("%s: NFA_EE_ACTION_EVT; h=0x%X; unknown trigger (0x%X)", fn, action.ee_handle, action.trigger);
        }
        break;

    case NFA_EE_DISCOVER_REQ_EVT:
        {
            ALOGD ("%s: NFA_EE_DISCOVER_REQ_EVT; status=0x%X; num ee=%u", __FUNCTION__,
                    eventData->discover_req.status, eventData->discover_req.num_ee);
            SyncEventGuard guard (routingManager.mEeInfoEvent);
            memcpy (&routingManager.mEeInfo, &eventData->discover_req, sizeof(routingManager.mEeInfo));
            routingManager.mReceivedEeInfo = true;
            routingManager.mEeInfoEvent.notifyOne();
        }
        break;

    case NFA_EE_NO_CB_ERR_EVT:
        ALOGD ("%s: NFA_EE_NO_CB_ERR_EVT  status=%u", fn, eventData->status);
        break;

    case NFA_EE_ADD_AID_EVT:
        {
            ALOGD ("%s: NFA_EE_ADD_AID_EVT  status=%u", fn, eventData->status);
        }
        break;

    case NFA_EE_REMOVE_AID_EVT:
        {
            ALOGD ("%s: NFA_EE_REMOVE_AID_EVT  status=%u", fn, eventData->status);
        }
        break;

    case NFA_EE_NEW_EE_EVT:
        {
            ALOGD ("%s: NFA_EE_NEW_EE_EVT  h=0x%X; status=%u", fn,
                eventData->new_ee.ee_handle, eventData->new_ee.ee_status);
        }
        break;

    case NFA_EE_UPDATED_EVT:
        {
            ALOGD("%s: NFA_EE_UPDATED_EVT", fn);
            SyncEventGuard guard(routingManager.mEeUpdateEvent);
            routingManager.mEeUpdateEvent.notifyOne();
        }
        break;

    default:
        ALOGE ("%s: unknown event=%u ????", fn, event);
        break;
    }
}

int RoutingManager::registerJniFunctions (JNIEnv* e)
{
    static const char fn [] = "RoutingManager::registerJniFunctions";
    ALOGD ("%s", fn);
    return jniRegisterNativeMethods (e, "com/android/nfc/cardemulation/AidRoutingManager", sMethods, NELEM(sMethods));
}

int RoutingManager::com_android_nfc_cardemulation_doGetDefaultRouteDestination (JNIEnv*)
{
    return getInstance().mDefaultEe;
}

int RoutingManager::com_android_nfc_cardemulation_doGetDefaultOffHostRouteDestination (JNIEnv*)
{
    return getInstance().mOffHostEe;
}

int RoutingManager::com_android_nfc_cardemulation_doGetAidMatchingMode (JNIEnv*)
{
    return getInstance().mAidMatchingMode;
}
