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
 *  Adjust the controller's power states.
 */
#include "OverrideLog.h"
#include "PowerSwitch.h"
#include "NfcJniUtil.h"
#include "config.h"
#include "SecureElement.h"


namespace android
{
    void doStartupConfig ();
}


PowerSwitch PowerSwitch::sPowerSwitch;
const PowerSwitch::PowerActivity PowerSwitch::DISCOVERY=0x01;
const PowerSwitch::PowerActivity PowerSwitch::SE_ROUTING=0x02;
const PowerSwitch::PowerActivity PowerSwitch::SE_CONNECTED=0x04;

/*******************************************************************************
**
** Function:        PowerSwitch
**
** Description:     Initialize member variables.
**
** Returns:         None
**
*******************************************************************************/
PowerSwitch::PowerSwitch ()
:   mCurrLevel (UNKNOWN_LEVEL),
    mCurrDeviceMgtPowerState (NFA_DM_PWR_STATE_UNKNOWN),
    mDesiredScreenOffPowerState (0),
    mCurrActivity(0)
{
}


/*******************************************************************************
**
** Function:        ~PowerSwitch
**
** Description:     Release all resources.
**
** Returns:         None
**
*******************************************************************************/
PowerSwitch::~PowerSwitch ()
{
}


/*******************************************************************************
**
** Function:        getInstance
**
** Description:     Get the singleton of this object.
**
** Returns:         Reference to this object.
**
*******************************************************************************/
PowerSwitch& PowerSwitch::getInstance ()
{
    return sPowerSwitch;
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
void PowerSwitch::initialize (PowerLevel level)
{
    static const char fn [] = "PowerSwitch::initialize";

    mMutex.lock ();

    ALOGD ("%s: level=%s (%u)", fn, powerLevelToString(level), level);
    GetNumValue (NAME_SCREEN_OFF_POWER_STATE, &mDesiredScreenOffPowerState, sizeof(mDesiredScreenOffPowerState));
    ALOGD ("%s: desired screen-off state=%d", fn, mDesiredScreenOffPowerState);

    switch (level)
    {
    case FULL_POWER:
        mCurrDeviceMgtPowerState = NFA_DM_PWR_MODE_FULL;
        mCurrLevel = level;
        break;

    case UNKNOWN_LEVEL:
        mCurrDeviceMgtPowerState = NFA_DM_PWR_STATE_UNKNOWN;
        mCurrLevel = level;
        break;

    default:
        ALOGE ("%s: not handled", fn);
        break;
    }
    mMutex.unlock ();
}


/*******************************************************************************
**
** Function:        getLevel
**
** Description:     Get the current power level of the controller.
**
** Returns:         Power level.
**
*******************************************************************************/
PowerSwitch::PowerLevel PowerSwitch::getLevel ()
{
    PowerLevel level = UNKNOWN_LEVEL;
    mMutex.lock ();
    level = mCurrLevel;
    mMutex.unlock ();
    return level;
}


/*******************************************************************************
**
** Function:        setLevel
**
** Description:     Set the controller's power level.
**                  level: power level.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool PowerSwitch::setLevel (PowerLevel newLevel)
{
    static const char fn [] = "PowerSwitch::setLevel";
    bool retval = false;

    mMutex.lock ();

    ALOGD ("%s: level=%s (%u)", fn, powerLevelToString(newLevel), newLevel);
    if (mCurrLevel == newLevel)
    {
        retval = true;
        goto TheEnd;
    }

    switch (newLevel)
    {
    case FULL_POWER:
        if (mCurrDeviceMgtPowerState == NFA_DM_PWR_MODE_OFF_SLEEP)
            retval = setPowerOffSleepState (false);
        break;

    case LOW_POWER:
    case POWER_OFF:
        if (isPowerOffSleepFeatureEnabled())
            retval = setPowerOffSleepState (true);
        else if (mDesiredScreenOffPowerState == 1) //.conf file desires full-power
        {
            mCurrLevel = FULL_POWER;
            retval = true;
        }
        break;

    default:
        ALOGE ("%s: not handled", fn);
        break;
    }

TheEnd:
    mMutex.unlock ();
    return retval;
}

/*******************************************************************************
**
** Function:        setModeOff
**
** Description:     Set a mode to be deactive.
**
** Returns:         True if any mode is still active.
**
*******************************************************************************/
bool PowerSwitch::setModeOff (PowerActivity deactivated)
{
    bool retVal = false;

    mMutex.lock ();
    mCurrActivity &= ~deactivated;
    retVal = mCurrActivity != 0;
    ALOGD ("PowerSwitch::setModeOff(deactivated=0x%x) : mCurrActivity=0x%x", deactivated, mCurrActivity);
    mMutex.unlock ();
    return retVal;
}


/*******************************************************************************
**
** Function:        setModeOn
**
** Description:     Set a mode to be active.
**
** Returns:         True if any mode is active.
**
*******************************************************************************/
bool PowerSwitch::setModeOn (PowerActivity activated)
{
    bool retVal = false;

    mMutex.lock ();
    mCurrActivity |= activated;
    retVal = mCurrActivity != 0;
    ALOGD ("PowerSwitch::setModeOn(activated=0x%x) : mCurrActivity=0x%x", activated, mCurrActivity);
    mMutex.unlock ();
    return retVal;
}


/*******************************************************************************
**
** Function:        setPowerOffSleepState
**
** Description:     Adjust controller's power-off-sleep state.
**                  sleep: whether to enter sleep state.
**
** Returns:         True if ok.
**
*******************************************************************************/
bool PowerSwitch::setPowerOffSleepState (bool sleep)
{
    static const char fn [] = "PowerSwitch::setPowerOffSleepState";
    ALOGD ("%s: enter; sleep=%u", fn, sleep);
    tNFA_STATUS stat = NFA_STATUS_FAILED;
    bool retval = false;

    if (sleep) //enter power-off-sleep state
    {
        //make sure the current power state is ON
        if (mCurrDeviceMgtPowerState != NFA_DM_PWR_MODE_OFF_SLEEP)
        {
            SyncEventGuard guard (mPowerStateEvent);
            ALOGD ("%s: try power off", fn);
            stat = NFA_PowerOffSleepMode (TRUE);
            if (stat == NFA_STATUS_OK)
            {
                mPowerStateEvent.wait ();
                mCurrLevel = LOW_POWER;
            }
            else
            {
                ALOGE ("%s: API fail; stat=0x%X", fn, stat);
                goto TheEnd;
            }
        }
        else
        {
            ALOGE ("%s: power is not ON; curr device mgt power state=%s (%u)", fn,
                    deviceMgtPowerStateToString (mCurrDeviceMgtPowerState), mCurrDeviceMgtPowerState);
            goto TheEnd;
        }
    }
    else //exit power-off-sleep state
    {
        //make sure the current power state is OFF
        if (mCurrDeviceMgtPowerState != NFA_DM_PWR_MODE_FULL)
        {
            mCurrDeviceMgtPowerState = NFA_DM_PWR_STATE_UNKNOWN;
            SyncEventGuard guard (mPowerStateEvent);
            ALOGD ("%s: try full power", fn);
            stat = NFA_PowerOffSleepMode (FALSE);
            if (stat == NFA_STATUS_OK)
            {
                mPowerStateEvent.wait ();
                if (mCurrDeviceMgtPowerState != NFA_DM_PWR_MODE_FULL)
                {
                    ALOGE ("%s: unable to full power; curr device mgt power stat=%s (%u)", fn,
                            deviceMgtPowerStateToString (mCurrDeviceMgtPowerState), mCurrDeviceMgtPowerState);
                    goto TheEnd;
                }
                android::doStartupConfig ();
                mCurrLevel = FULL_POWER;
            }
            else
            {
                ALOGE ("%s: API fail; stat=0x%X", fn, stat);
                goto TheEnd;
            }
        }
        else
        {
            ALOGE ("%s: not in power-off state; curr device mgt power state=%s (%u)", fn,
                    deviceMgtPowerStateToString (mCurrDeviceMgtPowerState), mCurrDeviceMgtPowerState);
            goto TheEnd;
        }
    }

    retval = true;
TheEnd:
    ALOGD ("%s: exit; return %u", fn, retval);
    return retval;
}


/*******************************************************************************
**
** Function:        deviceMgtPowerStateToString
**
** Description:     Decode power level to a string.
**                  deviceMgtPowerState: power level.
**
** Returns:         Text representation of power level.
**
*******************************************************************************/
const char* PowerSwitch::deviceMgtPowerStateToString (UINT8 deviceMgtPowerState)
{
    switch (deviceMgtPowerState)
    {
    case NFA_DM_PWR_MODE_FULL:
        return "DM-FULL";
    case NFA_DM_PWR_MODE_OFF_SLEEP:
        return "DM-OFF";
    default:
        return "DM-unknown????";
    }
}


/*******************************************************************************
**
** Function:        powerLevelToString
**
** Description:     Decode power level to a string.
**                  level: power level.
**
** Returns:         Text representation of power level.
**
*******************************************************************************/
const char* PowerSwitch::powerLevelToString (PowerLevel level)
{
    switch (level)
    {
    case UNKNOWN_LEVEL:
        return "PS-UNKNOWN";
    case FULL_POWER:
        return "PS-FULL";
    case LOW_POWER:
        return "PS-LOW-POWER";
    case POWER_OFF:
        return "PS-POWER-OFF";
    default:
        return "PS-unknown????";
    }
}


/*******************************************************************************
**
** Function:        abort
**
** Description:     Abort and unblock currrent operation.
**
** Returns:         None
**
*******************************************************************************/
void PowerSwitch::abort ()
{
    static const char fn [] = "PowerSwitch::abort";
    ALOGD ("%s", fn);
    SyncEventGuard guard (mPowerStateEvent);
    mPowerStateEvent.notifyOne ();
}


/*******************************************************************************
**
** Function:        deviceManagementCallback
**
** Description:     Callback function for the stack.
**                  event: event ID.
**                  eventData: event's data.
**
** Returns:         None
**
*******************************************************************************/
void PowerSwitch::deviceManagementCallback (UINT8 event, tNFA_DM_CBACK_DATA* eventData)
{
    static const char fn [] = "PowerSwitch::deviceManagementCallback";

    switch (event)
    {
    case NFA_DM_PWR_MODE_CHANGE_EVT:
        {
            tNFA_DM_PWR_MODE_CHANGE& power_mode = eventData->power_mode;
            ALOGD ("%s: NFA_DM_PWR_MODE_CHANGE_EVT; status=%u; device mgt power mode=%s (%u)", fn,
                    power_mode.status, sPowerSwitch.deviceMgtPowerStateToString (power_mode.power_mode), power_mode.power_mode);
            SyncEventGuard guard (sPowerSwitch.mPowerStateEvent);
            if (power_mode.status == NFA_STATUS_OK)
                sPowerSwitch.mCurrDeviceMgtPowerState = power_mode.power_mode;
            sPowerSwitch.mPowerStateEvent.notifyOne ();
        }
        break;
    }
}


/*******************************************************************************
**
** Function:        isPowerOffSleepFeatureEnabled
**
** Description:     Whether power-off-sleep feature is enabled in .conf file.
**
** Returns:         True if feature is enabled.
**
*******************************************************************************/
bool PowerSwitch::isPowerOffSleepFeatureEnabled ()
{
    return mDesiredScreenOffPowerState == 0;
}

