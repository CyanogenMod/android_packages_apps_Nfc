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
#pragma once
#include "SyncEvent.h"
#include "NfcJniUtil.h"
#include "RouteDataSet.h"
#include <vector>
extern "C"
{
    #include "nfa_api.h"
}


class HostAidRouter
{
public:
    /*******************************************************************************
    **
    ** Function:        getInstance
    **
    ** Description:     Obtain a reference to the singleton object of HostAidRouter
    **
    ** Returns:         Reference to HostAidRouter object.
    **
    *******************************************************************************/
    static HostAidRouter& getInstance ();


    /*******************************************************************************
    **
    ** Function:        initialize
    **
    ** Description:     Initialize all resources.
    **
    ** Returns:         True if ok.
    **
    *******************************************************************************/
    bool initialize ();


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
    bool addPpseRoute ();


    /*******************************************************************************
    **
    ** Function:        deleteAllRoutes
    **
    ** Description:     Delete all AID routes to the host.
    **
    ** Returns:         True if ok.
    **
    *******************************************************************************/
    bool deleteAllRoutes ();


    /*******************************************************************************
    **
    ** Function:        isFeatureEnabled
    **
    ** Description:     Is AID-routing-to-host feature enabled?
    **
    ** Returns:         True if enabled.
    **
    *******************************************************************************/
    bool isFeatureEnabled () {return mIsFeatureEnabled;};


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
    bool startRoute (const UINT8* aid, UINT8 aidLen);


private:
    typedef std::vector<tNFA_HANDLE> AidHandleDatabase;

    tNFA_HANDLE mTempHandle;
    bool mIsFeatureEnabled;
    static HostAidRouter sHostAidRouter; //singleton object
    RouteDataSet mRouteDataSet; //route data from xml file
    SyncEvent mRegisterEvent;
    SyncEvent mDeregisterEvent;
    AidHandleDatabase mHandleDatabase; //store all AID handles that are registered with the stack


    /*******************************************************************************
    **
    ** Function:        HostAidRouter
    **
    ** Description:     Private constructor to prevent public call.
    **
    ** Returns:         None.
    **
    *******************************************************************************/
    HostAidRouter ();


    /*******************************************************************************
    **
    ** Function:        ~HostAidRouter
    **
    ** Description:     Private destructor to prevent public call.
    **
    ** Returns:         None.
    **
    *******************************************************************************/
    ~HostAidRouter ();


    /*******************************************************************************
    **
    ** Function:        stackCallback
    **
    ** Description:     Receive events from the NFC stack.
    **
    ** Returns:         None.
    **
    *******************************************************************************/
    static void stackCallback (UINT8 event, tNFA_CONN_EVT_DATA* eventdata);
};
