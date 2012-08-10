/*****************************************************************************
**
**  Name:           Mutex.h
**
**  Description:    Encapsulate a mutex for thread synchronization.
**
**  Copyright (c) 2012, Broadcom Corp., All Rights Reserved.
**  Proprietary and confidential.
**
*****************************************************************************/

#pragma once
#include <pthread.h>


class Mutex
{
public:
    /*******************************************************************************
    **
    ** Function:        Mutex
    **
    ** Description:     Initialize member variables.
    **
    ** Returns:         None.
    **
    *******************************************************************************/
    Mutex ();


    /*******************************************************************************
    **
    ** Function:        ~Mutex
    **
    ** Description:     Cleanup all resources.
    **
    ** Returns:         None.
    **
    *******************************************************************************/
    ~Mutex ();


    /*******************************************************************************
    **
    ** Function:        lock
    **
    ** Description:     Block the thread and try lock the mutex.
    **
    ** Returns:         None.
    **
    *******************************************************************************/
    void lock ();


    /*******************************************************************************
    **
    ** Function:        unlock
    **
    ** Description:     Unlock a mutex to unblock a thread.
    **
    ** Returns:         None.
    **
    *******************************************************************************/
    void unlock ();


    /*******************************************************************************
    **
    ** Function:        tryLock
    **
    ** Description:     Try to lock the mutex.
    **
    ** Returns:         True if the mutex is locked.
    **
    *******************************************************************************/
    bool tryLock ();


    /*******************************************************************************
    **
    ** Function:        nativeHandle
    **
    ** Description:     Get the handle of the mutex.
    **
    ** Returns:         Handle of the mutex.
    **
    *******************************************************************************/
    pthread_mutex_t* nativeHandle ();

private:
    pthread_mutex_t mMutex;
};

