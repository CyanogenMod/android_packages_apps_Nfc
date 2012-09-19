/*
 * Copyright (C) 2012 The Android Open Source Project
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
 *  Manage listen-mode AID routing to the host.
 */
#include "OverrideLog.h"
#include "HostAidRouter.h"
#include "config.h"
#include "SecureElement.h"


HostAidRouter HostAidRouter::sHostAidRouter; //singleton HostAidRouter object


/*******************************************************************************
**
** Function:        HostAidRouter
**
** Description:     Private constructor to prevent public call.
**
** Returns:         None.
**
*******************************************************************************/
HostAidRouter::HostAidRouter ()
    : mTempHandle (NFA_HANDLE_INVALID),
      mIsFeatureEnabled (true)
{
}


/*******************************************************************************
**
** Function:        ~HostAidRouter
**
** Description:     Private destructor to prevent public call.
**
** Returns:         None.
**
*******************************************************************************/
HostAidRouter::~HostAidRouter ()
{
}


/*******************************************************************************
**
** Function:        getInstance
**
** Description:     Obtain a reference to the singleton object of HostAidRouter
**
** Returns:         Reference to HostAidRouter object.
**
*******************************************************************************/
HostAidRouter& HostAidRouter::getInstance ()
{
    return sHostAidRouter;
}


