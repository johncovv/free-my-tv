package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;

import io.freemytv.Cards.Title;

/**
 * A title's page (DetailsView): what it is, who made it, the synopsis and where to watch it. Every movie or show on the
 * home screen, in Explore and in search opens here first; the streaming app opens from its "Watch on" buttons. Like
 * Explore, it lives only while it's on screen.
 */
public class DetailsActivity extends Activity {
    private DetailsView view;

    static void open(Context c, int id, String kind, String title, String backdrop, String poster) {
        c.startActivity(new Intent(c, DetailsActivity.class).putExtra("id", id).putExtra("kind", kind)
                .putExtra("title", title).putExtra("backdrop", backdrop).putExtra("poster", poster)
                .addFlags(c instanceof Activity ? 0 : Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    static void open(Context c, Title t) {
        open(c, t.id, t.kind, t.title, t.backdrop, t.poster);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        Intent i = getIntent();
        Title t = new Title();
        t.id = i.getIntExtra("id", 0);
        t.kind = i.getStringExtra("kind");
        t.title = i.getStringExtra("title");
        t.backdrop = i.getStringExtra("backdrop");
        t.poster = i.getStringExtra("poster");
        if (t.id == 0 || t.kind == null) {
            finish();
            return;
        }
        view = new DetailsView(this, t);
        setContentView(view);
        getWindow().setBackgroundDrawable(null);
    }

    @Override protected void onStop() {
        super.onStop();
        // Gone once a streaming app opens (or Home is pressed): its images go with it, and Explore's behind it too.
        // With Back it's already finishing and Explore is showing again.
        if (!isFinishing()) ExploreActivity.closeBehind();
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
