/*****************************************************************************
**
**  Name:           PeerToPeer.cpp
**
**  Description:    Communicate with a peer using NFC-DEP, LLCP, SNEP.
**
**  Copyright (c) 2012, Broadcom Corp., All Rights Reserved.
**  Proprietary and confidential.
**
*****************************************************************************/
#include "OverrideLog.h"
#include "PeerToPeer.h"
#include "NfcJniUtil.h"
#include "llcp_defs.h"
#include "config.h"

namespace android
{
    extern jmethodID gCachedNfcManagerNotifyLlcpLinkActivation;
    extern jmethodID gCachedNfcManagerNotifyLlcpLinkDeactivated;
    extern void nativeNfcTag_registerNdefTypeHandler ();
    extern void nativeNfcTag_deregisterNdefTypeHandler ();
}


PeerToPeer PeerToPeer::sP2p;
const std::string PeerToPeer::sSnepServiceName ("urn:nfc:sn:snep");
const std::string PeerToPeer::sNppServiceName ("com.android.npp");


/*******************************************************************************
**
** Function:        PeerToPeer
**
** Description:     Initialize member variables.
**
** Returns:         None
**
*******************************************************************************/
PeerToPeer::PeerToPeer ()
:   mRemoteWKS (0),
    mIsP2pListening (false),
    mP2pListenTechMask (NFA_TECHNOLOGY_MASK_A
                        | NFA_TECHNOLOGY_MASK_F
                        | NFA_TECHNOLOGY_MASK_A_ACTIVE
                        | NFA_TECHNOLOGY_MASK_F_ACTIVE),
    mJniHandleSendingNppViaSnep (0),
    mSnepRegHandle (NFA_HANDLE_INVALID),
    mRcvFakeNppJniHandle (0),
    mNppFakeOutBuffer (NULL),
    mNppTotalLen (0),
    mNppReadSoFar (0),
    mNdefTypeHandlerHandle (NFA_HANDLE_INVALID),
    mNextJniHandle (1)
{
    unsigned long num = 0;
    memset (mServers, 0, sizeof(mServers));
    memset (mClients, 0, sizeof(mClients));
}


/*******************************************************************************
**
** Function:        ~PeerToPeer
**
** Description:     Free all resources.
**
** Returns:         None
**
*******************************************************************************/
PeerToPeer::~PeerToPeer ()
{
}


/*******************************************************************************
**
** Function:        getInstance
**
** Description:     Get the singleton PeerToPeer object.
**
** Returns:         Singleton PeerToPeer object.
**
*******************************************************************************/
PeerToPeer& PeerToPeer::getInstance ()
{
    return sP2p;
}


/*******************************************************************************
**
** Function:        initialize
**
** Description:     Initialize member variables.
**
** Returns:         None
**
*******************************************************************************/
void PeerToPeer::initialize ()
{
    ALOGD ("PeerToPeer::initialize");
    unsigned long num = 0;

    if (GetNumValue ("P2P_LISTEN_TECH_MASK", &num, sizeof (num)))
        mP2pListenTechMask = num;
}


/*******************************************************************************
**
** Function:        findServer
**
** Description:     Find a PeerToPeer object by connection handle.
**                  nfaP2pServerHandle: Connectin handle.
**
** Returns:         PeerToPeer object.
**
*******************************************************************************/
P2pServer *PeerToPeer::findServer (tNFA_HANDLE nfaP2pServerHandle)
{
    for (int i = 0; i < sMax; i++)
    {
        if ( (mServers[i] != NULL)
          && (mServers[i]->mNfaP2pServerHandle == nfaP2pServerHandle) )
        {
            return (mServers [i]);
        }
    }

    // If here, not found
    return NULL;
}


/*******************************************************************************
**
** Function:        findServer
**
** Description:     Find a PeerToPeer object by connection handle.
**                  serviceName: service name.
**
** Returns:         PeerToPeer object.
**
*******************************************************************************/
P2pServer *PeerToPeer::findServer (tBRCM_JNI_HANDLE jniHandle)
{
    for (int i = 0; i < sMax; i++)
    {
        if ( (mServers[i] != NULL)
          && (mServers[i]->mJniHandle == jniHandle) )
        {
            return (mServers [i]);
        }
    }

    // If here, not found
    return NULL;
}


/*******************************************************************************
**
** Function:        findServer
**
** Description:     Find a PeerToPeer object by service name
**                  serviceName: service name.
**
** Returns:         PeerToPeer object.
**
*******************************************************************************/
P2pServer *PeerToPeer::findServer (const char *serviceName)
{
    for (int i = 0; i < sMax; i++)
    {
        if ( (mServers[i] != NULL) && (mServers[i]->mServiceName.compare(serviceName) == 0) )
            return (mServers [i]);
    }

    // If here, not found
    return NULL;
}


/*******************************************************************************
**
** Function:        registerServer
**
** Description:     Let a server start listening for peer's connection request.
**                  jniHandle: Connection handle.
**                  serviceName: Server's service name.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool PeerToPeer::registerServer (tBRCM_JNI_HANDLE jniHandle, const char *serviceName)
{
    static const char fn [] = "PeerToPeer::registerServer";
    ALOGD ("%s: enter; service name: %s  JNI handle: %u", fn, serviceName, jniHandle);
    tNFA_STATUS     stat  = NFA_STATUS_OK;
    P2pServer       *pSrv = NULL;
    UINT8           serverSap = NFA_P2P_ANY_SAP;

    // Check if already registered
    if ((pSrv = findServer(serviceName)) != NULL)
    {
        ALOGD ("%s: service name=%s  already registered, handle: 0x%04x", fn, serviceName, pSrv->mNfaP2pServerHandle);

        // Update JNI handle
        pSrv->mJniHandle = jniHandle;
        return (true);
    }

    for (int ii = 0; ii < sMax; ii++)
    {
        if (mServers[ii] == NULL)
        {
            pSrv = mServers[ii] = new P2pServer;
            pSrv->mServiceName.assign (serviceName);
            pSrv->mJniHandle = jniHandle;

            ALOGD ("%s: added new p2p server  index: %d  handle: %u  name: %s", fn, ii, jniHandle, serviceName);
            break;
        }
    }

    if (pSrv == NULL)
    {
        ALOGE ("%s: service name=%s  no free entry", fn, serviceName);
        return (false);
    }

    /**********************
    default values for all LLCP parameters:
    - Local Link MIU (LLCP_MIU)
    - Option parameter (LLCP_OPT_VALUE)
    - Response Waiting Time Index (LLCP_WAITING_TIME)
    - Local Link Timeout (LLCP_LTO_VALUE)
    - Inactivity Timeout as initiator role (LLCP_INIT_INACTIVITY_TIMEOUT)
    - Inactivity Timeout as target role (LLCP_TARGET_INACTIVITY_TIMEOUT)
    - Delay SYMM response (LLCP_DELAY_RESP_TIME)
    - Data link connection timeout (LLCP_DATA_LINK_CONNECTION_TOUT)
    - Delay timeout to send first PDU as initiator (LLCP_DELAY_TIME_TO_SEND_FIRST_PDU)
    ************************/
    stat = NFA_P2pSetLLCPConfig (LLCP_MIU,
            LLCP_OPT_VALUE,
            LLCP_WAITING_TIME,
            LLCP_LTO_VALUE,
            0, //use 0 for infinite timeout for symmetry procedure when acting as initiator
            0, //use 0 for infinite timeout for symmetry procedure when acting as target
            LLCP_DELAY_RESP_TIME,
            LLCP_DATA_LINK_CONNECTION_TOUT,
            LLCP_DELAY_TIME_TO_SEND_FIRST_PDU);
    if (stat != NFA_STATUS_OK)
        ALOGE ("%s: fail set LLCP config; error=0x%X", fn, stat);

    if (sSnepServiceName.compare(serviceName) == 0)
        serverSap = LLCP_SAP_SNEP; //LLCP_SAP_SNEP == 4

    SyncEventGuard guard (pSrv->mRegServerEvent);
    stat = NFA_P2pRegisterServer (serverSap, NFA_P2P_DLINK_TYPE, const_cast<char*>(serviceName), nfaServerCallback);
    if (stat != NFA_STATUS_OK)
    {
        ALOGE ("%s: fail register p2p server; error=0x%X", fn, stat);
        removeServer (jniHandle);
        return (false);
    }
    ALOGD ("%s: wait for listen-completion event", fn);
    // Wait for NFA_P2P_REG_SERVER_EVT
    pSrv->mRegServerEvent.wait ();

    if (pSrv->mNfaP2pServerHandle == NFA_HANDLE_INVALID)
    {
        ALOGE ("%s: invalid server handle", fn);
        removeServer (jniHandle);
        return (false);
    }
    else
    {
        ALOGD ("%s: got new p2p server h=0x%X", fn, pSrv->mNfaP2pServerHandle);
        return (true);
    }
}


/*******************************************************************************
**
** Function:        removeServer
**
** Description:     Free resources related to a server.
**                  jniHandle: Connection handle.
**
** Returns:         None
**
*******************************************************************************/
void PeerToPeer::removeServer (tBRCM_JNI_HANDLE jniHandle)
{
    static const char fn [] = "PeerToPeer::removeServer";

    for (int i = 0; i < sMax; i++)
    {
        if ( (mServers[i] != NULL) && (mServers[i]->mJniHandle == jniHandle) )
        {
            ALOGD ("%s: server jni_handle: %u;  nfa_handle: 0x%04x; name: %s; index=%d",
                    fn, jniHandle, mServers[i]->mNfaP2pServerHandle, mServers[i]->mServiceName.c_str(), i);

            delete mServers [i];
            mServers [i] = NULL;
            return;
        }
    }
    ALOGE ("%s: unknown server jni handle: %u", fn, jniHandle);
}


