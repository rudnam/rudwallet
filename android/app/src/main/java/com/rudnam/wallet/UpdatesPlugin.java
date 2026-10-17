package com.rudnam.wallet;

import android.content.pm.PackageManager;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Tells the web app which app installed this one (web/src/updates.ts): a copy
 * from the Play Store is updated by the Play Store, and an APK from the site
 * can't be installed over it.
 */
@CapacitorPlugin(name = "Updates")
public class UpdatesPlugin extends Plugin {
    @PluginMethod
    public void installer(PluginCall call) {
        JSObject result = new JSObject();
        result.put("installer", installerPackage());
        call.resolve(result);
    }

    private String installerPackage() {
        PackageManager pm = getContext().getPackageManager();
        String self = getContext().getPackageName();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return pm.getInstallSourceInfo(self).getInstallingPackageName();
            }
            return pm.getInstallerPackageName(self);
        } catch (Exception e) {
            return null;
        }
    }
}
