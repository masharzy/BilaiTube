package org.schabi.newpipe;

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.ComponentActivity;

import org.schabi.newpipe.bilai.BilaiShell;

public final class MainActivity extends ComponentActivity {
    // Shared by the existing playback and data layers.
    public static final boolean DEBUG = BuildConfig.DEBUG;
    private BilaiShell shell;

    @Override
    protected void onCreate(final Bundle state) {
        super.onCreate(state);
        shell = new BilaiShell(this);
        setContentView(shell.getView());
    }

    @Override
    protected void onDestroy() {
        shell.dispose();
        super.onDestroy();
    }

    @Override
    public void onPictureInPictureModeChanged(final boolean inPictureInPictureMode,
                                              final android.content.res.Configuration config) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, config);
        if (shell != null) shell.setPictureInPicture(inPictureInPictureMode);
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        shell.handleIntent(intent);
    }
}
