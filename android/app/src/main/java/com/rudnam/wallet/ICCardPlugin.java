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
 *
 * Cards are read two ways: read(), after the user asks to, or by tapping a
 * card to the phone when RudWallet isn't waiting for one. Android then
 * offers RudWallet for it (the TECH_DISCOVERED filter in the manifest), and
 * the card is read from that intent and sent as a "tapped" event, kept
 * until the web app listens, which opens the import with it.
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
        try {
            call.resolve(readCard(tag));
        } catch (ReadError e) {
            call.reject(e.getMessage(), e.code);
        } finally {
            finish(call);
        }
    }

    // A card tapped while the app was closed starts it with the tap's intent;
    // one tapped while it is open (and not reading) brings a new intent.
    @Override
    public void load() {
        Activity activity = getActivity();
        if (activity != null) tapped(activity.getIntent());
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        tapped(intent);
    }

    @SuppressWarnings("deprecation") // getParcelableExtra(name, class) needs API 33
    private void tapped(Intent intent) {
        if (intent == null || !NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction())) return;
        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        intent.setAction(Intent.ACTION_MAIN); // handled: not again if the activity is recreated
        if (tag == null) return;
        // Read at once, off the main thread, while the card is still there.
        new Thread(() -> {
            JSObject event;
            try {
                event = readCard(tag);
            } catch (ReadError e) {
                event = new JSObject();
                event.put("error", e.code);
            }
            notifyListeners("tapped", event, true);
        }).start();
    }

    /** Why a card couldn't be read: code is not-transit or lost. */
    private static class ReadError extends Exception {
        final String code;

        ReadError(String message, String code) {
            super(message);
            this.code = code;
        }
    }

    /** Reads a card's IDm and history: {idm, history: [hex, …]}. */
    private static JSObject readCard(Tag tag) throws ReadError {
        NfcF card = NfcF.get(tag);
        if (card == null) throw new ReadError("Not a transit IC card", "not-transit");
        try {
            card.connect();
            byte[] idm = poll(card);
            if (idm == null) throw new ReadError("Not a transit IC card", "not-transit");
            JSArray history = new JSArray();
            for (int i = 0; i < MAX_BLOCKS; i++) {
                byte[] block = readBlock(card, idm, i);
                if (block == null) break;
                history.put(hex(block, 0, 16));
            }
            JSObject result = new JSObject();
            result.put("idm", hex(idm, 0, 8));
            result.put("history", history);
            return result;
        } catch (IOException e) {
            // Usually the card was moved away too soon.
            throw new ReadError("The card was moved away before it was read", "lost");
        } finally {
            try {
                card.close();
            } catch (IOException ignored) {
            }
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
