package com.android.nfc;

/**
 * Parameters for enabling NFC tag discovery and polling.
 */
public final class NfcDiscoveryParameters {

    public static class Builder {

        private NfcDiscoveryParameters mParameters;

        private Builder() {
            mParameters = new NfcDiscoveryParameters();
        }

        public NfcDiscoveryParameters.Builder setTechMask(int techMask) {
            mParameters.mTechMask = techMask;
            return this;
        }

        public NfcDiscoveryParameters.Builder setEnableLowPowerDiscovery(boolean enable) {
            mParameters.mEnableLowPowerDiscovery = enable;
            return this;
        }

        public NfcDiscoveryParameters.Builder setEnableReaderMode(boolean enable) {
            mParameters.mEnableReaderMode = enable;

            if (enable) {
                mParameters.mEnableLowPowerDiscovery = false;
            }

            return this;
        }

        public NfcDiscoveryParameters.Builder setRestartPolling(boolean restart) {
            mParameters.mRestartPolling = restart;
            return this;
        }

        public NfcDiscoveryParameters build() {
            if (mParameters.mEnableReaderMode && mParameters.mEnableLowPowerDiscovery) {
                throw new IllegalStateException("Can't enable LPTD and reader mode simultaneously");
            }
            return mParameters;
        }
    }

    // Polling technology masks
    static final int NFC_POLL_DEFAULT = 0;
    static final int NFC_POLL_A = 0x01;
    static final int NFC_POLL_B = 0x02;
    static final int NFC_POLL_F = 0x04;
    static final int NFC_POLL_ISO15693 = 0x08;
    static final int NFC_POLL_B_PRIME = 0x10;
    static final int NFC_POLL_KOVIO = 0x20;

    private int mTechMask = 0;
    private boolean mEnableLowPowerDiscovery = true;
    private boolean mEnableReaderMode = false;
    private boolean mRestartPolling = false;

    public NfcDiscoveryParameters() {}

    public int getTechMask() {
        return mTechMask;
    }

    public boolean shouldEnableLowPowerDiscovery() {
        return mEnableLowPowerDiscovery;
    }

    public boolean shouldEnableReaderMode() {
        return mEnableReaderMode;
    }

    public boolean shouldRestartPolling() {
        return mRestartPolling;
    }

    public static NfcDiscoveryParameters.Builder newBuilder() {
        return new Builder();
    }

    public static NfcDiscoveryParameters getDefaultInstance() {
        return new NfcDiscoveryParameters();
    }
}
