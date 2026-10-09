package png.ohrmich.shekelsense;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

import png.ohrmich.shekelsense.notificationcapture.NotificationCapturePlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NotificationCapturePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