/*******************************************************************************
**
** Function:        llcpActivatedHandler
**
** Description:     Receive LLLCP-activated event from stack.
**                  nat: JVM-related data.
**                  activated: Event data.
**
** Returns:         None
**
*******************************************************************************/
void PeerToPeer::llcpActivatedHandler (nfc_jni_native_data* nat, tNFA_LLCP_ACTIVATED& activated)
{
    static const char fn [] = "PeerToPeer::llcpActivatedHandler";
    ALOGD ("%s: enter", fn);
    JNIEnv* e = NULL;
    jclass tag_cls = NULL;
    jobject tag = NULL;
    jmethodID ctor = 0;
    jfieldID f = 0;

    //no longer need to receive NDEF message from a tag
    android::nativeNfcTag_deregisterNdefTypeHandler ();

    //register a type handler in case we need to send NDEF messages received from SNEP through NPP
    mNdefTypeHandlerHandle = NFA_HANDLE_INVALID;
    NFA_RegisterNDefTypeHandler (TRUE, NFA_TNF_DEFAULT, (UINT8 *)"", 0, ndefTypeCallback);

    mRemoteWKS = activated.remote_wks;

    nat->vm->AttachCurrentThread (&e, NULL);
    if (e == NULL)
    {
        ALOGE ("%s: jni env is null", fn);
        return;
    }

    ALOGD ("%s: get object class", fn);
    tag_cls = e->GetObjectClass (nat->cached_P2pDevice);
    if (e->ExceptionCheck())
    {
        e->ExceptionClear();
        ALOGE ("%s: fail get p2p device", fn);
        goto TheEnd;
    }

    ALOGD ("%s: instantiate", fn);
    /* New target instance */
    ctor = e->GetMethodID (tag_cls, "<init>", "()V");
    tag = e->NewObject (tag_cls, ctor);

    /* Set P2P Target mode */
    f = e->GetFieldID (tag_cls, "mMode", "I");

    if (activated.is_initiator == TRUE)
    {
        ALOGD ("%s: p2p initiator", fn);
        e->SetIntField (tag, f, (jint) MODE_P2P_INITIATOR);
    }
    else
    {
        ALOGD ("%s: p2p target", fn);
        e->SetIntField (tag, f, (jint) MODE_P2P_TARGET);
    }

    /* Set tag handle */
    f = e->GetFieldID (tag_cls, "mHandle", "I");
    e->SetIntField (tag, f, (jint) 0x1234); // ?? This handle is not used for anything

    if (nat->tag != NULL)
    {
        e->DeleteGlobalRef (nat->tag);
    }
    nat->tag = e->NewGlobalRef (tag);

    ALOGD ("%s: notify nfc service", fn);

    /* Notify manager that new a P2P device was found */
    e->CallVoidMethod (nat->manager, android::gCachedNfcManagerNotifyLlcpLinkActivation, tag);
    if (e->ExceptionCheck())
    {
        e->ExceptionClear();
        ALOGE ("%s: fail notify", fn);
    }

    e->DeleteLocalRef (tag);

TheEnd:
    nat->vm->DetachCurrentThread ();
    ALOGD ("%s: exit", fn);
}


/*******************************************************************************
**
** Function:        llcpDeactivatedHandler
**
** Description:     Receive LLLCP-deactivated event from stack.
**                  nat: JVM-related data.
**                  deactivated: Event data.
**
** Returns:         None
**
*******************************************************************************/
void PeerToPeer::llcpDeactivatedHandler (nfc_jni_native_data* nat, tNFA_LLCP_DEACTIVATED& deactivated)
{
    static const char fn [] = "PeerToPeer::llcpDeactivatedHandler";
    ALOGD ("%s: enter", fn);
    JNIEnv* e = NULL;

    nat->vm->AttachCurrentThread (&e, NULL);
    if (e == NULL)
    {
        ALOGE ("%s: jni env is null", fn);
        return;
    }

    ALOGD ("%s: notify nfc service", fn);
    /* Notify manager that the LLCP is lost or deactivated */
    e->CallVoidMethod (nat->manager, android::gCachedNfcManagerNotifyLlcpLinkDeactivated, nat->tag);
    if (e->ExceptionCheck())
    {
        e->ExceptionClear();
        ALOGE ("%s: fail notify", fn);
    }

    nat->vm->DetachCurrentThread ();

    //PeerToPeer no longer needs to handle NDEF data event
    NFA_DeregisterNDefTypeHandler (mNdefTypeHandlerHandle);
    mNdefTypeHandlerHandle = NFA_HANDLE_INVALID;

    //let the tag-reading code handle NDEF data event
    android::nativeNfcTag_registerNdefTypeHandler ();
    ALOGD ("%s: exit", fn);
}


/*******************************************************************************
**
** Function:        accept
**
** Description:     Accept a peer's request to connect.
**                  serverJniHandle: Server's handle.
**                  connJniHandle: Connection handle.
**                  maxInfoUnit: Maximum information unit.
**                  recvWindow: Receive window size.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool PeerToPeer::accept (tBRCM_JNI_HANDLE serverJniHandle, tBRCM_JNI_HANDLE connJniHandle, int maxInfoUnit, int recvWindow)
{
    static const char fn [] = "PeerToPeer::accept";
    tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
    NfaConn     *pConn = NULL;
    bool        stat = false;
    int         ii = 0;
    P2pServer   *pSrv = NULL;

    ALOGD ("%s: enter; server jni handle: %u; conn jni handle: %u; maxInfoUnit: %d; recvWindow: %d", fn,
            serverJniHandle, connJniHandle, maxInfoUnit, recvWindow);

    if ((pSrv = findServer (serverJniHandle)) == NULL)
    {
        ALOGE ("%s: unknown server jni handle: %u", fn, serverJniHandle);
        return (false);
    }

    // First, find a free connection block to handle the connection
    for (ii = 0; ii < MAX_NFA_CONNS_PER_SERVER; ii++)
    {
        if (pSrv->mServerConn[ii] == NULL)
        {
            ALOGD ("%s: serverJniHandle: %u; connJniHandle: %u; allocate server conn index: %u", fn,
                    serverJniHandle, connJniHandle, ii);
            pSrv->mServerConn[ii] = new NfaConn;
            pSrv->mServerConn[ii]->mJniHandle = connJniHandle;
            break;
        }
    }

    if (ii == MAX_NFA_CONNS_PER_SERVER)
    {
        ALOGE ("%s: fail allocate connection block", fn);
        return (false);
    }

    {
        // Wait for NFA_P2P_CONN_REQ_EVT or NFA_NDEF_DATA_EVT when remote device requests connection
        SyncEventGuard guard (pSrv->mConnRequestEvent);
        ALOGD ("%s: serverJniHandle: %u; connJniHandle: %u; server conn index: %u; wait for incoming connection", fn,
                serverJniHandle, connJniHandle, ii);
        pSrv->mConnRequestEvent.wait();
        ALOGD ("%s: serverJniHandle: %u; connJniHandle: %u; server conn index: %u; nfa conn h: 0x%X; got incoming connection", fn,
                serverJniHandle, connJniHandle, ii, pSrv->mServerConn[ii]->mNfaConnHandle);
    }

    // If we had gotten a message via SNEP, fake it out to be for NPP
    if (mRcvFakeNppJniHandle == serverJniHandle)
    {
        ALOGD ("%s:  server jni handle %u diverted to NPP fake receive on conn jni handle %u", fn, serverJniHandle, connJniHandle);
        delete (pSrv->mServerConn[ii]);
        pSrv->mServerConn[ii] = NULL;
        mRcvFakeNppJniHandle = connJniHandle;
        return (true);
    }

    if (pSrv->mServerConn[ii]->mNfaConnHandle == NFA_HANDLE_INVALID)
    {
        delete (pSrv->mServerConn[ii]);
        pSrv->mServerConn[ii] = NULL;
        ALOGD ("%s: no handle assigned", fn);
        return (false);
    }

    ALOGD ("%s: serverJniHandle: %u; connJniHandle: %u; server conn index: %u; nfa conn h: 0x%X; try accept", fn,
            serverJniHandle, connJniHandle, ii, pSrv->mServerConn[ii]->mNfaConnHandle);
    nfaStat = NFA_P2pAcceptConn (pSrv->mServerConn[ii]->mNfaConnHandle, maxInfoUnit, recvWindow);

    if (nfaStat != NFA_STATUS_OK)
    {
        ALOGE ("%s: fail to accept remote; error=0x%X", fn, nfaStat);
        return (false);
    }

    ALOGD ("%s: exit; serverJniHandle: %u; connJniHandle: %u; server conn index: %u; nfa conn h: 0x%X", fn,
            serverJniHandle, connJniHandle, ii, pSrv->mServerConn[ii]->mNfaConnHandle);
    return (true);
}


/*******************************************************************************
**
** Function:        deregisterServer
**
** Description:     Stop a P2pServer from listening for peer.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool PeerToPeer::deregisterServer (tBRCM_JNI_HANDLE jniHandle)
{
    static const char fn [] = "PeerToPeer::deregisterServer";
    ALOGD ("%s: enter; JNI handle: %u", fn, jniHandle);
    tNFA_STATUS     nfaStat = NFA_STATUS_FAILED;
    P2pServer       *pSrv = NULL;

    if ((pSrv = findServer (jniHandle)) == NULL)
    {
        ALOGE ("%s: unknown service handle: %u", fn, jniHandle);
        return (false);
    }

    // Server does not call NFA_P2pDisconnect(), so unblock the accept()
    SyncEventGuard guard (pSrv->mConnRequestEvent);
    pSrv->mConnRequestEvent.notifyOne();

    nfaStat = NFA_P2pDeregister (pSrv->mNfaP2pServerHandle);
    if (nfaStat != NFA_STATUS_OK)
    {
        ALOGE ("%s: deregister error=0x%X", fn, nfaStat);
    }

    removeServer (jniHandle);

    ALOGD ("%s: exit", fn);
    return true;
}


/*******************************************************************************
**
** Function:        createClient
**
** Description:     Create a P2pClient object for a new out-bound connection.
**                  jniHandle: Connection handle.
**                  miu: Maximum information unit.
**                  rw: Receive window size.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool PeerToPeer::createClient (tBRCM_JNI_HANDLE jniHandle, UINT16 miu, UINT8 rw)
{
    static const char fn [] = "PeerToPeer::createClient";
    int i = 0;
    ALOGD ("%s: enter: jni h: %u  miu: %u  rw: %u", fn, jniHandle, miu, rw);

    for (i = 0; i < sMax; i++)
    {
        if (mClients[i] == NULL)
        {
            mClients [i] = new P2pClient;

            mClients [i]->mClientConn.mJniHandle   = jniHandle;
            mClients [i]->mClientConn.mMaxInfoUnit = miu;
            mClients [i]->mClientConn.mRecvWindow  = rw;
            break;
        }
    }

    if (i == sMax)
    {
        ALOGE ("%s: fail", fn);
        return (false);
    }

    ALOGD ("%s: pClient: 0x%p  assigned for client jniHandle: %u", fn, mClients[i], jniHandle);

    SyncEventGuard guard (mClients[i]->mRegisteringEvent);
    NFA_P2pRegisterClient (NFA_P2P_DLINK_TYPE, nfaClientCallback);
    mClients[i]->mRegisteringEvent.wait(); //wait for NFA_P2P_REG_CLIENT_EVT

    if (mClients[i]->mNfaP2pClientHandle != NFA_HANDLE_INVALID)
    {
        ALOGD ("%s: exit; new client jniHandle: %u   NFA Handle: 0x%04x", fn, jniHandle, mClients[i]->mClientConn.mNfaConnHandle);
        return (true);
    }
    else
    {
        ALOGE ("%s: FAILED; new client jniHandle: %u   NFA Handle: 0x%04x", fn, jniHandle, mClients[i]->mClientConn.mNfaConnHandle);
        removeConn (jniHandle);
        return (false);
    }
}


/*******************************************************************************
**
** Function:        removeConn
**
** Description:     Free resources related to a connection.
**                  jniHandle: Connection handle.
**
** Returns:         None
**
*******************************************************************************/
void PeerToPeer::removeConn(tBRCM_JNI_HANDLE jniHandle)
{
    static const char fn[] = "PeerToPeer::removeConn";
    int ii = 0, jj = 0;

    // If the connection is a for a client, delete the client itself
    for (ii = 0; ii < sMax; ii++)
    {
        if (mClients[ii] && (mClients[ii]->mClientConn.mJniHandle == jniHandle))
        {
            if (mClients[ii]->mNfaP2pClientHandle != NFA_HANDLE_INVALID)
                NFA_P2pDeregister (mClients[ii]->mNfaP2pClientHandle);

            delete mClients[ii];
            mClients[ii] = NULL;
            ALOGD ("%s: deleted client handle: %u  index: %u", fn, jniHandle, ii);
            return;
        }
    }

    // If the connection is for a server, just delete the connection
    for (ii = 0; ii < sMax; ii++)
    {
        if (mServers[ii] != NULL)
        {
            for (jj = 0; jj < MAX_NFA_CONNS_PER_SERVER; jj++)
            {
                if ( (mServers[ii]->mServerConn[jj] != NULL)
                 &&  (mServers[ii]->mServerConn[jj]->mJniHandle == jniHandle) )
                {
                    ALOGD ("%s: delete server conn jni h: %u; index: %d; server jni h: %u",
                            fn, mServers[ii]->mServerConn[jj]->mJniHandle, jj, mServers[ii]->mJniHandle);
                    delete mServers[ii]->mServerConn[jj];
                    mServers[ii]->mServerConn[jj] = NULL;
                    return;
                }
            }
        }
    }

    if (jniHandle == mRcvFakeNppJniHandle)
    {
        ALOGD ("%s: Reset mRcvFakeNppJniHandle: %u", fn, jniHandle);
        mRcvFakeNppJniHandle = 0;
        if (mNppFakeOutBuffer != NULL)
        {
            free (mNppFakeOutBuffer);
            mNppFakeOutBuffer = NULL;
        }
    }
    else
        ALOGE ("%s: could not find handle: %u", fn, jniHandle);
}


