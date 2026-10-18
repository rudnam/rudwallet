package com.rudnam.wallet;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;
import com.rudnam.wallet.notify.NotificationsPlugin;
import com.rudnam.wallet.widget.WidgetsPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Before super.onCreate, which starts the bridge.
        registerPlugin(WidgetsPlugin.class);
        registerPlugin(NotificationsPlugin.class);
        registerPlugin(UpdatesPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
