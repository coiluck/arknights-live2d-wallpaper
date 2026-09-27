package moe.coiluck.spinewallpaper;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g2d.TextureAtlas.TextureAtlasData;
import com.badlogic.gdx.input.GestureDetector;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.FloatArray;
import com.esotericsoftware.spine.Animation;
import com.esotericsoftware.spine.AnimationState;
import com.esotericsoftware.spine.AnimationState.TrackEntry;
import com.esotericsoftware.spine.AnimationStateData;
import com.esotericsoftware.spine.Skeleton;
import com.esotericsoftware.spine.SkeletonBinary;
import com.esotericsoftware.spine.SkeletonData;
import com.esotericsoftware.spine.SkeletonJson;
import com.esotericsoftware.spine.SkeletonRenderer;
import com.esotericsoftware.spine.Slot;
import com.esotericsoftware.spine.attachments.Attachment;
import com.esotericsoftware.spine.attachments.MeshAttachment;
import com.esotericsoftware.spine.attachments.RegionAttachment;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * Draws assets/background/ (any png/jpg) filling the screen, and the Spine character found in
 * assets/character/ on top of it. The character loops Idle, plays Interact when the character
 * itself is tapped, and plays Special now and then.
 */
public class CharacterScene extends ApplicationAdapter {
    // --- Tweak these to adjust the look ---
    /** Character size. 1.0 = the whole character just fits the screen. */
    private static final float CHARACTER_SCALE = 1.5f;
    /** Vertical position of the character's center. 0 = bottom of screen, 0.5 = middle, 1 = top. */
    private static final float CHARACTER_CENTER_Y = 0.25f;
    /** Special plays after Idle has run for a random time within this range (seconds). */
    private static final float SPECIAL_MIN_SECONDS = 10f;
    private static final float SPECIAL_MAX_SECONDS = 40f;

    private OrthographicCamera camera;
    private PolygonSpriteBatch batch;
    private SkeletonRenderer renderer;
    private Texture background;
    private TextureAtlas atlas;
    private Skeleton skeleton;
    private AnimationState state;
    private Animation idle, interact, special;
    private float secondsUntilSpecial;
    private final Vector2 boundsOffset = new Vector2();
    private final Vector2 boundsSize = new Vector2();
    private final Vector3 touchPoint = new Vector3();
    private final FloatArray hitVertices = new FloatArray();

    @Override
    public void create() {
        camera = new OrthographicCamera();
        batch = new PolygonSpriteBatch();
        renderer = new SkeletonRenderer();
        // Arknights textures are exported with premultiplied alpha; without this, edges turn dark.
        renderer.setPremultipliedAlpha(true);

        FileHandle backgroundFile = findFile("background", ".png", ".jpg", ".jpeg");
        if (backgroundFile != null) {
            background = new Texture(backgroundFile);
            background.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        }

        atlas = loadAtlas(findFile("character", ".atlas", ".atlas.txt"));
        FileHandle skeletonFile = findFile("character", ".skel", ".skel.txt", ".json", ".json.txt");
        SkeletonData data = skeletonFile.name().contains(".json")
                ? new SkeletonJson(atlas).readSkeletonData(skeletonFile)
                : new SkeletonBinary(atlas).readSkeletonData(skeletonFile);
        idle = data.findAnimation("Idle");
        interact = data.findAnimation("Interact");
        special = data.findAnimation("Special");

        skeleton = new Skeleton(data);
        AnimationStateData stateData = new AnimationStateData(data);
        stateData.setDefaultMix(0.3f); // crossfade so animation switches don't pop
        state = new AnimationState(stateData);
        state.setAnimation(0, idle, true);
        scheduleSpecial();

        // Measure the first frame of Idle (at scale 1, origin 0) to know the character's size.
        state.apply(skeleton);
        skeleton.updateWorldTransform();
        skeleton.getBounds(boundsOffset, boundsSize, new FloatArray());

        // tap() only fires for a short touch without movement, so home-screen swipes are ignored.
        Gdx.input.setInputProcessor(new GestureDetector(new GestureDetector.GestureAdapter() {
            @Override
            public boolean tap(float x, float y, int count, int button) {
                onTap(x, y);
                return true;
            }
        }));
    }

    /** Returns the first file in assets/{dir}/ whose name ends with one of the extensions, or null. */
    private static FileHandle findFile(String dir, String... extensions) {
        for (FileHandle file : Gdx.files.internal(dir).list()) {
            String name = file.name().toLowerCase();
            for (String extension : extensions) {
                if (name.endsWith(extension)) return file;
            }
        }
        return null;
    }