/*******************************************************************************
**
** Function:        connectConnOriented
**
** Description:     Estabish a connection-oriented connection to a peer.
**                  jniHandle: Connection handle.
**                  serviceName: Peer's service name.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool PeerToPeer::connectConnOriented (tBRCM_JNI_HANDLE jniHandle, const char* serviceName)
{
    static const char fn [] = "PeerToPeer::connectConnOriented";
    ALOGD ("%s: enter; h: %u  service name=%s", fn, jniHandle, serviceName);

    // If we are connecting to NPP and the other side supports SNEP, use SNEP
    if ( (sNppServiceName.compare(serviceName)==0) && (mSnepRegHandle != NFA_HANDLE_INVALID) )
    {
        P2pClient   *pClient = NULL;

        if ((pClient = findClient (jniHandle)) == NULL)
        {
            ALOGE ("%s: can't find client, JNI handle: %u", fn, jniHandle);
            return (false);
        }

        if (mJniHandleSendingNppViaSnep != 0)
        {
            ALOGE ("%s: SNEP already active, SNEP JNI handle: %u  new JNI handle: %u", fn, mJniHandleSendingNppViaSnep, jniHandle);
            return (false);
        }

        // Save JNI Handle and try to connect to SNEP
        mJniHandleSendingNppViaSnep = jniHandle;

        if (NFA_SnepConnect (mSnepRegHandle, const_cast<char*>("urn:nfc:sn:snep")) == NFA_STATUS_OK)
        {
            SyncEventGuard guard (pClient->mSnepEvent);
            pClient->mSnepEvent.wait();

            // If the connect attempt failed, connection handle is invalid
            if (pClient->mSnepConnHandle != NFA_HANDLE_INVALID)
            {
                // return true, as if we were connected.
                pClient->mClientConn.mRemoteMaxInfoUnit = 248;
                pClient->mClientConn.mRemoteRecvWindow  = 1;
                return (true);
            }
        }
        mJniHandleSendingNppViaSnep = 0;
    }

    // If here, we did not establish a SNEP connection
    bool stat = createDataLinkConn (jniHandle, serviceName, 0);
    ALOGD ("%s: exit; h: %u  stat: %u", fn, jniHandle, stat);
    return stat;
}


/*******************************************************************************
**
** Function:        connectConnOriented
**
** Description:     Estabish a connection-oriented connection to a peer.
**                  jniHandle: Connection handle.
**                  destinationSap: Peer's service access point.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool PeerToPeer::connectConnOriented (tBRCM_JNI_HANDLE jniHandle, UINT8 destinationSap)
{
    static const char fn [] = "PeerToPeer::connectConnOriented";
    ALOGD ("%s: enter; h: %u  dest sap: 0x%X", fn, jniHandle, destinationSap);
    bool stat = createDataLinkConn (jniHandle, NULL, destinationSap);
    ALOGD ("%s: exit; h: %u  stat: %u", fn, jniHandle, stat);
    return stat;
}


/*******************************************************************************
**
** Function:        createDataLinkConn
**
** Description:     Estabish a connection-oriented connection to a peer.
**                  jniHandle: Connection handle.
**                  serviceName: Peer's service name.
**                  destinationSap: Peer's service access point.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool PeerToPeer::createDataLinkConn (tBRCM_JNI_HANDLE jniHandle, const char* serviceName, UINT8 destinationSap)
{
    static const char fn [] = "PeerToPeer::createDataLinkConn";
    ALOGD ("%s: enter", fn);
    tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
    P2pClient   *pClient = NULL;

    if ((pClient = findClient (jniHandle)) == NULL)
    {
        ALOGE ("%s: can't find client, JNI handle: %u", fn, jniHandle);
        return (false);
    }

    SyncEventGuard guard (pClient->mConnectingEvent);
    pClient->mIsConnecting = true;

    if (serviceName)
        nfaStat = NFA_P2pConnectByName (pClient->mNfaP2pClientHandle,
                const_cast<char*>(serviceName), pClient->mClientConn.mMaxInfoUnit,
                pClient->mClientConn.mRecvWindow);
    else if (destinationSap)
        nfaStat = NFA_P2pConnectBySap (pClient->mNfaP2pClientHandle, destinationSap,
                pClient->mClientConn.mMaxInfoUnit, pClient->mClientConn.mRecvWindow);

    if (nfaStat == NFA_STATUS_OK)
    {
        ALOGD ("%s: wait for connected event  mConnectingEvent: 0x%p", fn, pClient);
        pClient->mConnectingEvent.wait();

        if (pClient->mClientConn.mNfaConnHandle == NFA_HANDLE_INVALID)
        {
            removeConn (jniHandle);
            nfaStat = NFA_STATUS_FAILED;
        }
        else
            pClient->mIsConnecting = false;
    }
    else
    {
        removeConn (jniHandle);
        ALOGE ("%s: fail; error=0x%X", fn, nfaStat);
    }

    ALOGD ("%s: exit", fn);
    return nfaStat == NFA_STATUS_OK;
}


/*******************************************************************************
**
** Function:        findClient
**
** Description:     Find a PeerToPeer object with a client connection handle.
**                  nfaConnHandle: Connection handle.
**
** Returns:         PeerToPeer object.
**
*******************************************************************************/
P2pClient *PeerToPeer::findClient (tNFA_HANDLE nfaConnHandle)
{
    for (int i = 0; i < sMax; i++)
    {
        if (mClients[i] && (mClients[i]->mNfaP2pClientHandle == nfaConnHandle))
            return (mClients[i]);
    }
    return (NULL);
}


