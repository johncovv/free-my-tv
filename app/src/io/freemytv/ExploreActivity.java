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

    /** Opens a title's page on top of Explore (which stays for Back). */
    void openDetails(Cards.Title t) {
        toDetails = true;
        DetailsActivity.open(this, t);
    }

    @Override protected void onResume() {
        super.onResume();
        toDetails = false;
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
