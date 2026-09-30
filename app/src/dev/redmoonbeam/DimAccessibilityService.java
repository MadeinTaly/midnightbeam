package dev.redmoonbeam;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/** Only lends its window to DimService, so the overlay can be drawn as an accessibility overlay. */
public class DimAccessibilityService extends AccessibilityService {
    static volatile DimAccessibilityService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        refresh();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        refresh();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private void refresh() {
        Compat.startForegroundService(this, new Intent(this, DimService.class).putExtra("refresh", true));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
