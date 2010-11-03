/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.nfc;

/**
 * LlcpServiceSocket represents a LLCP Service to be used in a
 * Connection-oriented communication
 */
public class NativeLlcpServiceSocket {

    private int mHandle;

    private int mLocalMiu;

    private int mLocalRw;

    private int mLocalLinearBufferLength;

    private int mSap;

    private String mServiceName;

    public NativeLlcpServiceSocket(){

    }

    public NativeLlcpServiceSocket(int sap, String serviceName, int miu, int rw, int linearBufferLength){
        mSap = sap;
        mServiceName = serviceName;
        mLocalMiu = miu;
        mLocalRw = rw;
        mLocalLinearBufferLength = linearBufferLength;
    }

    public native NativeLlcpSocket doAccept(int miu, int rw, int linearBufferLength);

    public native boolean doClose();

    public int getHandle(){
        return mHandle;
    }

    public int getRw(){
        return mLocalRw;
    }

    public int getMiu(){
        return mLocalMiu;
    }

    public int getLinearBufferLength(){
        return mLocalLinearBufferLength;
    }
}
