/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.content.Context;
import android.nfc.Tag;
import android.nfc.tech.TagTechnology;
import android.provider.Settings;
import android.text.format.Time;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Helper class for storing and modifying NFC unlock state.
 *
 * See {@link android.nfc.INfcUnlockSettings} for details.
 */
class NfcUnlockManager {

    private static final String TAG = "NfcUnlockManager";

    private static final String TAG_KEY = "tag";
    private static final String TIMESTAMP_KEY = "timestamp";
    private static final String TECH_KEY = "tech";

    private static final String NFC_LOCK_FILE_FORMAT = "/%d.nfclock.key";

    private final Map<Integer, UnlockSettings> mUnlockSettings;
    private final Context mContext;

    NfcUnlockManager(Context context) {
        mContext = context;
        mUnlockSettings = new HashMap<Integer, UnlockSettings>();
    }

    boolean canUnlock(int userId, Tag tag) {
        String hashedTagId = hashTag(tag.getId());

        if (hashedTagId == null) {
            return false;
        }

        UnlockSettings unlockSettings = maybeLoadUnlockSettings(userId);

        synchronized (unlockSettings) {
            return unlockSettings.containsTag(hashedTagId);
        }
    }

    boolean registerTag(int userId, Tag tag) {
        String hashedTagId = hashTag(tag.getId());

        if (hashedTagId == null) {
            return false;
        }

        UnlockSettings unlockSettings = maybeLoadUnlockSettings(userId);

        synchronized (unlockSettings) {

            if (unlockSettings.containsTag(hashedTagId)) {
                return true;
            }
            try {
                unlockSettings.addTag(hashedTagId, tag.getTechCodeList());
            } catch (IllegalArgumentException ex) {
                Log.e(TAG, "registerTag failed", ex);
                return false;
            }

            try {
                // TODO: consider writing in a thread
                writeUnlockSettings(userId, unlockSettings);
            } catch (IOException ex) {
                Log.e(TAG, "Failed to persist unlock settings changes.", ex);
                return false;
            } catch (IllegalStateException ex) {
                Log.e(TAG, "Settings corrupted, unable to persist changes.");
                return false;
            }
        }

        return true;
    }

    long[] getTagRegistryTimes(int userId) {
        List<Long> timestamps = maybeLoadUnlockSettings(userId).getTimestamps();
        long[] result = new long[timestamps.size()];

        int i = 0;
        for (Long timestamp : timestamps) {
            result[i++] = timestamp.longValue();
        }

        return result;
    }

    void setNfcUnlockEnabled(int userId, boolean enabled) {
        Settings.Secure.putIntForUser(
                mContext.getContentResolver(), Settings.Secure.NFC_UNLOCK_ENABLED, enabled ? 1 : 0,
                userId);
    }

    boolean getNfcUnlockEnabled(int userId) {
        try {
            return Settings.Secure.getIntForUser(
                    mContext.getContentResolver(), Settings.Secure.NFC_UNLOCK_ENABLED, userId) == 1;
        } catch (Settings.SettingNotFoundException e) {
            return false;
        }
    }

    private UnlockSettings maybeLoadUnlockSettings(int userId) {
        synchronized (mUnlockSettings) {
            if (!mUnlockSettings.containsKey(userId)) {
                mUnlockSettings.put(userId, loadUnlockSettings(userId));
            }

            return mUnlockSettings.get(userId);
        }
    }

    public boolean deregisterTag(int userId, long timestamp) {
        UnlockSettings unlockSettings = maybeLoadUnlockSettings(userId);

        synchronized (unlockSettings) {
            if (unlockSettings.removeTag(timestamp)) {
                try {
                    // TODO: consider writing in a thread
                    writeUnlockSettings(userId, unlockSettings);
                    return true;
                } catch (IOException ex) {
                    Log.e(TAG, "Failed to persist unlock settings changes.", ex);
                    return false;
                } catch (IllegalStateException ex) {
                    Log.e(TAG, "Settings corrupted, unable to persist changes.");
                    return false;
                }
            }
        }

        return false;
    }

    public int getRegisteredTechMask(int userId) {
        UnlockSettings unlockSettings = maybeLoadUnlockSettings(userId);
        return unlockSettings.getTechMask();
    }


    private String getNfcLockFile(int userId) throws  IOException {
        return mContext.getFilesDir().getAbsolutePath()
                .concat(String.format(NFC_LOCK_FILE_FORMAT, userId));
    }

    private static String hashTag(byte[] tagId) {
        String algo = null;
        byte[] sha1;
        byte[] md5;

        try {
            sha1 = MessageDigest.getInstance(algo = "SHA-1").digest(tagId);
            md5 = MessageDigest.getInstance(algo = "MD5").digest(tagId);
        } catch (NoSuchAlgorithmException ex) {
            Log.e(TAG, "Failed to encode tag due to missing algo: " + algo);
            return null;
        }

        return Base64.encodeToString(sha1, Base64.DEFAULT)
                + Base64.encodeToString(md5, Base64.DEFAULT);
    }

    private void writeUnlockSettings(int userId, UnlockSettings unlockSettings)
            throws IOException {
        AtomicFile atomicFile = new AtomicFile(new File(getNfcLockFile(userId)));

        FileOutputStream unlockSettingsStream = atomicFile.startWrite();

        byte[] bytes;

        try {
            JSONArray unlockSettingsJson = unlockSettings.toJSONArray();
            bytes = unlockSettingsJson.toString().getBytes();
        } catch (IllegalStateException ex) {
            // User in-memory data is corrupt, invalidate and reload lazily
            // TODO: consider reloading in a thread
            synchronized (mUnlockSettings) {
                mUnlockSettings.remove(userId);
            }
            throw ex;
        }

        try {
            unlockSettingsStream.write(bytes, 0, bytes.length);
            atomicFile.finishWrite(unlockSettingsStream);
        } catch (IOException ex) {
            atomicFile.failWrite(unlockSettingsStream);
            throw ex;
        }
    }


