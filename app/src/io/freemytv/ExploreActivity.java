package io.freemytv;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;

/**
 * The Explore screen: recommendations for you, new releases, genres and search (ExploreView). Opened from the home
 * screen (the compass button at the top, or the remote's search key). It lives only while it's on screen: leaving it
 * closes it and frees its images, so the home screen stays as light as before.
 */
public class ExploreActivity extends Activity {
    static final String EXTRA_SEARCH = "search";
    private ExploreView view;
    private boolean toDetails;   // a title's page is opening on top: stay, so Back returns here

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new ExploreView(this, getIntent().getBooleanExtra(EXTRA_SEARCH, false));
        setContentView(view);
        getWindow().setBackgroundDrawable(null);
    }

    private static java.lang.ref.WeakReference<ExploreActivity> waiting;

    /** Opens a title's page on top of Explore (which stays for Back). */
    void openDetails(Cards.Title t) {
        toDetails = true;
        waiting = new java.lang.ref.WeakReference<>(this);
        DetailsActivity.open(this, t);
    }

    /** The title's page was left for an app or Home (not with Back): Explore behind it goes too. */
    static void closeBehind() {
        ExploreActivity e = waiting == null ? null : waiting.get();
        waiting = null;
        if (e != null && e.toDetails && !e.isFinishing()) e.finish();
    }

    /** The TV's "Allow Free My TV to record audio?" prompt (voice search); the answer goes back to the view. */
    void askMicrophone() {
        requestPermissions(new String[] {android.Manifest.permission.RECORD_AUDIO}, 1);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (view != null) view.micAllowed(results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED);
    }

    @Override protected void onPause() {
        if (view != null) view.stopMic();   // the microphone never stays on behind another screen
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        toDetails = false;
        waiting = null;
    }

    @Override protected void onStop() {
        super.onStop();
        if (!toDetails) finish();   // not kept in the background (a streaming app, Home): its images go with it
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