/*******************************************************************************
**
** Function:        initialize
**
** Description:     Initialize all resources.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool HostAidRouter::initialize ()
{
    unsigned long num = 0;
    mTempHandle = NFA_HANDLE_INVALID;
    mHandleDatabase.clear ();

    if (GetNumValue (NAME_REGISTER_VIRTUAL_SE, &num, sizeof (num)))
        mIsFeatureEnabled = num != 0;
    return true;
}


/*******************************************************************************
**
** Function:        addPpseRoute
**
** Description:     Route Proximity Payment System Environment request
**                  to the host.  This function is called when there is no
**                  route data.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool HostAidRouter::addPpseRoute ()
{
    static const char fn [] = "HostAidRouter::addPpseRoute";
    ALOGD ("%s: enter", fn);
    tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
    bool retval = false;

    if (! mIsFeatureEnabled)
    {
        ALOGD ("%s: feature disabled", fn);
        goto TheEnd;
    }

    {
        ALOGD ("%s: register PPSE AID", fn);
        SyncEventGuard guard (mRegisterEvent);
        mTempHandle = NFA_HANDLE_INVALID;
        nfaStat = NFA_CeRegisterAidOnDH ((UINT8*) "2PAY.SYS.DDF01", 14, stackCallback);
        if (nfaStat == NFA_STATUS_OK)
        {
            mRegisterEvent.wait (); //wait for NFA_CE_REGISTERED_EVT
            if (mTempHandle == NFA_HANDLE_INVALID)
            {
                ALOGE ("%s: received invalid handle", fn);
                goto TheEnd;
            }
            else
                mHandleDatabase.push_back (mTempHandle);
        }
        else
        {
            ALOGE ("%s: fail register", fn);
            goto TheEnd;
        }
    }
    retval = true;

TheEnd:
    ALOGD ("%s: exit; ok=%u", fn, retval);
    return retval;
}


/*******************************************************************************
**
** Function:        deleteAllRoutes
**
** Description:     Delete all AID routes to the host.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool HostAidRouter::deleteAllRoutes ()
{
    static const char fn [] = "HostAidRouter::deleteAllRoutes";
    ALOGD ("%s: enter", fn);
    tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
    bool retval = false;

    if (! mIsFeatureEnabled)
    {
        ALOGD ("%s: feature disabled", fn);
        goto TheEnd;
    }

    //deregister each registered AID from the stack
    for (AidHandleDatabase::iterator iter1 = mHandleDatabase.begin(); iter1 != mHandleDatabase.end(); iter1++)
    {
        tNFA_HANDLE aidHandle = *iter1;
        ALOGD ("%s: deregister h=0x%X", fn, aidHandle);
        SyncEventGuard guard (mDeregisterEvent);
        nfaStat = NFA_CeDeregisterAidOnDH (aidHandle);
        if (nfaStat == NFA_STATUS_OK)
            mDeregisterEvent.wait (); //wait for NFA_CE_DEREGISTERED_EVT
        else
            ALOGE ("%s: fail deregister", fn);
    }
    mHandleDatabase.clear ();
    retval = true;

TheEnd:
    ALOGD ("%s: exit; ok=%u", fn, retval);
    return retval;
}


/*******************************************************************************
**
** Function:        startRoute
**
** Description:     Begin to route AID request to the host.
**                  aid: Buffer that contains Application ID
**                  aidLen: Actual length of the buffer.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool HostAidRouter::startRoute (const UINT8* aid, UINT8 aidLen)
{
    static const char fn [] = "HostAidRouter::startRoute";
    ALOGD ("%s: enter", fn);
    tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
    bool retval = false;

    if (! mIsFeatureEnabled)
    {
        ALOGD ("%s: feature disabled", fn);
        goto TheEnd;
    }

    {
        ALOGD ("%s: register AID; len=%u", fn, aidLen);
        SyncEventGuard guard (mRegisterEvent);
        mTempHandle = NFA_HANDLE_INVALID;
        nfaStat = NFA_CeRegisterAidOnDH ((UINT8*) aid, aidLen, stackCallback);
        if (nfaStat == NFA_STATUS_OK)
        {
            mRegisterEvent.wait (); //wait for NFA_CE_REGISTERED_EVT
            if (mTempHandle == NFA_HANDLE_INVALID)
            {
                ALOGE ("%s: received invalid handle", fn);
                goto TheEnd;
            }
            else
                mHandleDatabase.push_back (mTempHandle);
        }
        else
        {
            ALOGE ("%s: fail register", fn);
            goto TheEnd;
        }
    }

TheEnd:
    ALOGD ("%s: exit; ok=%u", fn, retval);
    return retval;
}


/*******************************************************************************
**
** Function:        stackCallback
**
** Description:     Receive events from the NFC stack.
**
** Returns:         None.
**
*******************************************************************************/
void HostAidRouter::stackCallback (UINT8 event, tNFA_CONN_EVT_DATA* eventData)
{
    static const char fn [] = "HostAidRouter::stackCallback";
    ALOGD("%s: event=0x%X", fn, event);

    switch (event)
    {
    case NFA_CE_REGISTERED_EVT:
        {
            tNFA_CE_REGISTERED& ce_registered = eventData->ce_registered;
            ALOGD("%s: NFA_CE_REGISTERED_EVT; status=0x%X; h=0x%X", fn, ce_registered.status, ce_registered.handle);
            SyncEventGuard guard (sHostAidRouter.mRegisterEvent);
            if (ce_registered.status == NFA_STATUS_OK)
                sHostAidRouter.mTempHandle = ce_registered.handle;
            else
                sHostAidRouter.mTempHandle = NFA_HANDLE_INVALID;
            sHostAidRouter.mRegisterEvent.notifyOne();
        }
        break;

    case NFA_CE_DEREGISTERED_EVT:
        {
            tNFA_CE_DEREGISTERED& ce_deregistered = eventData->ce_deregistered;
            ALOGD("%s: NFA_CE_DEREGISTERED_EVT; h=0x%X", fn, ce_deregistered.handle);
            SyncEventGuard guard (sHostAidRouter.mDeregisterEvent);
            sHostAidRouter.mDeregisterEvent.notifyOne();
        }
        break;

    case NFA_CE_DATA_EVT:
        {
            tNFA_CE_DATA& ce_data = eventData->ce_data;
            ALOGD("%s: NFA_CE_DATA_EVT; h=0x%X; data len=%u", fn, ce_data.handle, ce_data.len);
            SecureElement::getInstance().notifyTransactionListenersOfAid ((UINT8 *)"2PAY.SYS.DDF01", 14);
        }
        break;
    }
}
