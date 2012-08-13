/*****************************************************************************
**
**  Name:           IntervalTimer.h
**
**  Description:    Asynchronous interval timer.
**
**  Copyright (c) 2012, Broadcom Corp., All Rights Reserved.
**  Proprietary and confidential.
**
*****************************************************************************/

#include <time.h>


class IntervalTimer
{
public:
    typedef void (*TIMER_FUNC) (union sigval);

    IntervalTimer();
    ~IntervalTimer();
    bool set(int ms, TIMER_FUNC cb);
    void kill();
    bool create(TIMER_FUNC );

private:
    timer_t mTimerId;
    TIMER_FUNC mCb;
};
