/*****************************************************************************
**
**  Name:           PeerToPeer.h
**
**  Description:    Communicate with a peer using NFC-DEP, LLCP, SNEP.
**
**  Copyright (c) 2012, Broadcom Corp., All Rights Reserved.
**  Proprietary and confidential.
**
*****************************************************************************/
#pragma once
#include "SyncEvent.h"
#include "NfcJniUtil.h"
#include <string>
extern "C"
{
    #include "nfa_p2p_api.h"
    #include "nfa_snep_api.h"
}

class P2pServer;
class P2pClient;
class NfaConn;
#define MAX_NFA_CONNS_PER_SERVER    5


/*****************************************************************************
**
**  Name:           PeerToPeer
**
**  Description:    Communicate with a peer using NFC-DEP, LLCP, SNEP.
**
*****************************************************************************/
class PeerToPeer
{
public:
    typedef unsigned int tJNI_HANDLE;


    /*******************************************************************************
    **
    ** Function:        PeerToPeer
    **
    ** Description:     Initialize member variables.
    **
    ** Returns:         None
    **
    *******************************************************************************/
    PeerToPeer ();


    /*******************************************************************************
    **
    ** Function:        ~PeerToPeer
    **
    ** Description:     Free all resources.
    **
    ** Returns:         None
    **
    *******************************************************************************/
    ~PeerToPeer ();


    /*******************************************************************************
    **
    ** Function:        getInstance
    **
    ** Description:     Get the singleton PeerToPeer object.
    **
    ** Returns:         Singleton PeerToPeer object.
    **
    *******************************************************************************/
    static PeerToPeer& getInstance();


    /*******************************************************************************
    **
    ** Function:        initialize
    **
    ** Description:     Initialize member variables.
    **
    ** Returns:         None
    **
    *******************************************************************************/
    void initialize ();


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
    void llcpActivatedHandler (nfc_jni_native_data* nativeData, tNFA_LLCP_ACTIVATED& activated);


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
    void llcpDeactivatedHandler (nfc_jni_native_data* nativeData, tNFA_LLCP_DEACTIVATED& deactivated);


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
    void connectionEventHandler (UINT8 event, tNFA_CONN_EVT_DATA* eventData);


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
    bool registerServer (tJNI_HANDLE jniHandle, const char* serviceName);


    /*******************************************************************************
    **
    ** Function:        deregisterServer
    **
    ** Description:     Stop a P2pServer from listening for peer.
    **
    ** Returns:         True if ok.
    **
    *******************************************************************************/
    bool deregisterServer (tJNI_HANDLE jniHandle);


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
    bool accept (tJNI_HANDLE serverJniHandle, tJNI_HANDLE connJniHandle, int maxInfoUnit, int recvWindow);


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
    bool createClient (tJNI_HANDLE jniHandle, UINT16 miu, UINT8 rw);


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
    bool connectConnOriented (tJNI_HANDLE jniHandle, const char* serviceName);


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
    bool connectConnOriented (tJNI_HANDLE jniHandle, UINT8 destinationSap);


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
    bool send (tJNI_HANDLE jniHandle, UINT8* buffer, UINT16 bufferLen);


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
    bool receive (tJNI_HANDLE jniHandle, UINT8* buffer, UINT16 bufferLen, UINT16& actualLen);


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
    bool disconnectConnOriented (tJNI_HANDLE jniHandle);


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
    UINT16 getRemoteMaxInfoUnit (tJNI_HANDLE jniHandle);


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
    UINT8 getRemoteRecvWindow (tJNI_HANDLE jniHandle);


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
    void setP2pListenMask (tNFA_TECHNOLOGY_MASK p2pListenMask);

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
    void enableP2pListening (bool isEnable);


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
    void handleNfcOnOff (bool isOn);


    /*******************************************************************************
    **
    ** Function:        getNextJniHandle
    **
    ** Description:     Get a new JNI handle.
    **
    ** Returns:         A new JNI handle.
    **
    *******************************************************************************/
    tJNI_HANDLE getNewJniHandle ();


private:
    static const int sMax = 10;
    static PeerToPeer sP2p;
    static const std::string sSnepServiceName;
    static const std::string sNppServiceName;
    UINT16          mRemoteWKS;                 // Peer's well known services
    bool            mIsP2pListening;            // If P2P listening is enabled or not
    tNFA_TECHNOLOGY_MASK    mP2pListenTechMask; // P2P Listen mask
    tJNI_HANDLE     mJniHandleSendingNppViaSnep;
    tNFA_HANDLE     mSnepRegHandle;
    tJNI_HANDLE     mRcvFakeNppJniHandle;
    UINT8           *mNppFakeOutBuffer;
    UINT32          mNppTotalLen;
    UINT32          mNppReadSoFar;
    tNFA_HANDLE     mNdefTypeHandlerHandle;
    tJNI_HANDLE     mNextJniHandle;