/*******************************************************************************
**
** Function:        findClient
**
** Description:     Find a PeerToPeer object with a client connection handle.
**                  jniHandle: Connection handle.
**
** Returns:         PeerToPeer object.
**
*******************************************************************************/
P2pClient *PeerToPeer::findClient (tBRCM_JNI_HANDLE jniHandle)
{
    for (int i = 0; i < sMax; i++)
    {
        if (mClients[i] && (mClients[i]->mClientConn.mJniHandle == jniHandle))
            return (mClients[i]);
    }
    return (NULL);
}


/*******************************************************************************
**
** Function:        findClientCon
**
** Description:     Find a PeerToPeer object with a client connection handle.
**                  nfaConnHandle: Connection handle.
**
** Returns:         PeerToPeer object.
**
*******************************************************************************/
P2pClient *PeerToPeer::findClientCon (tNFA_HANDLE nfaConnHandle)
{
    for (int i = 0; i < sMax; i++)
    {
        if (mClients[i] && (mClients[i]->mClientConn.mNfaConnHandle == nfaConnHandle))
            return (mClients[i]);
    }
    return (NULL);
}


/*******************************************************************************
**
** Function:        findConnection
**
** Description:     Find a PeerToPeer object with a connection handle.
**                  nfaConnHandle: Connection handle.
**
** Returns:         PeerToPeer object.
**
*******************************************************************************/
NfaConn *PeerToPeer::findConnection (tNFA_HANDLE nfaConnHandle)
{
    int ii = 0, jj = 0;

    // First, look through all the client control blocks
    for (ii = 0; ii < sMax; ii++)
    {
        if ( (mClients[ii] != NULL)
           && (mClients[ii]->mClientConn.mNfaConnHandle == nfaConnHandle) )
            return (&mClients[ii]->mClientConn);
    }

    // Not found yet. Look through all the server control blocks
    for (ii = 0; ii < sMax; ii++)
    {
        if (mServers[ii] != NULL)
        {
            for (jj = 0; jj < MAX_NFA_CONNS_PER_SERVER; jj++)
            {
                if ( (mServers[ii]->mServerConn[jj] != NULL)
                 &&  (mServers[ii]->mServerConn[jj]->mNfaConnHandle == nfaConnHandle) )
                    return (mServers[ii]->mServerConn[jj]);
            }
        }
    }

    // Not found...
    return NULL;
}


/*******************************************************************************
**
** Function:        findConnection
**
** Description:     Find a PeerToPeer object with a connection handle.
**                  jniHandle: Connection handle.
**
** Returns:         PeerToPeer object.
**
*******************************************************************************/
NfaConn *PeerToPeer::findConnection (tBRCM_JNI_HANDLE jniHandle)
{
    int ii = 0, jj = 0;

    // First, look through all the client control blocks
    for (ii = 0; ii < sMax; ii++)
    {
        if ( (mClients[ii] != NULL)
          && (mClients[ii]->mClientConn.mJniHandle == jniHandle) )
            return (&mClients[ii]->mClientConn);
    }

    // Not found yet. Look through all the server control blocks
    for (ii = 0; ii < sMax; ii++)
    {
        if (mServers[ii] != NULL)
        {
            for (jj = 0; jj < MAX_NFA_CONNS_PER_SERVER; jj++)
            {
                if ( (mServers[ii]->mServerConn[jj] != NULL)
                 &&  (mServers[ii]->mServerConn[jj]->mJniHandle == jniHandle) )
                    return (mServers[ii]->mServerConn[jj]);
            }
        }
    }

    // Not found...
    return NULL;
}


