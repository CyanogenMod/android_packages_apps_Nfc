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
 * LlcpClientSocket represents a LLCP Connection-Oriented client to be used in a
 * connection-oriented communication
 */
public class NativeLlcpSocket {

    private int mHandle;

    private int mSap;

    private int mLocalMiu;

    private int mLocalRw;

    public NativeLlcpSocket(){

    }

    public NativeLlcpSocket(int sap, int miu, int rw){
        mSap = sap;
        mLocalMiu = miu;
        mLocalRw = rw;
    }

    public native boolean doConnect(int nSap);

    public native boolean doConnectBy(String sn);

    public native boolean doClose();

    public native boolean doSend(byte[] data);

    public native int doReceive(byte[] recvBuff);

    public native int doGetRemoteSocketMiu();

    public native int doGetRemoteSocketRw();

    public int getSap(){
        return mSap;
    }

    public int getMiu(){
        return mLocalMiu;
    }

    public int getRw(){
        return mLocalRw;
    }

    public int getHandle(){
        return mHandle;
    }

}