    P2pServer       *mServers [sMax];
    P2pClient       *mClients [sMax];
    SyncEvent       mSetTechEvent;              // completion event for NFA_SetP2pListenTech()
    SyncEvent       mSnepDefaultServerStartStopEvent; // completion event for NFA_SnepStartDefaultServer(), NFA_SnepStopDefaultServer()
    SyncEvent       mSnepRegisterEvent;         // completion event for NFA_SnepRegisterClient()
    Mutex           mDisconnectMutex;           // synchronize the disconnect operation
    Mutex           mNewJniHandleMutex;         // synchronize the creation of a new JNI handle


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
    static void nfaServerCallback  (tNFA_P2P_EVT p2pEvent, tNFA_P2P_EVT_DATA *eventData);


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
    static void nfaClientCallback  (tNFA_P2P_EVT p2pEvent, tNFA_P2P_EVT_DATA *eventData);


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
    static void snepClientCallback (tNFA_SNEP_EVT snepEvent, tNFA_SNEP_EVT_DATA *eventData);


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
    static void ndefTypeCallback   (tNFA_NDEF_EVT event, tNFA_NDEF_EVT_DATA *evetnData);


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
    P2pServer   *findServer (tNFA_HANDLE nfaP2pServerHandle);


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
    P2pServer   *findServer (tJNI_HANDLE jniHandle);


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
    P2pServer   *findServer (const char *serviceName);


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
    void        removeServer (tJNI_HANDLE jniHandle);


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
    void        removeConn (tJNI_HANDLE jniHandle);


    /*******************************************************************************
    **
    ** Function:        createDataLinkConn
    **
    ** Description:     Establish a connection-oriented connection to a peer.
    **                  jniHandle: Connection handle.
    **                  serviceName: Peer's service name.
    **                  destinationSap: Peer's service access point.
    **
    ** Returns:         True if ok.
    **
    *******************************************************************************/
    bool        createDataLinkConn (tJNI_HANDLE jniHandle, const char* serviceName, UINT8 destinationSap);


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
    P2pClient   *findClient (tNFA_HANDLE nfaConnHandle);


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
    P2pClient   *findClient (tJNI_HANDLE jniHandle);


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
    P2pClient   *findClientCon (tNFA_HANDLE nfaConnHandle);


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
    NfaConn     *findConnection (tNFA_HANDLE nfaConnHandle);


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
    NfaConn     *findConnection (tJNI_HANDLE jniHandle);


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
    bool        sendViaSnep (tJNI_HANDLE jniHandle, UINT8 *buffer, UINT16 bufferLen);


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
    bool        feedNppFromSnep (UINT8* buffer, UINT16 bufferLen, UINT16& actualLen);
};


/*****************************************************************************
**
**  Name:           NfaConn
**
**  Description:    Store information about a connection related to a peer.
**
*****************************************************************************/
class NfaConn
{
public:
    tNFA_HANDLE         mNfaConnHandle;         // NFA handle of the P2P connection
    PeerToPeer::tJNI_HANDLE mJniHandle;             // JNI handle of the P2P connection
    UINT16              mMaxInfoUnit;
    UINT8               mRecvWindow;
    UINT16              mRemoteMaxInfoUnit;
    UINT8               mRemoteRecvWindow;
    SyncEvent           mReadEvent;             // event for reading
    SyncEvent           mCongEvent;             // event for congestion
    SyncEvent           mDisconnectingEvent;     // event for disconnecting


    /*******************************************************************************
    **
    ** Function:        NfaConn
    **
    ** Description:     Initialize member variables.
    **
    ** Returns:         None
    **
    *******************************************************************************/
    NfaConn();
};


/*****************************************************************************
**
**  Name:           P2pServer
**
**  Description:    Store information about an in-bound connection from a peer.
**
*****************************************************************************/
class P2pServer
{
public:
    tNFA_HANDLE     mNfaP2pServerHandle;    // NFA p2p handle of local server
    PeerToPeer::tJNI_HANDLE mJniHandle;            // JNI Handle
    SyncEvent       mRegServerEvent;        // for NFA_P2pRegisterServer()
    SyncEvent       mConnRequestEvent;      // for accept()
    std::string     mServiceName;
    NfaConn         *mServerConn[MAX_NFA_CONNS_PER_SERVER];

    /*******************************************************************************
    **
    ** Function:        P2pServer
    **
    ** Description:     Initialize member variables.
    **
    ** Returns:         None
    **
    *******************************************************************************/
    P2pServer ();


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
    NfaConn *findServerConnection (tNFA_HANDLE nfaConnHandle);
};


/*****************************************************************************
**
**  Name:           P2pClient
**
**  Description:    Store information about an out-bound connection to a peer.
**
*****************************************************************************/
class P2pClient
{
public:
    tNFA_HANDLE         mNfaP2pClientHandle;    // NFA p2p handle of client
    bool                mIsConnecting;          // Set true while connecting
    tNFA_HANDLE         mSnepConnHandle;
    UINT32              mSnepNdefMsgLen;        // SNEP total NDEF message length
    UINT32              mSnepNdefBufLen;        // SNEP NDEF buffer length
    UINT8              *mSnepNdefBuf;           // SNEP NDEF Message
    bool                mIsSnepSentOk;          // SNEP transmission status
    NfaConn             mClientConn;
    SyncEvent           mRegisteringEvent;      // For client registration
    SyncEvent           mConnectingEvent;       // for NFA_P2pConnectByName or Sap()
    SyncEvent           mSnepEvent;             // To wait for SNEP completion

    /*******************************************************************************
    **
    ** Function:        P2pClient
    **
    ** Description:     Initialize member variables.
    **
    ** Returns:         None
    **
    *******************************************************************************/
    P2pClient ();


    /*******************************************************************************
    **
    ** Function:        ~P2pClient
    **
    ** Description:     Free all resources.
    **
    ** Returns:         None
    **
    *******************************************************************************/
    ~P2pClient ();
};

