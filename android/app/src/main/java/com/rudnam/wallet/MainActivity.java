package com.rudnam.wallet;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;
import com.rudnam.wallet.widget.WidgetsPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(WidgetsPlugin.class); // before super.onCreate, which starts the bridge
        super.onCreate(savedInstanceState);
    }
}
