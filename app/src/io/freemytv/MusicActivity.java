package io.freemytv;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;

/**
 * The Music screen: your Spotify account (MusicView). Opened from the music button at the top of the home screen. Like
 * Explore, it lives only while it's on screen: leaving it closes it and frees its images.
 */
public class MusicActivity extends Activity {
    private MusicView view;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new MusicView(this);
        setContentView(view);
        getWindow().setBackgroundDrawable(null);
    }

    @Override protected void onStop() {
        super.onStop();
        finish();
    }

    @Override protected void onDestroy() {
        if (view != null) view.destroy();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (view != null && view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }
}
