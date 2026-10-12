package app.exteraless.agent;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.utils.ViewOutlineProviderImpl;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.FragmentFloatingButton;

import app.exteraless.player.Spring;

/**
 * The agent FAB, floating to the left of the pencil. The squircle, the springs,
 * and the morph physics are taken from the MD3 player (MorphButton/Spring).
 */
public class AgentFab extends FragmentFloatingButton implements AgentWatcher.Listener {

    public static final String SPARK_PATH =
            "M12 1.8l2.59 7.61 7.61 2.59-7.61 2.59L12 22.2l-2.59-7.61L1.8 12l7.61-2.59z";

    private final AgentSparkDrawable spark = new AgentSparkDrawable();
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Spring badgeSpring = new Spring(0, 600f, 0.55f, 0.01f);
    private final Spring pulse = new Spring(0, 480f, 0.28f, 0.01f);
    private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();

    private int badgeCount;
    private boolean generating;
    private long lastFrame;
    private final Theme.ResourcesProvider rp;

    public AgentFab(@NonNull Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider);
        this.rp = resourcesProvider;
        // MD3 squircle instead of a circle
        setOutlineProvider(ViewOutlineProviderImpl.boundsWithPaddingRoundRect(0, dp(16)));
        imageView.setImageDrawable(spark);
        setClipToOutline(false);
        badgeTextPaint.setTextAlign(Paint.Align.CENTER);
        badgeTextPaint.setTextSize(dp(10));
        badgeTextPaint.setTypeface(AndroidUtilities.bold());
    }

    public void setGenerating(boolean value, boolean animated) {
        if (generating == value) {
            return;
        }
        generating = value;
        if (value) {
            pulse.snap(1f);
            spark.spin.target = 1f;
        } else {
            spark.spin.target = 0f;
            if (!animated) {
                pulse.snap(0f);
            } else {
                pulse.target = 0f;
            }
        }
        postInvalidateOnAnimation();
    }

    public void setBadge(int count, boolean animated) {
        if (badgeCount == count) {
            return;
        }
        badgeCount = count;
        if (!animated) {
            badgeSpring.snap(count > 0 ? 1f : 0f);
        } else {
            badgeSpring.target = count > 0 ? 1f : 0f;
        }
        postInvalidateOnAnimation();
    }

    public boolean isGenerating() {
        return generating;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        AgentWatcher.addListener(this);
        setBadge(AgentWatcher.watchesCount(), false);
        setGenerating(AgentWatcher.isBusy(), false);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        AgentWatcher.removeListener(this);
    }

    @Override
    public void onWatchListChanged() {
        post(() -> setBadge(AgentWatcher.watchesCount(), true));
    }

    @Override
    public void onBusyChanged(boolean busy) {
        post(() -> setGenerating(busy, true));
    }

    @Override
    public void updateColors() {
        super.updateColors();
        int iconColor = Theme.getColor(Theme.key_chats_actionIcon, rp);
        int badgeBg = Theme.getColor(Theme.key_featuredStickers_addButtonPressed, rp);
        spark.setColor(iconColor);
        badgePaint.setColor(ColorUtils.setAlphaComponent(badgeBg, 255));
        badgeTextPaint.setColor(Theme.getColor(Theme.key_chats_actionIcon, rp));
        pulsePaint.setColor(iconColor);
        pulsePaint.setStyle(Paint.Style.STROKE);
        pulsePaint.setStrokeWidth(dp(2));
        postInvalidate();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        super.draw(canvas);

        long now = SystemClock.elapsedRealtime();
        float dt = lastFrame == 0 ? 0.016f : (now - lastFrame) / 1000f;
        lastFrame = now;
        boolean animating = badgeSpring.step(dt);
        boolean pulsing = pulse.step(dt);

        float w = getWidth();
        float h = getHeight();

        // expanding pulse ring while the agent is thinking
        float p = generating ? Math.max(pulse.value, (float) (0.5 + 0.5 * Math.sin(now / 260.0))) : pulse.value;
        if (p > 0f) {
            float inset = dp(6) * (1f - p);
            tmpRect.set(inset, inset, w - inset, h - inset);
            pulsePaint.setAlpha((int) (90 * p));
            canvas.drawRoundRect(tmpRect, dp(16), dp(16), pulsePaint);
        }

        // watch badge
        float b = Math.max(0f, Math.min(1f, badgeSpring.value));
        if (b > 0f && badgeCount > 0) {
            float radius = dp(9) * (0.55f + 0.45f * b);
            float cx = org.telegram.messenger.LocaleController.isRTL ? dp(10) : w - dp(10);
            float cy = dp(10);
            badgePaint.setAlpha((int) (255 * b));
            canvas.drawCircle(cx, cy, radius, badgePaint);
            badgeTextPaint.setAlpha((int) (255 * b));
            String text = badgeCount > 9 ? "9+" : String.valueOf(badgeCount);
            canvas.drawText(text, cx, cy - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f, badgeTextPaint);
        }

        if (animating || pulsing || generating) {
            postInvalidateOnAnimation();
        } else {
            lastFrame = 0;
        }
    }

    /**
     * The four-point MD3 spark. Breathes with the same spring physics as the
     * player icons: pops in, gently spinning while the agent is thinking.
     */
    private final class AgentSparkDrawable extends Drawable {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Spring appear = new Spring(0, 520f, 0.6f, 0.01f);
        private final Spring spin = new Spring(0, 260f, 0.3f, 0.02f);

        AgentSparkDrawable() {
            appear.target = 1f;
        }

        void setColor(int value) {
            paint.setColor(value);
            invalidateSelf();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Path parsed = PathUtils.parse(SPARK_PATH);
            float dt = 0.016f;
            boolean animating = appear.step(dt) | spin.step(dt);
            Rect bounds = getBounds();
            float cx = bounds.centerX();
            float cy = bounds.centerY();
            float scale = bounds.width() > 0 ? (bounds.width() / 24f) : 1f;
            scale *= (0.7f + 0.3f * appear.value);
            float angle = spin.value * 45f;
            canvas.save();
            canvas.translate(cx, cy);
            canvas.rotate(angle);
            canvas.scale(scale, scale);
            canvas.translate(-12f, -12f);
            paint.setAlpha(255);
            canvas.drawPath(parsed, paint);
            canvas.restore();
            if (animating) {
                invalidateSelf();
            }
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return dp(24);
        }

        @Override
        public int getIntrinsicHeight() {
            return dp(24);
        }
    }

    private static final class PathUtils {
        private static final java.util.HashMap<String, Path> cache = new java.util.HashMap<>();

        static Path parse(String d) {
            Path path = cache.get(d);
            if (path == null) {
                path = androidx.core.graphics.PathParser.createPathFromPathData(d);
                cache.put(d, path);
            }
            return path;
        }
    }
}
