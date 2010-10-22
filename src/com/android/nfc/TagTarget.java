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

import java.util.HashMap;

import android.nfc.NdefTag;
import android.nfc.Tag;

/**
 * Helper to convert between internal tag types and public target types
 */
public abstract class TagTarget {
    static final String INTERNAL_TARGET_TYPE_ISO14443_3A = "Iso14443-3A";
    static final String INTERNAL_TARGET_TYPE_ISO14443_3B = "Iso14443-3B";
    static final String INTERNAL_TARGET_TYPE_ISO14443_4 = "Iso14443-4";
    static final String INTERNAL_TARGET_TYPE_MIFARE_UL = "MifareUL";
    static final String INTERNAL_TARGET_TYPE_MIFARE_1K = "Mifare1K";
    static final String INTERNAL_TARGET_TYPE_MIFARE_4K = "Mifare4K";
    static final String INTERNAL_TARGET_TYPE_MIFARE_DESFIRE = "MifareDESFIRE";
    static final String INTERNAL_TARGET_TYPE_MIFARE_UNKNOWN = "Unknown Mifare";
    static final String INTERNAL_TARGET_TYPE_FELICA = "Felica";
    static final String INTERNAL_TARGET_TYPE_JEWEL = "Jewel";
    static final String INTERNAL_TARGET_TYPE_UNKNOWN = "Unknown Type";

    static final HashMap<String, String[]> INT_TYPE_TO_RAW_TARGETS = new HashMap<String, String[]>() {
        {
            /* TODO: handle multiprotocol */
            put(INTERNAL_TARGET_TYPE_ISO14443_3A, new String[] { Tag.TARGET_ISO_14443_3A });
            put(INTERNAL_TARGET_TYPE_ISO14443_3B, new String[] { Tag.TARGET_ISO_14443_3B });
            put(INTERNAL_TARGET_TYPE_MIFARE_UL, new String[] { Tag.TARGET_ISO_14443_3A });
            put(INTERNAL_TARGET_TYPE_MIFARE_1K, new String[] { Tag.TARGET_ISO_14443_3A });
            put(INTERNAL_TARGET_TYPE_MIFARE_4K, new String[] { Tag.TARGET_ISO_14443_3A });
            put(INTERNAL_TARGET_TYPE_MIFARE_DESFIRE, new String[] { Tag.TARGET_ISO_14443_3A });
            put(INTERNAL_TARGET_TYPE_MIFARE_UNKNOWN, new String[] { Tag.TARGET_ISO_14443_3A });
            put(INTERNAL_TARGET_TYPE_FELICA, new String[] { Tag.TARGET_JIS_X_6319_4 });
            put(INTERNAL_TARGET_TYPE_JEWEL, new String[] { Tag.TARGET_ISO_14443_3A });
        }
    };

    static final HashMap<String, String[]> INT_TYPE_TO_NDEF_TARGETS = new HashMap<String, String[]>() {
        {
            // TODO: handle multiprotocol
            put(INTERNAL_TARGET_TYPE_JEWEL, new String[] { NdefTag.TARGET_TYPE_1 });
            put(INTERNAL_TARGET_TYPE_MIFARE_UL, new String[] { NdefTag.TARGET_TYPE_2 });
            put(INTERNAL_TARGET_TYPE_MIFARE_1K, new String[] { NdefTag.TARGET_MIFARE_CLASSIC });
            put(INTERNAL_TARGET_TYPE_MIFARE_4K, new String[] { NdefTag.TARGET_MIFARE_CLASSIC });
            put(INTERNAL_TARGET_TYPE_FELICA, new String[] { NdefTag.TARGET_TYPE_3 });
            put(INTERNAL_TARGET_TYPE_ISO14443_4, new String[] { NdefTag.TARGET_TYPE_4 });
            put(INTERNAL_TARGET_TYPE_MIFARE_DESFIRE, new String[] { NdefTag.TARGET_TYPE_4 });
        }
    };

    static String[] internalTypeToRawTargets(String internalType) {
        String[] rawTargets = INT_TYPE_TO_RAW_TARGETS.get(internalType);
        if (rawTargets == null) {
            rawTargets = new String[] { Tag.TARGET_OTHER };
        }
        return rawTargets;
    }

    static String[] internalTypeToNdefTargets(String internalType) {
        String[] ndefTargets = INT_TYPE_TO_NDEF_TARGETS.get(internalType);
        if (ndefTargets == null) {
            ndefTargets = new String[] { NdefTag.TARGET_OTHER };
        }
        return ndefTargets;
    }
}