/*******************************************************************************
**
** Function:        send
**
** Description:     Send data to peer.
**                  jniHandle: Handle of connection.
**                  buffer: Buffer of data.
**                  bufferLen: Length of data.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool PeerToPeer::send (tBRCM_JNI_HANDLE jniHandle, UINT8 *buffer, UINT16 bufferLen)
{
    static const char fn [] = "PeerToPeer::send";
    tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
    NfaConn     *pConn =  NULL;

    if ((pConn = findConnection (jniHandle)) == NULL)
    {
        ALOGE ("%s: can't find connection handle: %u", fn, jniHandle);
        return (false);
    }

    ALOGD_IF ((appl_trace_level>=BT_TRACE_LEVEL_DEBUG), "%s: send data; jniHandle: %u  nfaHandle: 0x%04X  mJniHandleSendingNppViaSnep: %u",
            fn, pConn->mJniHandle, pConn->mNfaConnHandle, mJniHandleSendingNppViaSnep);

    // Is this a SNEP fake-out
    if (jniHandle == mJniHandleSendingNppViaSnep)
    {
        return (sendViaSnep(jniHandle, buffer, bufferLen));
    }

    nfaStat = NFA_P2pSendData (pConn->mNfaConnHandle, bufferLen, buffer);

    while (nfaStat == NFA_STATUS_CONGESTED)
    {
        SyncEventGuard guard (pConn->mCongEvent);
        pConn->mCongEvent.wait ();

        if (pConn->mNfaConnHandle == NFA_HANDLE_INVALID)
            return (false);

        nfaStat = NFA_P2pSendData (pConn->mNfaConnHandle, bufferLen, buffer);
    }

    if (nfaStat == NFA_STATUS_OK)
        ALOGD_IF ((appl_trace_level>=BT_TRACE_LEVEL_DEBUG), "%s: exit OK; JNI handle: %u  NFA Handle: 0x%04x", fn, jniHandle, pConn->mNfaConnHandle);
    else
        ALOGE ("%s: Data not sent; JNI handle: %u  NFA Handle: 0x%04x  error: 0x%04x",
              fn, jniHandle, pConn->mNfaConnHandle, nfaStat);

    return nfaStat == NFA_STATUS_OK;
}


/*******************************************************************************
**
** Function:        sendViaSnep
**
** Description:     Send out-bound data to the stack's SNEP protocol.
**                  jniHandle: Handle of connection.
**                  buffer: Buffer of data.
**                  dataLen: Length of data.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool PeerToPeer::sendViaSnep (tBRCM_JNI_HANDLE jniHandle, UINT8 *buffer, UINT16 dataLen)
{
    static const char fn [] = "PeerToPeer::sendViaSnep";
    tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
    P2pClient   *pClient = NULL;

    if ((pClient = findClient (jniHandle)) == NULL)
    {
        ALOGE ("%s: can't find client, JNI handle: %u", fn, jniHandle);
        mJniHandleSendingNppViaSnep = 0;
        return (false);
    }

    ALOGD_IF ((appl_trace_level>=BT_TRACE_LEVEL_DEBUG), "%s: send data; jniHandle: %u  mSnepNdefMsgLen: %lu  mSnepNdefBufLen: %lu  dataLen: %d",
          fn, jniHandle, pClient->mSnepNdefMsgLen, pClient->mSnepNdefBufLen, dataLen);

    if (pClient->mSnepNdefMsgLen == 0)
    {
        pClient->mSnepNdefMsgLen = (buffer[6] << 24) | (buffer[7] << 16) | (buffer[8] << 8) | buffer[9];
        if ((pClient->mSnepNdefBuf = (UINT8 *)malloc (pClient->mSnepNdefMsgLen + 1000)) == NULL)
        {
            ALOGE ("%s: can't malloc len: %lu", fn, pClient->mSnepNdefMsgLen);
            mJniHandleSendingNppViaSnep = 0;
            return (false);
        }
        buffer += 10;
        dataLen -= 10;
    }

    if ((pClient->mSnepNdefBufLen + dataLen) > pClient->mSnepNdefMsgLen)
    {
        ALOGE ("%s: len error mSnepNdefBufLen: %lu  dataLen: %u  mSnepNdefMsgLen: %lu", fn,
              pClient->mSnepNdefBufLen, dataLen, pClient->mSnepNdefMsgLen);
        mJniHandleSendingNppViaSnep = 0;
        free (pClient->mSnepNdefBuf);
        pClient->mSnepNdefBuf = NULL;
        return (false);
    }

    // Save the data in the buffer
    memcpy (pClient->mSnepNdefBuf + pClient->mSnepNdefBufLen, buffer, dataLen);

    pClient->mSnepNdefBufLen += dataLen;

    // If we got all the data, send it via SNEP
    if (pClient->mSnepNdefBufLen == pClient->mSnepNdefMsgLen)
    {
        ALOGD ("%s  GKI_poolcount(2): %u   GKI_poolfreecount(2): %u", fn, GKI_poolcount(2), GKI_poolfreecount(2));

        nfaStat = NFA_SnepPut (pClient->mSnepConnHandle, pClient->mSnepNdefBufLen, pClient->mSnepNdefBuf);

        if (nfaStat != NFA_STATUS_OK)
        {
            ALOGE ("%s: NFA_SnepPut failed, code: 0x%04x", fn, nfaStat);
            mJniHandleSendingNppViaSnep = 0;
            free (pClient->mSnepNdefBuf);
            pClient->mSnepNdefBuf = NULL;
            return (false);
        }

        SyncEventGuard guard (pClient->mSnepEvent);
        pClient->mSnepEvent.wait ();

        free (pClient->mSnepNdefBuf);
        pClient->mSnepNdefBuf = NULL;
        mJniHandleSendingNppViaSnep = 0;
        return (pClient->mIsSnepSentOk);
    }
    return (true);
}


/*******************************************************************************
**
** Function:        receive
**
** Description:     Receive data from peer.
**                  jniHandle: Handle of connection.
**                  buffer: Buffer to store data.
**                  bufferLen: Max length of buffer.
**                  actualLen: Actual length received.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool PeerToPeer::receive (tBRCM_JNI_HANDLE jniHandle, UINT8* buffer, UINT16 bufferLen, UINT16& actualLen)
{
    static const char fn [] = "PeerToPeer::receive";
    ALOGD_IF ((appl_trace_level>=BT_TRACE_LEVEL_DEBUG), "%s: enter; jniHandle: %u  bufferLen: %u", fn, jniHandle, bufferLen);
    NfaConn *pConn = NULL;
    tNFA_STATUS stat = NFA_STATUS_FAILED;
    UINT32 actualDataLen2 = 0;
    BOOLEAN isMoreData = TRUE;
    bool retVal = false;

    if (jniHandle == mRcvFakeNppJniHandle)
        return (feedNppFromSnep(buffer, bufferLen, actualLen));

    if ((pConn = findConnection (jniHandle)) == NULL)
    {
        ALOGE ("%s: can't find connection handle: %u", fn, jniHandle);
        return (false);
    }

    ALOGD_IF ((appl_trace_level>=BT_TRACE_LEVEL_DEBUG), "%s: jniHandle: %u  nfaHandle: 0x%04X  buf len=%u", fn, pConn->mJniHandle, pConn->mNfaConnHandle, bufferLen);

    while (pConn->mNfaConnHandle != NFA_HANDLE_INVALID)
    {
        stat = NFA_P2pReadData (pConn->mNfaConnHandle, bufferLen, &actualDataLen2, buffer, &isMoreData);
        if ((stat == NFA_STATUS_OK) && (actualDataLen2 > 0)) //received some data
        {
            actualLen = (UINT16) actualDataLen2;
            retVal = true;
            break;
        }
        ALOGD_IF ((appl_trace_level>=BT_TRACE_LEVEL_DEBUG), "%s: waiting for data...", fn);
        {
            SyncEventGuard guard (pConn->mReadEvent);
            pConn->mReadEvent.wait();
        }
    } //while

    ALOGD_IF ((appl_trace_level>=BT_TRACE_LEVEL_DEBUG), "%s: exit; nfa h: 0x%X  ok: %u  actual len: %u", fn, pConn->mNfaConnHandle, retVal, actualLen);
    return retVal;
}


/*******************************************************************************
**
** Function:        feedNppFromSnep
**
** Description:     Send incomming data to the NFC service's NDEF Push Protocol.
**                  buffer: Buffer of data to send.
**                  bufferLen: Length of data in buffer.
**                  actualLen: Actual length sent.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool PeerToPeer::feedNppFromSnep (UINT8* buffer, UINT16 bufferLen, UINT16& actualLen)
{
    static const char fn [] = "PeerToPeer::feedNppFromSnep";

    ALOGD_IF ((appl_trace_level>=BT_TRACE_LEVEL_DEBUG), "%s: mNppTotalLen: %lu  mNppReadSoFar: %lu  bufferLen: %u",
            fn, mNppTotalLen, mNppReadSoFar, bufferLen);

    if (bufferLen > (mNppTotalLen - mNppReadSoFar))
        bufferLen = mNppTotalLen - mNppReadSoFar;

    memcpy (buffer, mNppFakeOutBuffer + mNppReadSoFar, bufferLen);

    mNppReadSoFar += bufferLen;
    actualLen      = bufferLen;

    if (mNppReadSoFar == mNppTotalLen)
    {
        ALOGD ("%s: entire message consumed", fn);
        free (mNppFakeOutBuffer);
        mNppFakeOutBuffer   = NULL;
        mRcvFakeNppJniHandle = 0;
    }
    return (true);
}


/*******************************************************************************
**
** Function:        disconnectConnOriented
**
** Description:     Disconnect a connection-oriented connection with peer.
**                  jniHandle: Handle of connection.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool PeerToPeer::disconnectConnOriented (tBRCM_JNI_HANDLE jniHandle)
{
    static const char fn [] = "PeerToPeer::disconnectConnOriented";
    tNFA_STATUS nfaStat = NFA_STATUS_FAILED;
    P2pClient   *pClient = NULL;
    NfaConn     *pConn = NULL;

    ALOGD ("%s: enter; jni handle: %u", fn, jniHandle);

    if ((pConn = findConnection(jniHandle)) == NULL)
    {
        ALOGE ("%s: can't find connection handle: %u", fn, jniHandle);
        return (false);
    }

    // If this is a client, he may not be connected yet, so unblock him just in case
    if ( ((pClient = findClient(jniHandle)) != NULL) && (pClient->mIsConnecting) )
    {
        SyncEventGuard guard (pClient->mConnectingEvent);
        pClient->mConnectingEvent.notifyOne();
        return (true);
    }

    {
        SyncEventGuard guard1 (pConn->mCongEvent);
        pConn->mCongEvent.notifyOne (); //unblock send() if congested
    }
    {
        SyncEventGuard guard2 (pConn->mReadEvent);
        pConn->mReadEvent.notifyOne (); //unblock receive()
    }

    if (pConn->mNfaConnHandle != NFA_HANDLE_INVALID)
    {
        ALOGD ("%s: try disconn nfa h=0x%04X", fn, pConn->mNfaConnHandle);
        SyncEventGuard guard (pConn->mDisconnectingEvent);
        nfaStat = NFA_P2pDisconnect (pConn->mNfaConnHandle, FALSE);

        if (nfaStat != NFA_STATUS_OK)
            ALOGE ("%s: fail p2p disconnect", fn);
        else
            pConn->mDisconnectingEvent.wait();
    }

    mDisconnectMutex.lock ();
    removeConn (jniHandle);
    mDisconnectMutex.unlock ();

    ALOGD ("%s: exit; jni handle: %u", fn, jniHandle);
    return nfaStat == NFA_STATUS_OK;
}


/*******************************************************************************
**
** Function:        getRemoteMaxInfoUnit
**
** Description:     Get peer's max information unit.
**                  jniHandle: Handle of the connection.
**
** Returns:         Peer's max information unit.
**
*******************************************************************************/
UINT16 PeerToPeer::getRemoteMaxInfoUnit (tBRCM_JNI_HANDLE jniHandle)
{
    static const char fn [] = "PeerToPeer::getRemoteMaxInfoUnit";
    NfaConn *pConn = NULL;

    if ((pConn = findConnection(jniHandle)) == NULL)
    {
        ALOGE ("%s: can't find client  jniHandle: %u", fn, jniHandle);
        return 0;
    }
    ALOGD ("%s: jniHandle: %u   MIU: %u", fn, jniHandle, pConn->mRemoteMaxInfoUnit);
    return (pConn->mRemoteMaxInfoUnit);
}


/*******************************************************************************
**
** Function:        getRemoteRecvWindow
**
** Description:     Get peer's receive window size.
**                  jniHandle: Handle of the connection.
**
** Returns:         Peer's receive window size.
**
*******************************************************************************/
UINT8 PeerToPeer::getRemoteRecvWindow (tBRCM_JNI_HANDLE jniHandle)
{
    static const char fn [] = "PeerToPeer::getRemoteRecvWindow";
    ALOGD ("%s: client jni handle: %u", fn, jniHandle);
    NfaConn *pConn = NULL;

    if ((pConn = findConnection(jniHandle)) == NULL)
    {
        ALOGE ("%s: can't find client", fn);
        return 0;
    }
    return pConn->mRemoteRecvWindow;
}

/*******************************************************************************
**
** Function:        setP2pListenMask
**
** Description:     Sets the p2p listen technology mask.
**                  p2pListenMask: the p2p listen mask to be set?
**
** Returns:         None
**
*******************************************************************************/
void PeerToPeer::setP2pListenMask (tNFA_TECHNOLOGY_MASK p2pListenMask) {
    mP2pListenTechMask = p2pListenMask;
}

/*******************************************************************************
**
** Function:        enableP2pListening
**
** Description:     Start/stop polling/listening to peer that supports P2P.
**                  isEnable: Is enable polling/listening?
**
** Returns:         None
**
*******************************************************************************/
void PeerToPeer::enableP2pListening (bool isEnable)
{
    static const char    fn []   = "PeerToPeer::enableP2pListening";
    tNFA_STATUS          nfaStat = NFA_STATUS_FAILED;

    ALOGD ("%s: enter isEnable: %u  mIsP2pListening: %u", fn, isEnable, mIsP2pListening);

    // If request to enable P2P listening, and we were not already listening
    if ( (isEnable == true) && (mIsP2pListening == false) && (mP2pListenTechMask != 0) )
    {
        SyncEventGuard guard (mSetTechEvent);
        if ((nfaStat = NFA_SetP2pListenTech (mP2pListenTechMask)) == NFA_STATUS_OK)
        {
            mSetTechEvent.wait ();
            mIsP2pListening = true;
        }
        else
            ALOGE ("%s: fail enable listen; error=0x%X", fn, nfaStat);
    }
    else if ( (isEnable == false) && (mIsP2pListening == true) )
    {
        SyncEventGuard guard (mSetTechEvent);
        // Request to disable P2P listening, check if it was enabled
        if ((nfaStat = NFA_SetP2pListenTech(0)) == NFA_STATUS_OK)
        {
            mSetTechEvent.wait ();
            mIsP2pListening = false;
        }
        else
            ALOGE ("%s: fail disable listen; error=0x%X", fn, nfaStat);
    }
    ALOGD ("%s: exit; mIsP2pListening: %u", fn, mIsP2pListening);
}


