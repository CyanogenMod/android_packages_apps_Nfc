/*****************************************************************************
**
**  Name:           IntervalTimer.cpp
**
**  Description:    Asynchronous interval timer.
**
**  Copyright (c) 2012, Broadcom Corp., All Rights Reserved.
**  Proprietary and confidential.
**
*****************************************************************************/

#include "IntervalTimer.h"
#include "OverrideLog.h"


IntervalTimer::IntervalTimer()
{
    mTimerId = NULL;
    mCb = NULL;
}


bool IntervalTimer::set(int ms, TIMER_FUNC cb)
{
    if (mTimerId == NULL)
    {
        if (cb == NULL)
            return false;

        if (!create(cb))
            return false;
    }
    if (cb != mCb)
    {
        kill();
        if (!create(cb))
            return false;
    }

    int stat = 0;
    struct itimerspec ts;
    ts.it_value.tv_sec = ms / 1000;
    ts.it_value.tv_nsec = (ms % 1000) * 1000000;

    ts.it_interval.tv_sec = 0;
    ts.it_interval.tv_nsec = 0;

    stat = timer_settime(mTimerId, 0, &ts, 0);
    if (stat == -1)
        ALOGE("IntervalTimer::set: fail set timer");
    return stat == 0;
}


IntervalTimer::~IntervalTimer()
{
    kill();
}


void IntervalTimer::kill()
{
    if (mTimerId == NULL)
        return;

    timer_delete(mTimerId);
    mTimerId = NULL;
    mCb = NULL;
}


bool IntervalTimer::create(TIMER_FUNC cb)
{
    struct sigevent se;
    int stat = 0;

    /*
     * Set the sigevent structure to cause the signal to be
     * delivered by creating a new thread.
     */
    se.sigev_notify = SIGEV_THREAD;
    se.sigev_value.sival_ptr = &mTimerId;
    se.sigev_notify_function = cb;
    se.sigev_notify_attributes = NULL;
    mCb = cb;
    stat = timer_create(CLOCK_MONOTONIC, &se, &mTimerId);
    if (stat == -1)
        ALOGE("IntervalTimer::create: fail create timer");
    return stat == 0;
}
