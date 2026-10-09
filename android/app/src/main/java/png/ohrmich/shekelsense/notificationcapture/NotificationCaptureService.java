package png.ohrmich.shekelsense.notificationcapture;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * Listens for push notifications from Israeli banks / card companies and
 * forwards candidate purchase notifications to the Capacitor plugin.
 *
 * Privacy: packages outside NotificationParser.ALLOWED_PACKAGES are dropped
 * immediately — no text is read, parsed, stored, or transmitted.
 */
public class NotificationCaptureService extends NotificationListenerService {

    private static final String TAG = "ShekelSenseNL";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            String pkg = sbn.getPackageName();
            if (!NotificationParser.ALLOWED_PACKAGES.contains(pkg)) {
                return; // not a bank/card app — ignore entirely
            }
            Notification notif = sbn.getNotification();
            if (notif == null) return;
            Bundle extras = notif.extras;
            if (extras == null) return;

            String title = extras.getString(Notification.EXTRA_TITLE);
            String text = asText(extras, Notification.EXTRA_TEXT);
            // Prefer bigText when present — it holds the full message.
            String bigText = asText(extras, Notification.EXTRA_BIG_TEXT);
            if (bigText != null && !bigText.isEmpty()) text = bigText;

            if ((title == null || title.isEmpty()) && (text == null || text.isEmpty())) {
                return;
            }

            NotificationParser.ParseResult result =
                    NotificationParser.parse(pkg, title, text, null);
            NotificationCapturePlugin.dispatch(pkg, title, text, result);
        } catch (Exception e) {
            Log.w(TAG, "notification handling failed", e);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // nothing to do
    }

    private static String asText(Bundle extras, String key) {
        try {
            CharSequence cs = extras.getCharSequence(key);
            return cs == null ? null : cs.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