/*******************************************************************************
**
** Function:        handleNfcOnOff
**
** Description:     Handle events related to turning NFC on/off by the user.
**                  isOn: Is NFC turning on?
**
** Returns:         None
**
*******************************************************************************/
void PeerToPeer::handleNfcOnOff (bool isOn)
{
    static const char fn [] = "PeerToPeer::handleNfcOnOff";
    ALOGD ("%s: enter; is on=%u", fn, isOn);
    tNFA_STATUS stat = NFA_STATUS_FAILED;

    mIsP2pListening = false;            // In both cases, P2P will not be listening

    if (isOn)
    {
        // Start with no clients or servers
        memset (mServers, 0, sizeof(mServers));
        memset (mClients, 0, sizeof(mClients));
    }
    else
    {
        int ii = 0, jj = 0;

        // Disconnect through all the clients
        for (ii = 0; ii < sMax; ii++)
        {
            if (mClients[ii] != NULL)
            {
                if (mClients[ii]->mClientConn.mNfaConnHandle == NFA_HANDLE_INVALID)
                {
                    SyncEventGuard guard (mClients[ii]->mConnectingEvent);
                    mClients[ii]->mConnectingEvent.notifyOne();
                }
                else
                {
                    mClients[ii]->mClientConn.mNfaConnHandle = NFA_HANDLE_INVALID;
                    {
                        SyncEventGuard guard1 (mClients[ii]->mClientConn.mCongEvent);
                        mClients[ii]->mClientConn.mCongEvent.notifyOne (); //unblock send()
                    }
                    {
                        SyncEventGuard guard2 (mClients[ii]->mClientConn.mReadEvent);
                        mClients[ii]->mClientConn.mReadEvent.notifyOne (); //unblock receive()
                    }
                }
            }
        } //loop

        // Now look through all the server control blocks
        for (ii = 0; ii < sMax; ii++)
        {
            if (mServers[ii] != NULL)
            {
                for (jj = 0; jj < MAX_NFA_CONNS_PER_SERVER; jj++)
                {
                    if (mServers[ii]->mServerConn[jj] != NULL)
                    {
                        mServers[ii]->mServerConn[jj]->mNfaConnHandle = NFA_HANDLE_INVALID;
                        {
                            SyncEventGuard guard1 (mServers[ii]->mServerConn[jj]->mCongEvent);
                            mServers[ii]->mServerConn[jj]->mCongEvent.notifyOne (); //unblock write (if congested)
                        }
                        {
                            SyncEventGuard guard2 (mServers[ii]->mServerConn[jj]->mReadEvent);
                            mServers[ii]->mServerConn[jj]->mReadEvent.notifyOne (); //unblock receive()
                        }
                    }
                }
            }
        } //loop

        mJniHandleSendingNppViaSnep = 0;
        mRcvFakeNppJniHandle        = 0;
        mSnepRegHandle              = NFA_HANDLE_INVALID;

        if (mNppFakeOutBuffer != NULL)
        {
            free (mNppFakeOutBuffer);
            mNppFakeOutBuffer = NULL;
        }
    }
    ALOGD ("%s: exit", fn);
}


/*******************************************************************************
**
** Function:        nfaServerCallback
**
** Description:     Receive LLCP-related events from the stack.
**                  p2pEvent: Event code.
**                  eventData: Event data.
**
** Returns:         None
**
*******************************************************************************/
void PeerToPeer::nfaServerCallback (tNFA_P2P_EVT p2pEvent, tNFA_P2P_EVT_DATA* eventData)
{
    static const char fn [] = "PeerToPeer::nfaServerCallback";
    P2pServer   *pSrv = NULL;
    NfaConn     *pConn = NULL;

    ALOGD_IF ((appl_trace_level>=BT_TRACE_LEVEL_DEBUG), "%s: enter; event=0x%X", fn, p2pEvent);

    switch (p2pEvent)
    {
    case NFA_P2P_REG_SERVER_EVT:  // NFA_P2pRegisterServer() has started to listen
        ALOGD ("%s: NFA_P2P_REG_SERVER_EVT; handle: 0x%04x; service sap=0x%02x  name: %s", fn,
              eventData->reg_server.server_handle, eventData->reg_server.server_sap, eventData->reg_server.service_name);

        if ((pSrv = sP2p.findServer(eventData->reg_server.service_name)) == NULL)
        {
            ALOGE ("%s: NFA_P2P_REG_SERVER_EVT for unknown service: %s", fn, eventData->reg_server.service_name);
        }
        else
        {
            SyncEventGuard guard (pSrv->mRegServerEvent);
            pSrv->mNfaP2pServerHandle = eventData->reg_server.server_handle;
            pSrv->mRegServerEvent.notifyOne(); //unblock registerServer()
        }
        break;

    case NFA_P2P_ACTIVATED_EVT: //remote device has activated
        ALOGD ("%s: NFA_P2P_ACTIVATED_EVT; handle: 0x%04x", fn, eventData->activated.handle);
        break;

    case NFA_P2P_DEACTIVATED_EVT:
        ALOGD ("%s: NFA_P2P_DEACTIVATED_EVT; handle: 0x%04x", fn, eventData->activated.handle);
        break;

    case NFA_P2P_CONN_REQ_EVT:
        ALOGD ("%s: NFA_P2P_CONN_REQ_EVT; nfa server h=0x%04x; nfa conn h=0x%04x; remote sap=0x%02x", fn,
                eventData->conn_req.server_handle, eventData->conn_req.conn_handle, eventData->conn_req.remote_sap);

        if ((pSrv = sP2p.findServer(eventData->conn_req.server_handle)) == NULL)
        {
            ALOGE ("%s: NFA_P2P_CONN_REQ_EVT; unknown server h", fn);
            return;
        }
        ALOGD ("%s: NFA_P2P_CONN_REQ_EVT; server jni h=%u", fn, pSrv->mJniHandle);

        // Look for a connection block that is waiting (handle invalid)
        if ((pConn = pSrv->findServerConnection(NFA_HANDLE_INVALID)) == NULL)
        {
            ALOGE ("%s: NFA_P2P_CONN_REQ_EVT; server not listening", fn);
        }
        else
        {
            SyncEventGuard guard (pSrv->mConnRequestEvent);
            pConn->mNfaConnHandle = eventData->conn_req.conn_handle;
            pConn->mRemoteMaxInfoUnit = eventData->conn_req.remote_miu;
            pConn->mRemoteRecvWindow = eventData->conn_req.remote_rw;
            ALOGD ("%s: NFA_P2P_CONN_REQ_EVT; server jni h=%u; conn jni h=%u; notify conn req", fn, pSrv->mJniHandle, pConn->mJniHandle);
            pSrv->mConnRequestEvent.notifyOne(); //unblock accept()
        }
        break;

    case NFA_P2P_CONNECTED_EVT:
        ALOGD ("%s: NFA_P2P_CONNECTED_EVT; h=0x%x  remote sap=0x%X", fn,
                eventData->connected.client_handle, eventData->connected.remote_sap);
        break;

    case NFA_P2P_DISC_EVT:
        ALOGD ("%s: NFA_P2P_DISC_EVT; h=0x%04x; reason=0x%X", fn, eventData->disc.handle, eventData->disc.reason);
        // Look for the connection block
        if ((pConn = sP2p.findConnection(eventData->disc.handle)) == NULL)
        {
            ALOGE ("%s: NFA_P2P_DISC_EVT: can't find conn for NFA handle: 0x%04x", fn, eventData->disc.handle);
        }
        else
        {
            sP2p.mDisconnectMutex.lock ();
            pConn->mNfaConnHandle = NFA_HANDLE_INVALID;
            {
                ALOGD ("%s: NFA_P2P_DISC_EVT; try guard disconn event", fn);
                SyncEventGuard guard3 (pConn->mDisconnectingEvent);
                pConn->mDisconnectingEvent.notifyOne ();
                ALOGD ("%s: NFA_P2P_DISC_EVT; notified disconn event", fn);
            }
            {
                ALOGD ("%s: NFA_P2P_DISC_EVT; try guard congest event", fn);
                SyncEventGuard guard1 (pConn->mCongEvent);
                pConn->mCongEvent.notifyOne (); //unblock write (if congested)
                ALOGD ("%s: NFA_P2P_DISC_EVT; notified congest event", fn);
            }
            {
                ALOGD ("%s: NFA_P2P_DISC_EVT; try guard read event", fn);
                SyncEventGuard guard2 (pConn->mReadEvent);
                pConn->mReadEvent.notifyOne (); //unblock receive()
                ALOGD ("%s: NFA_P2P_DISC_EVT; notified read event", fn);
            }
            sP2p.mDisconnectMutex.unlock ();
        }
        break;

    case NFA_P2P_DATA_EVT:
        // Look for the connection block
        if ((pConn = sP2p.findConnection(eventData->data.handle)) == NULL)
        {
            ALOGE ("%s: NFA_P2P_DATA_EVT: can't find conn for NFA handle: 0x%04x", fn, eventData->data.handle);
        }
        else
        {
            ALOGD_IF ((appl_trace_level>=BT_TRACE_LEVEL_DEBUG), "%s: NFA_P2P_DATA_EVT; h=0x%X; remote sap=0x%X", fn,
                    eventData->data.handle, eventData->data.remote_sap);
            SyncEventGuard guard (pConn->mReadEvent);
            pConn->mReadEvent.notifyOne();
        }
        break;

    case NFA_P2P_CONGEST_EVT:
        // Look for the connection block
        if ((pConn = sP2p.findConnection(eventData->congest.handle)) == NULL)
        {
            ALOGE ("%s: NFA_P2P_CONGEST_EVT: can't find conn for NFA handle: 0x%04x", fn, eventData->congest.handle);
        }
        else
        {
            ALOGD ("%s: NFA_P2P_CONGEST_EVT; nfa handle: 0x%04x  congested: %u", fn,
                    eventData->congest.handle, eventData->congest.is_congested);

            SyncEventGuard guard (pConn->mCongEvent);
            pConn->mCongEvent.notifyOne();
        }
        break;

    default:
        ALOGE ("%s: unknown event 0x%X ????", fn, p2pEvent);
        break;
    }
    ALOGD_IF ((appl_trace_level>=BT_TRACE_LEVEL_DEBUG), "%s: exit", fn);
}