    // TODO: consider using SQLite DB and loading all files on startup.
    private UnlockSettings loadUnlockSettings(int userId) {
        Log.i(TAG, "Loading config...");
        AtomicFile nfcLockFile = null;
        List<String> tagList = new ArrayList<String>();
        List<Long>  timestamps = new ArrayList<Long>();
        List<Integer> technologies = new ArrayList<Integer>();

        try {
            nfcLockFile = new AtomicFile(new File(getNfcLockFile(userId)));

            byte[] bytes = nfcLockFile.readFully();
            JSONArray tagArray = new JSONArray(new String(bytes));

            for (int i = 0; i < tagArray.length(); ++i) {
                try {
                    JSONObject tagData = tagArray.getJSONObject(i);
                    String tag = tagData.getString(TAG_KEY);
                    String time = tagData.getString(TIMESTAMP_KEY);
                    int technology = tagData.getInt(TECH_KEY);
                    long timeMillis = Long.parseLong(time);
                    tagList.add(tag);
                    timestamps.add(timeMillis);
                    technologies.add(technology);
                } catch (JSONException ex) {
                    Log.e(TAG, "Encountered corrupt data. Skipping.", ex);
                } catch (NumberFormatException ex) {
                    Log.e(TAG, "Encountered corrupt timestamp. Skipping.", ex);
                }
            }

        } catch (FileNotFoundException ex) {
            Log.i(TAG, "NFC Lock file not found");
        } catch (IOException ex) {
            Log.e(TAG, "Failed to read from NFC lock file", ex);
        } catch (JSONException e) {
            // No tags are registered
        }

        return new UnlockSettings(tagList, timestamps, technologies);
    }

    private class UnlockSettings {
        private final Map<Integer, Integer> TECH_CODE_TO_MASK = new HashMap<Integer, Integer>();

        private final List<String> mTags;
        private final Set<String> mTagSet;
        private final List<Long> mTimestamps;
        private final List<Integer> mTechList;

        private int mTechMask;

        UnlockSettings(List<String> tags, List<Long> timestamps, List<Integer> technologies) {
            this.mTags = tags;
            this.mTimestamps = timestamps;
            this.mTagSet = new HashSet<String>(tags);
            this.mTechList = new ArrayList<Integer>(technologies);

            computeTechMask();

            TECH_CODE_TO_MASK.put(TagTechnology.NFC_A, NfcService.NFC_POLL_A);
            TECH_CODE_TO_MASK.put(TagTechnology.NFC_B,
                    NfcService.NFC_POLL_B | NfcService.NFC_POLL_B_PRIME);
            TECH_CODE_TO_MASK.put(TagTechnology.NFC_V, NfcService.NFC_POLL_ISO15693);
            TECH_CODE_TO_MASK.put(TagTechnology.NFC_F, NfcService.NFC_POLL_F);
            TECH_CODE_TO_MASK.put(TagTechnology.NFC_BARCODE, NfcService.NFC_POLL_KOVIO);
        }

        List<Long> getTimestamps() {
            return new ArrayList<Long>(mTimestamps);
        }

        void addTag(String tag, int[] techList) throws IllegalArgumentException {

            if (mTagSet.contains(tag)) {
                return;
            }

            populateTechList(techList);

            mTags.add(tag);
            mTagSet.add(tag);
            Time time = new Time();
            time.setToNow();
            mTimestamps.add(time.toMillis(true));
        }

        int getTechMask() {
            return mTechMask;
        }

        boolean removeTag(long timestamp) {
            int tagIndex = mTimestamps.indexOf(timestamp);

            if (tagIndex < 0) {
                return false;
            }

            String toRemove = mTags.get(tagIndex);

            mTags.remove(tagIndex);
            mTimestamps.remove(tagIndex);
            mTechList.remove(tagIndex);
            mTagSet.remove(toRemove);

            computeTechMask();

            return true;
        }


        boolean containsTag(String tag) {
            return mTags.contains(tag);
        }

        JSONArray toJSONArray() throws IllegalStateException {
            JSONArray unlockSettings = new JSONArray();

            for (int i = 0; i < mTags.size(); ++i) {
                JSONObject thisTag = new JSONObject();
                try {
                    thisTag.put(TAG_KEY, mTags.get(i));
                    thisTag.put(TIMESTAMP_KEY, mTimestamps.get(i));
                    thisTag.put(TECH_KEY, mTechList.get(i));

                    unlockSettings.put(thisTag);
                } catch (JSONException ex) {
                    Log.e(TAG, "Found NULL in in-memory data. Refusing to write unlock settings.");
                    throw new IllegalStateException(ex);
                }
            }

            return unlockSettings;
        }

        private void populateTechList(int[] techList) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < techList.length; i++) {
                if (TECH_CODE_TO_MASK.containsKey(techList[i])) {
                    int mask = TECH_CODE_TO_MASK.get(techList[i]);
                    mTechList.add(mask);
                    mTechMask |= mask;
                    return;
                }
                builder.append(techList[i] + ",");
            }

            String technologies = builder.toString();
            throw new IllegalArgumentException(
                    String.format(
                            "No supported technologies found in set [%s]. Refusing to add tag.",
                            technologies.substring(0, technologies.length() - 1)));
        }


        private void computeTechMask() {
            int techMask = 0;

            for (int mask : mTechList) {
                techMask |= mask;
            }

            mTechMask = techMask;
        }
    }
}

