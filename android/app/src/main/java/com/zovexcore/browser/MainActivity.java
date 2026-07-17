package com.zovexcore.browser;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(BrowserTabsPlugin.class);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onBackPressed() {
        if (BrowserTabsPlugin.handleBackPressedStatic()) {
            return;
        }
        super.onBackPressed();
    }
}
