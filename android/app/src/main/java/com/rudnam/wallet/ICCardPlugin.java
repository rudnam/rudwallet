package com.rudnam.wallet;

import android.app.Activity;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.NfcF;
import android.provider.Settings;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.IOException;

/**
 * Reads a transit IC card's history (Suica, PASMO, ICOCA…) when it is held
 * to the phone, for the IC card import (web/src/native.ts). The card is
 * FeliCa: its history is service 090F of system 0003, one 16-byte block per
 * entry, newest first, readable without a key. The blocks go to the server
 * as they are; internal/wallet/importic.go makes sense of them.
 */
@CapacitorPlugin(name = "ICCard")
public class ICCardPlugin extends Plugin {
    /** Cards keep about 20 entries; reading stops at the first block they don't have. */
    private static final int MAX_BLOCKS = 20;
    private static final byte[] TRANSIT_SYSTEM = {0x00, 0x03};
    private static final byte[] HISTORY_SERVICE = {0x0f, 0x09}; // 090F, little-endian

    /** The read() waiting for a card, or null. */
    private PluginCall waiting;

    @PluginMethod
    public void status(PluginCall call) {
        NfcAdapter nfc = NfcAdapter.getDefaultAdapter(getContext());
        JSObject result = new JSObject();
        result.put("nfc", nfc != null);
        result.put("enabled", nfc != null && nfc.isEnabled());
        call.resolve(result);
    }

    /** Opens Android's NFC settings, to turn it on. */
    @PluginMethod
    public void openSettings(PluginCall call) {
        Intent intent = new Intent(Settings.ACTION_NFC_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
        call.resolve();
    }

    /** Waits for a card, and resolves with {idm, history: [hex, …]}. */
    @PluginMethod
    public void read(PluginCall call) {
        NfcAdapter nfc = NfcAdapter.getDefaultAdapter(getContext());
        if (nfc == null) {
            call.reject("This phone can't read cards", "no-nfc");
            return;
        }
        if (!nfc.isEnabled()) {
            call.reject("NFC is off", "nfc-off");
            return;
        }
        if (waiting != null) {
            waiting.reject("Cancelled", "cancelled");
        }
        waiting = call;
        listen(true);
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        if (waiting != null) {
            waiting.reject("Cancelled", "cancelled");
            waiting = null;
        }
        listen(false);
        call.resolve();
    }

    // Reader mode only works while the app is in front.
    @Override
    protected void handleOnPause() {
        listen(false);
    }

    @Override
    protected void handleOnResume() {
        if (waiting != null) listen(true);
    }

    private void listen(boolean on) {
        Activity activity = getActivity();
        NfcAdapter nfc = NfcAdapter.getDefaultAdapter(getContext());
        if (activity == null || nfc == null) return;
        activity.runOnUiThread(() -> {
            if (on) {
                nfc.enableReaderMode(activity, this::onTag,
                        NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null);
            } else {
                nfc.disableReaderMode(activity);
            }
        });
    }

    private void onTag(Tag tag) {
        PluginCall call = waiting;
        if (call == null) return;
        NfcF card = NfcF.get(tag);
        if (card == null) {
            call.reject("Not a transit IC card", "not-transit");
            finish(call);
            return;
        }
        try {
            card.connect();
            byte[] idm = poll(card);
            if (idm == null) {
                call.reject("Not a transit IC card", "not-transit");
                return;
            }
            JSArray history = new JSArray();
            for (int i = 0; i < MAX_BLOCKS; i++) {
                byte[] block = readBlock(card, idm, i);
                if (block == null) break;
                history.put(hex(block, 0, 16));
            }
            JSObject result = new JSObject();
            result.put("idm", hex(idm, 0, 8));
            result.put("history", history);
            call.resolve(result);
        } catch (IOException e) {
            // Usually the card was moved away too soon.
            call.reject("The card was moved away before it was read", "lost");
        } finally {
            try {
                card.close();
            } catch (IOException ignored) {
            }
            finish(call);
        }
    }

    private void finish(PluginCall call) {
        if (waiting == call) {
            waiting = null;
            listen(false);
        }
    }

    /** Polls for the transit system, and returns its IDm, or null if the card hasn't one. */
    private static byte[] poll(NfcF card) throws IOException {
        byte[] res = card.transceive(new byte[] {6, 0x00, TRANSIT_SYSTEM[0], TRANSIT_SYSTEM[1], 0x00, 0x00});
        if (res == null || res.length < 10 || res[1] != 0x01) return null;
        byte[] idm = new byte[8];
        System.arraycopy(res, 2, idm, 0, 8);
        return idm;
    }

    /** Reads one history block (Read Without Encryption), or null past the last. */
    private static byte[] readBlock(NfcF card, byte[] idm, int n) throws IOException {
        byte[] cmd = new byte[16];
        cmd[0] = 16;
        cmd[1] = 0x06;
        System.arraycopy(idm, 0, cmd, 2, 8);
        cmd[10] = 1; // one service
        cmd[11] = HISTORY_SERVICE[0];
        cmd[12] = HISTORY_SERVICE[1];
        cmd[13] = 1; // one block
        cmd[14] = (byte) 0x80; // two-byte block list element, service 0
        cmd[15] = (byte) n;
        byte[] res = card.transceive(cmd);
        // [length, 07, IDm×8, status 1, status 2, blocks, data×16]
        if (res == null || res.length < 29 || res[1] != 0x07 || res[10] != 0) return null;
        byte[] block = new byte[16];
        System.arraycopy(res, 13, block, 0, 16);
        return block;
    }

    private static String hex(byte[] b, int from, int n) {
        StringBuilder s = new StringBuilder();
        for (int i = from; i < from + n; i++) s.append(String.format("%02X", b[i]));
        return s.toString();
    }
}