    /** Loads an atlas, accepting page names URL-encoded by the download tool (e.g. "%23" for "#"). */
    private static TextureAtlas loadAtlas(FileHandle atlasFile) {
        TextureAtlasData data = new TextureAtlasData(atlasFile, atlasFile.parent(), false);
        for (TextureAtlasData.Page page : data.getPages()) {
            if (!page.textureFile.exists()) {
                try {
                    page.textureFile = atlasFile.parent().child(URLDecoder.decode(page.textureFile.name(), "UTF-8"));
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return new TextureAtlas(data);
    }

    /**
     * Plays Interact if (x, y) is on the character; taps elsewhere do nothing.
     * (x, y) are screen pixels with y pointing down. Must be called on the render thread.
     */
    public void onTap(float x, float y) {
        camera.unproject(touchPoint.set(x, y, 0));
        if (!hits(skeleton, touchPoint.x, touchPoint.y, hitVertices)) return;
        playInteract();
    }

    /** Returns true if (x, y) in skeleton world coordinates lies on any polygon the skeleton currently draws. */
    static boolean hits(Skeleton skeleton, float x, float y, FloatArray vertices) {
        for (Slot slot : skeleton.getDrawOrder()) {
            Attachment attachment = slot.getAttachment();
            if (attachment == null || slot.getColor().a == 0) continue;
            if (attachment instanceof RegionAttachment) {
                float[] v = vertices.setSize(8);
                ((RegionAttachment) attachment).computeWorldVertices(slot.getBone(), v, 0, 2);
                // The quad's corners are in order, so it splits into triangles 0-1-2 and 0-2-3.
                if (inTriangle(v, 0, 2, 4, x, y) || inTriangle(v, 0, 4, 6, x, y)) return true;
            } else if (attachment instanceof MeshAttachment) {
                MeshAttachment mesh = (MeshAttachment) attachment;
                int length = mesh.getWorldVerticesLength();
                float[] v = vertices.setSize(length);
                mesh.computeWorldVertices(slot, 0, length, v, 0, 2);
                short[] triangles = mesh.getTriangles();
                for (int i = 0; i < triangles.length; i += 3) {
                    if (inTriangle(v, triangles[i] * 2, triangles[i + 1] * 2, triangles[i + 2] * 2, x, y)) return true;
                }
            }
        }
        return false;
    }

    /** Point-in-triangle test; a, b, c are indices of each corner's x in v (y follows at +1). */
    private static boolean inTriangle(float[] v, int a, int b, int c, float x, float y) {
        float d1 = (x - v[b]) * (v[a + 1] - v[b + 1]) - (v[a] - v[b]) * (y - v[b + 1]);
        float d2 = (x - v[c]) * (v[b + 1] - v[c + 1]) - (v[b] - v[c]) * (y - v[c + 1]);
        float d3 = (x - v[a]) * (v[c + 1] - v[a + 1]) - (v[c] - v[a]) * (y - v[a + 1]);
        boolean hasNegative = d1 < 0 || d2 < 0 || d3 < 0;
        boolean hasPositive = d1 > 0 || d2 > 0 || d3 > 0;
        return !(hasNegative && hasPositive);
    }

    /** Plays Interact once, then returns to Idle. */
    private void playInteract() {
        if (interact == null) return;
        TrackEntry current = state.getCurrent(0);
        // Ignore taps while Interact is running; the wallpaper can receive one tap as both a touch and a command.
        if (current != null && current.getAnimation() == interact) return;
        Gdx.app.log("CharacterScene", "Interact");
        playOnce(interact);
    }

    private void playOnce(Animation animation) {
        state.setAnimation(0, animation, false);
        state.addAnimation(0, idle, true, 0);
        scheduleSpecial();
    }

    private void scheduleSpecial() {
        secondsUntilSpecial = MathUtils.random(SPECIAL_MIN_SECONDS, SPECIAL_MAX_SECONDS);
    }

    @Override
    public void resize(int width, int height) {
        // Work in screen pixels; the character is scaled and moved to fit.
        camera.setToOrtho(false, width, height);
        camera.update();

        float scale = Math.min(width / boundsSize.x, height / boundsSize.y) * CHARACTER_SCALE;
        skeleton.setScale(scale, scale);
        skeleton.setPosition(
                width / 2f - (boundsOffset.x + boundsSize.x / 2) * scale,
                height * CHARACTER_CENTER_Y - (boundsOffset.y + boundsSize.y / 2) * scale);
    }

    @Override
    public void render() {
        float delta = Gdx.graphics.getDeltaTime();
        TrackEntry current = state.getCurrent(0);
        if (special != null && current != null && current.getAnimation() == idle) {
            secondsUntilSpecial -= delta;
            if (secondsUntilSpecial <= 0) {
                Gdx.app.log("CharacterScene", "Special");
                playOnce(special);
            }
        }
        state.update(delta);
        state.apply(skeleton);
        skeleton.updateWorldTransform();

        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        if (background != null) drawBackgroundCover();
        renderer.draw(batch, skeleton);
        batch.end();
    }

    /** Draws the background scaled to cover the whole screen, cropping the overflow evenly. */
    private void drawBackgroundCover() {
        float screenW = camera.viewportWidth, screenH = camera.viewportHeight;
        float scale = Math.max(screenW / background.getWidth(), screenH / background.getHeight());
        float w = background.getWidth() * scale, h = background.getHeight() * scale;
        batch.disableBlending(); // the background is opaque
        batch.draw(background, (screenW - w) / 2, (screenH - h) / 2, w, h);
        batch.enableBlending();
    }

    @Override
    public void dispose() {
        batch.dispose();
        atlas.dispose();
        if (background != null) background.dispose();
    }
}
