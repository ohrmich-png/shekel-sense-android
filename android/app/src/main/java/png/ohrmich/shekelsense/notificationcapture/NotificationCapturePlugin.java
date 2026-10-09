package png.ohrmich.shekelsense.notificationcapture;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Capacitor bridge for notification-based expense capture.
 *
 * JS API (via Capacitor.Plugins.NotificationCapture):
 *   isListenerEnabled() -> { enabled: boolean }
 *   openListenerSettings() -> void  (opens system notification-access settings)
 * Events:
 *   "transactionDetected"  { package, title, text, amount, merchant, cardLast4, detectedAt }
 *   "notificationUnparsed" { package, title, text, detectedAt, reason }
 *
 * Privacy: the native service ignores every package outside ALLOWED_PACKAGES
 * before any parsing happens. Nothing is uploaded anywhere — events go only
 * to the local WebView.
 */
@CapacitorPlugin(name = "NotificationCapture")
public class NotificationCapturePlugin extends Plugin {

    private static NotificationCapturePlugin instance;

    @Override
    public void load() {
        instance = this;
    }

    /** Called by NotificationCaptureService on the main thread. */
    static void dispatch(String pkg, String title, String text,
                         NotificationParser.ParseResult result) {
        NotificationCapturePlugin plugin = instance;
        if (plugin == null) return; // WebView not up yet — drop, never queue PII
        plugin.handleResult(pkg, title, text, result);
    }

    private void handleResult(String pkg, String title, String text,
                              NotificationParser.ParseResult result) {
        JSObject data = new JSObject();
        data.put("package", pkg);
        data.put("title", title == null ? "" : title);
        data.put("text", text == null ? "" : text);
        data.put("detectedAt", System.currentTimeMillis());

        if (result.incoming) {
            return; // money in — not an expense, skip silently (never misfile)
        }
        if (result.confident) {
            data.put("amount", result.amount);
            data.put("merchant", result.merchant);
            if (result.cardLast4 != null) data.put("cardLast4", result.cardLast4);
            notifyListeners("transactionDetected", data);
        } else {
            String reason = result.amount == null ? "no-amount" : "no-merchant";
            data.put("reason", reason);
            // partial data helps the review inbox pre-fill the add form
            if (result.amount != null) data.put("amount", result.amount);
            if (result.merchant != null) data.put("merchant", result.merchant);
            notifyListeners("notificationUnparsed", data);
        }
    }

    @PluginMethod
    public void isListenerEnabled(PluginCall call) {
        Context ctx = getContext();
        ComponentName cn = new ComponentName(ctx, NotificationCaptureService.class);
        String flat = cn.flattenToString();
        String shortFlat = cn.flattenToShortString();
        String enabled = Settings.Secure.getString(
                ctx.getContentResolver(), "enabled_notification_listeners");
        boolean isEnabled = enabled != null
                && (enabled.contains(flat) || enabled.contains(shortFlat));
        JSObject ret = new JSObject();
        ret.put("enabled", isEnabled);
        call.resolve(ret);
    }

    @PluginMethod
    public void openListenerSettings(PluginCall call) {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
        call.resolve();
    }
}
