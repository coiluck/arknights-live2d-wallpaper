package moe.coiluck.spinewallpaper;

import android.app.WallpaperManager;
import android.os.Bundle;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.badlogic.gdx.backends.android.AndroidLiveWallpaperService;

/** Runs the same CharacterScene as MainActivity, but as a live wallpaper. */
public class LiveWallpaper extends AndroidLiveWallpaperService {
    private CharacterScene scene;

    @Override
    public void onCreateApplication() {
        super.onCreateApplication();
        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        // Sensors are unused; turning them off saves battery while the wallpaper is visible.
        config.useAccelerometer = false;
        config.useCompass = false;
        scene = new CharacterScene();
        initialize(scene, config);
    }

    @Override
    public Engine onCreateEngine() {
        return new AndroidWallpaperEngine() {
            // Launchers report taps on empty home-screen space as this command, not always as touch events.
            @Override
            public Bundle onCommand(String action, int x, int y, int z, Bundle extras, boolean resultRequested) {
                if (WallpaperManager.COMMAND_TAP.equals(action) && scene != null) {
                    Gdx.app.postRunnable(() -> scene.onTap(x, y));
                }
                return super.onCommand(action, x, y, z, extras, resultRequested);
            }
        };
    }
}
