/*****************************************************************************
**
**  Name:           DataQueue.h
**
**  Description:    Store data bytes in a variable-size queue.
**
**  Copyright (c) 2012, Broadcom Corp., All Rights Reserved.
**  Proprietary and confidential.
**
*****************************************************************************/

#pragma once
#include "NfcJniUtil.h"
#include "gki.h"
#include "Mutex.h"
#include <list>


class DataQueue
{
public:
    /*******************************************************************************
    **
    ** Function:        DataQueue
    **
    ** Description:     Initialize member variables.
    **
    ** Returns:         None.
    **
    *******************************************************************************/
    DataQueue ();


    /*******************************************************************************
    **
    ** Function:        ~DataQueue
    **
    ** Description:      Release all resources.
    **
    ** Returns:         None.
    **
    *******************************************************************************/
    ~DataQueue ();


    /*******************************************************************************
    **
    ** Function:        enqueue
    **
    ** Description:     Append data to the queue.
    **                  data: array of bytes
    **                  dataLen: length of the data.
    **
    ** Returns:         True if ok.
    **
    *******************************************************************************/
    bool enqueue (UINT8* data, UINT16 dataLen);


    /*******************************************************************************
    **
    ** Function:        dequeue
    **
    ** Description:     Retrieve and remove data from the front of the queue.
    **                  buffer: array to store the data.
    **                  bufferMaxLen: maximum size of the buffer.
    **                  actualLen: actual length of the data.
    **
    ** Returns:         True if ok.
    **
    *******************************************************************************/
    bool dequeue (UINT8* buffer, UINT16 bufferMaxLen, UINT16& actualLen);


    /*******************************************************************************
    **
    ** Function:        isEmpty
    **
    ** Description:     Whether the queue is empty.
    **
    ** Returns:         True if empty.
    **
    *******************************************************************************/
    bool isEmpty();

private:
    struct tHeader
    {
        UINT16 mDataLen; //number of octets of data
        UINT16 mOffset; //offset of the first octet of data
    };
    typedef std::list<tHeader*> Queue;

    Queue mQueue;
    Mutex mMutex;
};