/*******************************************************************************
**
** Function:        nfaClientCallback
**
** Description:     Receive LLCP-related events from the stack.
**                  p2pEvent: Event code.
**                  eventData: Event data.
**
** Returns:         None
**
*******************************************************************************/
void PeerToPeer::nfaClientCallback (tNFA_P2P_EVT p2pEvent, tNFA_P2P_EVT_DATA* eventData)
{
    static const char fn [] = "PeerToPeer::nfaClientCallback";
    NfaConn     *pConn = NULL;
    P2pClient   *pClient = NULL;

    ALOGD_IF ((appl_trace_level>=BT_TRACE_LEVEL_DEBUG), "%s: enter; event=%u", fn, p2pEvent);

    switch (p2pEvent)
    {
    case NFA_P2P_REG_CLIENT_EVT:
        // Look for a client that is trying to register
        if ((pClient = sP2p.findClient ((tNFA_HANDLE)NFA_HANDLE_INVALID)) == NULL)
        {
            ALOGE ("%s: NFA_P2P_REG_CLIENT_EVT: can't find waiting client", fn);
        }
        else
        {
            ALOGD ("%s: NFA_P2P_REG_CLIENT_EVT; Conn Handle: 0x%04x, pClient: 0x%p", fn, eventData->reg_client.client_handle, pClient);

            SyncEventGuard guard (pClient->mRegisteringEvent);
            pClient->mNfaP2pClientHandle = eventData->reg_client.client_handle;
            pClient->mRegisteringEvent.notifyOne();
        }
        break;

    case NFA_P2P_ACTIVATED_EVT:
        // Look for a client that is trying to register
        if ((pClient = sP2p.findClient (eventData->activated.handle)) == NULL)
        {
            ALOGE ("%s: NFA_P2P_ACTIVATED_EVT: can't find client", fn);
        }
        else
        {
            ALOGD ("%s: NFA_P2P_ACTIVATED_EVT; Conn Handle: 0x%04x, pClient: 0x%p", fn, eventData->activated.handle, pClient);
        }
        break;

    case NFA_P2P_DEACTIVATED_EVT:
        ALOGD ("%s: NFA_P2P_DEACTIVATED_EVT: conn handle: 0x%X", fn, eventData->deactivated.handle);
        break;

    case NFA_P2P_CONNECTED_EVT:
        // Look for the client that is trying to connect
        if ((pClient = sP2p.findClient (eventData->connected.client_handle)) == NULL)
        {
            ALOGE ("%s: NFA_P2P_CONNECTED_EVT: can't find client: 0x%04x", fn, eventData->connected.client_handle);
        }
        else
        {
            ALOGD ("%s: NFA_P2P_CONNECTED_EVT; client_handle=0x%04x  conn_handle: 0x%04x  remote sap=0x%X  pClient: 0x%p", fn,
                    eventData->connected.client_handle, eventData->connected.conn_handle, eventData->connected.remote_sap, pClient);

            SyncEventGuard guard (pClient->mConnectingEvent);
            pClient->mClientConn.mNfaConnHandle     = eventData->connected.conn_handle;
            pClient->mClientConn.mRemoteMaxInfoUnit = eventData->connected.remote_miu;
            pClient->mClientConn.mRemoteRecvWindow  = eventData->connected.remote_rw;
            pClient->mConnectingEvent.notifyOne(); //unblock createDataLinkConn()
        }
        break;

    case NFA_P2P_DISC_EVT:
        ALOGD ("%s: NFA_P2P_DISC_EVT; h=0x%04x; reason=0x%X", fn, eventData->disc.handle, eventData->disc.reason);
        // Look for the connection block
        if ((pConn = sP2p.findConnection(eventData->disc.handle)) == NULL)
        {
            // If no connection, may be a client that is trying to connect
            if ((pClient = sP2p.findClientCon ((tNFA_HANDLE)NFA_HANDLE_INVALID)) == NULL)
            {
                ALOGE ("%s: NFA_P2P_DISC_EVT: can't find conn for NFA handle: 0x%04x", fn, eventData->disc.handle);
                return;
            }
            // Unblock createDataLinkConn()
            SyncEventGuard guard (pClient->mConnectingEvent);
            pClient->mConnectingEvent.notifyOne();
        }
        else
        {
            sP2p.mDisconnectMutex.lock ();
            pConn->mNfaConnHandle = NFA_HANDLE_INVALID;
            {
                ALOGD ("%s: NFA_P2P_DISC_EVT; try guard disconn event", fn);
                SyncEventGuard guard3 (pConn->mDisconnectingEvent);
                pConn->mDisconnectingEvent.notifyOne ();
                ALOGD ("%s: NFA_P2P_DISC_EVT; notified disconn event", fn);
            }
            {
                ALOGD ("%s: NFA_P2P_DISC_EVT; try guard congest event", fn);
                SyncEventGuard guard1 (pConn->mCongEvent);
                pConn->mCongEvent.notifyOne(); //unblock write (if congested)
                ALOGD ("%s: NFA_P2P_DISC_EVT; notified congest event", fn);
            }
            {
                ALOGD ("%s: NFA_P2P_DISC_EVT; try guard read event", fn);
                SyncEventGuard guard2 (pConn->mReadEvent);
                pConn->mReadEvent.notifyOne(); //unblock receive()
                ALOGD ("%s: NFA_P2P_DISC_EVT; notified read event", fn);
            }
            sP2p.mDisconnectMutex.unlock ();
        }
        break;

    case NFA_P2P_DATA_EVT:
        // Look for the connection block
        if ((pConn = sP2p.findConnection(eventData->data.handle)) == NULL)
        {
            ALOGE ("%s: NFA_P2P_DATA_EVT: can't find conn for NFA handle: 0x%04x", fn, eventData->data.handle);
        }
        else
        {
            ALOGD_IF ((appl_trace_level>=BT_TRACE_LEVEL_DEBUG), "%s: NFA_P2P_DATA_EVT; h=0x%X; remote sap=0x%X", fn,
                    eventData->data.handle, eventData->data.remote_sap);
            SyncEventGuard guard (pConn->mReadEvent);
            pConn->mReadEvent.notifyOne();
        }
        break;

    case NFA_P2P_CONGEST_EVT:
        // Look for the connection block
        if ((pConn = sP2p.findConnection(eventData->congest.handle)) == NULL)
        {
            ALOGE ("%s: NFA_P2P_CONGEST_EVT: can't find conn for NFA handle: 0x%04x", fn, eventData->congest.handle);
        }
        else
        {
            ALOGD_IF ((appl_trace_level>=BT_TRACE_LEVEL_DEBUG), "%s: NFA_P2P_CONGEST_EVT; nfa handle: 0x%04x  congested: %u", fn,
                    eventData->congest.handle, eventData->congest.is_congested);

            SyncEventGuard guard (pConn->mCongEvent);
            pConn->mCongEvent.notifyOne();
        }
        break;

    default:
        ALOGE ("%s: unknown event 0x%X ????", fn, p2pEvent);
        break;
    }
}


/*******************************************************************************
**
** Function:        snepClientCallback
**
** Description:     Receive SNEP-related events from the stack.
**                  snepEvent: Event code.
**                  eventData: Event data.
**
** Returns:         None
**
*******************************************************************************/
void PeerToPeer::snepClientCallback (tNFA_SNEP_EVT snepEvent, tNFA_SNEP_EVT_DATA *eventData)
{
    static const char fn [] = "PeerToPeer::snepClientCallback";
    P2pClient   *pClient;

    switch (snepEvent)
    {
    case NFA_SNEP_REG_EVT:
        {
            ALOGD ("%s  NFA_SNEP_REG_EVT  Status: %u  Handle: 0x%X", fn, eventData->reg.status, eventData->reg.reg_handle);
            SyncEventGuard guard (sP2p.mSnepRegisterEvent);
            if (eventData->reg.status == NFA_STATUS_OK)
                sP2p.mSnepRegHandle = eventData->reg.reg_handle;
            sP2p.mSnepRegisterEvent.notifyOne ();
            break;
        }

    case NFA_SNEP_ACTIVATED_EVT:
        ALOGD ("%s  NFA_SNEP_ACTIVATED_EVT  mJniHandleSendingNppViaSnep: %u", fn, sP2p.mJniHandleSendingNppViaSnep);
        break;

    case NFA_SNEP_DEACTIVATED_EVT:
        ALOGD ("%s  NFA_SNEP_ACTIVATED_EVT  mJniHandleSendingNppViaSnep: %u", fn, sP2p.mJniHandleSendingNppViaSnep);
        break;

    case NFA_SNEP_CONNECTED_EVT:
        if ((pClient = sP2p.findClient (sP2p.mJniHandleSendingNppViaSnep)) == NULL)
        {
            ALOGE ("%s: NFA_SNEP_CONNECTED_EVT - can't find SNEP client, mJniHandleSendingNppViaSnep: %u", fn, sP2p.mJniHandleSendingNppViaSnep);
        }
        else
        {
            ALOGD ("%s  NFA_SNEP_CONNECTED_EVT  mJniHandleSendingNppViaSnep: %u  ConnHandle: 0x%04x", fn, sP2p.mJniHandleSendingNppViaSnep, eventData->connect.conn_handle);

            pClient->mSnepConnHandle = eventData->connect.conn_handle;
            SyncEventGuard guard (pClient->mSnepEvent);
            pClient->mSnepEvent.notifyOne();
        }
        break;

    case NFA_SNEP_PUT_RESP_EVT:
        if ((pClient = sP2p.findClient (sP2p.mJniHandleSendingNppViaSnep)) == NULL)
        {
            ALOGE ("%s: NFA_SNEP_PUT_RESP_EVT - can't find SNEP client, mJniHandleSendingNppViaSnep: %u", fn, sP2p.mJniHandleSendingNppViaSnep);
        }
        else
        {
            ALOGD ("%s  NFA_SNEP_PUT_RESP_EVT  mJniHandleSendingNppViaSnep: %u  Result: 0x%X", fn, sP2p.mJniHandleSendingNppViaSnep, eventData->put_resp.resp_code);

            pClient->mIsSnepSentOk = (eventData->put_resp.resp_code == NFA_SNEP_RESP_CODE_SUCCESS);

            NFA_SnepDisconnect (eventData->put_resp.conn_handle, FALSE);

            SyncEventGuard guard (pClient->mSnepEvent);
            pClient->mSnepEvent.notifyOne();
        }
        break;

    case NFA_SNEP_DISC_EVT:
        if ((pClient = sP2p.findClient (sP2p.mJniHandleSendingNppViaSnep)) == NULL)
        {
            ALOGE ("%s: NFA_SNEP_DISC_EVT - can't find SNEP client, mJniHandleSendingNppViaSnep: %u", fn, sP2p.mJniHandleSendingNppViaSnep);
        }
        else
        {
            ALOGD ("%s  NFA_SNEP_DISC_EVT  mJniHandleSendingNppViaSnep: %u", fn, sP2p.mJniHandleSendingNppViaSnep);
            pClient->mSnepConnHandle = NFA_HANDLE_INVALID;

            SyncEventGuard guard (pClient->mSnepEvent);
            pClient->mSnepEvent.notifyOne();
        }
        break;

    case NFA_SNEP_DEFAULT_SERVER_STARTED_EVT:
        {
            ALOGE ("%s: NFA_SNEP_DEFAULT_SERVER_STARTED_EVT", fn);
            SyncEventGuard guard (sP2p.mSnepDefaultServerStartStopEvent);
            sP2p.mSnepDefaultServerStartStopEvent.notifyOne(); //unblock NFA_SnepStartDefaultServer()
            break;
        }

    case NFA_SNEP_DEFAULT_SERVER_STOPPED_EVT:
        {
            ALOGE ("%s: NFA_SNEP_DEFAULT_SERVER_STOPPED_EVT", fn);
            SyncEventGuard guard (sP2p.mSnepDefaultServerStartStopEvent);
            sP2p.mSnepDefaultServerStartStopEvent.notifyOne(); //unblock NFA_SnepStopDefaultServer()
            break;
        }
        break;

    default:
        ALOGE ("%s UNKNOWN EVENT: 0x%04x  mJniHandleSendingNppViaSnep: %u", fn, snepEvent, sP2p.mJniHandleSendingNppViaSnep);
        break;
    }
}


