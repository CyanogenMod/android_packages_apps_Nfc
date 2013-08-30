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

package com.android.nfc.cardemulation;

import com.android.internal.R;
import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;

import java.util.ArrayList;
import java.util.List;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.drawable.Drawable;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

public class AppChooserActivity extends AlertActivity
        implements AdapterView.OnItemClickListener {

    static final String TAG = "AppChooserActivity";

    public static final String EXTRA_COMPONENTS = "components";
    public static final String EXTRA_CATEGORY = "category";
    public static final String EXTRA_FAILED_COMPONENT = "failed_component";

    private int mIconSize;
    private ListView mListView;
    private ListAdapter mListAdapter;
    private CardEmulation mCardEmuManager;
    private String mCategory;

    protected void onCreate(Bundle savedInstanceState, String category,
            ArrayList<ComponentName> options, ComponentName failedComponent) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.Theme_DeviceDefault_Light_Dialog_Alert);

        if ((options == null || options.size() == 0) && failedComponent == null) {
            Log.e(TAG, "No components passed in.");
            finish();
            return;
        }

        mCategory = category;

        final NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
        mCardEmuManager = CardEmulation.getInstance(adapter);
        AlertController.AlertParams ap = mAlertParams;

        final ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        mIconSize = am.getLauncherLargeIconSize();

        // Three cases:
        // 1. Failed component and no alternatives: just an OK box
        // 2. Failed component and alternatives: pick alternative
        // 3. No failed component and alternatives: pick alternative
        PackageManager pm = getPackageManager();

        CharSequence applicationLabel = "unknown";
        if (failedComponent != null) {
            try {
                ApplicationInfo info = pm.getApplicationInfo(failedComponent.getPackageName(), 0);
                applicationLabel = info.loadLabel(pm);
            } catch (NameNotFoundException e) {
            }

        }
        if (options.size() == 0 && failedComponent != null) {
            ap.mTitle = "";
            ap.mMessage = "This transaction couldn't be completed with " + applicationLabel + ".";
            ap.mPositiveButtonText = "OK";
            setupAlert();
        } else {
            mListAdapter = new ListAdapter(this, options);
            if (failedComponent != null) {
                ap.mTitle = "Couldn't use " + applicationLabel + ".";
            } else {
                if (CardEmulation.CATEGORY_PAYMENT.equals(category)) {
                    ap.mTitle = "Pay with";
                } else {
                    ap.mTitle = "Complete tap with";
                }
            }
            ap.mView = getLayoutInflater().inflate(com.android.nfc.R.layout.cardemu_resolver, null);

            mListView = (ListView) ap.mView.findViewById(com.android.nfc.R.id.resolver_list);
            mListView.setAdapter(mListAdapter);
            mListView.setOnItemClickListener(this);

            setupAlert();
        }
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Intent intent = getIntent();
        ArrayList<ComponentName> components = intent.getParcelableArrayListExtra(EXTRA_COMPONENTS);
        String category = intent.getStringExtra(EXTRA_CATEGORY);
        ComponentName failedComponent = intent.getParcelableExtra(EXTRA_FAILED_COMPONENT);
        onCreate(savedInstanceState, category, components, failedComponent);
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        DisplayAppInfo info = (DisplayAppInfo) mListAdapter.getItem(position);
        mCardEmuManager.setDefaultForNextTap(info.component);
        Intent dialogIntent = new Intent(this, TapAgainDialog.class);
        dialogIntent.putExtra(TapAgainDialog.EXTRA_CATEGORY, mCategory);
        dialogIntent.putExtra(TapAgainDialog.EXTRA_COMPONENT, info.component);
        startActivity(dialogIntent);
        finish();
    }

    final class DisplayAppInfo {
        ComponentName component;
        CharSequence displayLabel;
        Drawable displayIcon;

        public DisplayAppInfo(ComponentName component, CharSequence label, Drawable icon) {
            this.component = component;
            displayIcon = icon;
            displayLabel = label;
        }
    }

    final class ListAdapter extends BaseAdapter {
        private final LayoutInflater mInflater;
        private List<DisplayAppInfo> mList;

        public ListAdapter(Context context, ArrayList<ComponentName> components) {
            mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            // For each component, get the corresponding app name and icon
            PackageManager pm = getPackageManager();
            mList = new ArrayList<DisplayAppInfo>();

            for (ComponentName component : components) {
                try {
                    ApplicationInfo appInfo = pm.getApplicationInfo(component.getPackageName(), 0);
                    CharSequence label = appInfo.loadLabel(pm);
                    Drawable icon = appInfo.loadIcon(pm);
                    DisplayAppInfo info = new DisplayAppInfo(component, label, icon);
                    mList.add(info);
                } catch (NameNotFoundException e) {
                    Log.e(TAG, "Could not load ApplicationInfo for " + component);
                }
            }
        }

        @Override
        public int getCount() {
            return mList.size();
        }

        @Override
        public Object getItem(int position) {
            return mList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view;
            if (convertView == null) {
                view = mInflater.inflate(
                        com.android.nfc.R.layout.cardemu_item, parent, false);
                final ViewHolder holder = new ViewHolder(view);
                view.setTag(holder);

                ViewGroup.LayoutParams lp = holder.icon.getLayoutParams();
                lp.width = lp.height = mIconSize;
            } else {
                view = convertView;
            }

            final ViewHolder holder = (ViewHolder) view.getTag();
            DisplayAppInfo appInfo = mList.get(position);
            holder.text.setText(appInfo.displayLabel);
            holder.icon.setImageDrawable(appInfo.displayIcon);
            return view;
        }
    }

    static class ViewHolder {
        public TextView text;
        public ImageView icon;

        public ViewHolder(View view) {
            text = (TextView) view.findViewById(com.android.nfc.R.id.applabel);
            icon = (ImageView) view.findViewById(com.android.nfc.R.id.appicon);
        }
    }
}