/*******************************************************************************
**
** Function:        ndefTypeCallback
**
** Description:     Receive NDEF-related events from the stack.
**                  ndefEvent: Event code.
**                  eventData: Event data.
**
** Returns:         None
**
*******************************************************************************/
void PeerToPeer::ndefTypeCallback (tNFA_NDEF_EVT ndefEvent, tNFA_NDEF_EVT_DATA *eventData)
{
    static const char fn [] = "PeerToPeer::ndefTypeCallback";
    P2pServer *pSvr = NULL;

    if (ndefEvent == NFA_NDEF_REGISTER_EVT)
    {
        tNFA_NDEF_REGISTER& ndef_reg = eventData->ndef_reg;
        ALOGD ("%s  NFA_NDEF_REGISTER_EVT  Status: %u; h=0x%X", fn, ndef_reg.status, ndef_reg.ndef_type_handle);
        sP2p.mNdefTypeHandlerHandle = ndef_reg.ndef_type_handle;
    }
    else if (ndefEvent == NFA_NDEF_DATA_EVT)
    {
        ALOGD ("%s  NFA_NDEF_DATA_EVT  Len: %lu", fn, eventData->ndef_data.len);

        if (sP2p.mRcvFakeNppJniHandle != 0)
        {
            ALOGE ("%s  Got NDEF Data while busy, mRcvFakeNppJniHandle: %u", fn, sP2p.mRcvFakeNppJniHandle);
            return;
        }

        if ((pSvr = sP2p.findServer ("com.android.npp")) == NULL)
        {
            ALOGE ("%s  Got NDEF Data but no NPP server listening", fn);
            return;
        }

        if ((sP2p.mNppFakeOutBuffer = (UINT8 *)malloc(eventData->ndef_data.len + 10)) == NULL)
        {
            ALOGE ("%s  failed to malloc: %lu bytes", fn, eventData->ndef_data.len + 10);
            return;
        }

        sP2p.mNppFakeOutBuffer[0] = 0x01;
        sP2p.mNppFakeOutBuffer[1] = 0x00;
        sP2p.mNppFakeOutBuffer[2] = 0x00;
        sP2p.mNppFakeOutBuffer[3] = 0x00;
        sP2p.mNppFakeOutBuffer[4] = 0x01;
        sP2p.mNppFakeOutBuffer[5] = 0x01;
        sP2p.mNppFakeOutBuffer[6] = (UINT8)(eventData->ndef_data.len >> 24);
        sP2p.mNppFakeOutBuffer[7] = (UINT8)(eventData->ndef_data.len >> 16);
        sP2p.mNppFakeOutBuffer[8] = (UINT8)(eventData->ndef_data.len >> 8);
        sP2p.mNppFakeOutBuffer[9] = (UINT8)(eventData->ndef_data.len);

        memcpy (&sP2p.mNppFakeOutBuffer[10], eventData->ndef_data.p_data, eventData->ndef_data.len);

        ALOGD ("%s  NFA_NDEF_DATA_EVT  Faking NPP on Server Handle: %u", fn, pSvr->mJniHandle);

        sP2p.mRcvFakeNppJniHandle = pSvr->mJniHandle;
        sP2p.mNppTotalLen         = eventData->ndef_data.len + 10;
        sP2p.mNppReadSoFar        = 0;
        {
            SyncEventGuard guard (pSvr->mConnRequestEvent);
            pSvr->mConnRequestEvent.notifyOne();
        }
    }
    else
    {
        ALOGE ("%s UNKNOWN EVENT: 0x%X", fn, ndefEvent);
    }

}


/*******************************************************************************
**
** Function:        connectionEventHandler
**
** Description:     Receive events from the stack.
**                  event: Event code.
**                  eventData: Event data.
**
** Returns:         None
**
*******************************************************************************/
void PeerToPeer::connectionEventHandler (UINT8 event, tNFA_CONN_EVT_DATA* eventData)
{
    switch (event)
    {
    case NFA_SET_P2P_LISTEN_TECH_EVT:
        {
            SyncEventGuard guard (mSetTechEvent);
            mSetTechEvent.notifyOne(); //unblock NFA_SetP2pListenTech()
            break;
        }
    }
}


/*******************************************************************************
**
** Function:        getNextJniHandle
**
** Description:     Get a new JNI handle.
**
** Returns:         A new JNI handle.
**
*******************************************************************************/
tBRCM_JNI_HANDLE PeerToPeer::getNewJniHandle ()
{
    tBRCM_JNI_HANDLE newHandle = 0;

    mNewJniHandleMutex.lock ();
    newHandle = mNextJniHandle++;
    mNewJniHandleMutex.unlock ();
    return newHandle;
}


/////////////////////////////////////////////////////////////////////////
/////////////////////////////////////////////////////////////////////////


/*******************************************************************************
**
** Function:        P2pServer
**
** Description:     Initialize member variables.
**
** Returns:         None
**
*******************************************************************************/
P2pServer::P2pServer()
:   mNfaP2pServerHandle (NFA_HANDLE_INVALID),
    mJniHandle (0)
{
    memset (mServerConn, 0, sizeof(mServerConn));
}


/*******************************************************************************
**
** Function:        findServerConnection
**
** Description:     Find a P2pServer that has the handle.
**                  nfaConnHandle: NFA connection handle.
**
** Returns:         P2pServer object.
**
*******************************************************************************/
NfaConn *P2pServer::findServerConnection (tNFA_HANDLE nfaConnHandle)
{
    int jj = 0;

    for (jj = 0; jj < MAX_NFA_CONNS_PER_SERVER; jj++)
    {
        if ( (mServerConn[jj] != NULL) && (mServerConn[jj]->mNfaConnHandle == nfaConnHandle) )
            return (mServerConn[jj]);
    }

    // If here, not found
    return (NULL);
}


/////////////////////////////////////////////////////////////////////////
/////////////////////////////////////////////////////////////////////////


/*******************************************************************************
**
** Function:        P2pClient
**
** Description:     Initialize member variables.
**
** Returns:         None
**
*******************************************************************************/
P2pClient::P2pClient ()
:   mNfaP2pClientHandle (NFA_HANDLE_INVALID),
    mIsConnecting (false),
    mSnepConnHandle (NFA_HANDLE_INVALID),
    mSnepNdefMsgLen (0),
    mSnepNdefBufLen (0),
    mSnepNdefBuf (NULL),
    mIsSnepSentOk (false)
{
}


/*******************************************************************************
**
** Function:        ~P2pClient
**
** Description:     Free all resources.
**
** Returns:         None
**
*******************************************************************************/
P2pClient::~P2pClient ()
{
    if (mSnepNdefBuf)
        free (mSnepNdefBuf);
}


/////////////////////////////////////////////////////////////////////////
/////////////////////////////////////////////////////////////////////////


/*******************************************************************************
**
** Function:        NfaConn
**
** Description:     Initialize member variables.
**
** Returns:         None
**
*******************************************************************************/
NfaConn::NfaConn()
:   mNfaConnHandle (NFA_HANDLE_INVALID),
    mJniHandle (0),
    mMaxInfoUnit (0),
    mRecvWindow (0),
    mRemoteMaxInfoUnit (0),
    mRemoteRecvWindow (0)
{
}

