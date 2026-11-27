package local.jiege.hook.quickruler;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.hook.filterpanel.ExpandedFilterMenu;
import local.jiege.hook.filterpanel.LegacyFilterPanel;
import local.jiege.settings.ModuleSettings;

/**
 * The quick ruler of the master viewfinder: a ruler right above the filter entry button, centred on it, for the
 * exposure compensation of the gear in use. It is a child of the activity's content and is shown only while the
 * viewfinder is the whole screen: the master mode, the entry button fully shown, and no panel open. Every frame the
 * check runs before drawing, so the ruler is gone in the frame a panel starts to open, with no transition.
 *
 * The camera keeps its controls where they are on the phone's body and turns only their icons when the screen
 * turns; the ruler does the same. Its place, its drags and its lanes are worked out in the phone's natural
 * (upright) frame and only the result is mapped onto the screen: the ruler is turned with the screen so it stays
 * along the same edge, and its two texts are turned back so they read upright.
 *
 * Long press on the unit label and drag to move the ruler anywhere; on letting go it snaps to the nearer of the
 * two edge lanes (the button's column and its mirror on the other side) with a short animation. The lane and the
 * height are kept apart for upright and sideways.
 */
public final class QuickRuler {
    private static final String TAG = "QuickRuler";
    private static final String BUTTON = "com.oplus.camera.ui.effectcontainer.MenuRightButton";
    private static final String ENTRY = "camera_menu_right_enter_button";
    /** Gap between the unit label under the spread ruler and the entry button. */
    private static final float GAP_DP = 8f;
    private static final long SNAP_MS = 260;
    private static final long SYNC_MS = 150;
    /** The camera's own preferences file holding where the ruler was put. */
    private static final String PREFS = "jiege_quick_ruler";
    private static final String PORTRAIT_OFFSET = "offset_dp";
    private static final String PORTRAIT_LEFT = "left";
    private static final String LANDSCAPE_OFFSET = "offset_landscape_dp";
    private static final String LANDSCAPE_LEFT = "left_landscape";

    private static final Map<View, Attached> attached = Collections.synchronizedMap(new WeakHashMap<>());

    private QuickRuler() {}

    public static void installCamera(ClassLoader classLoader) {
        Log.guard(TAG, "entry button", () -> {
            XposedHelpers.findAndHookMethod(BUTTON, classLoader, "onAttachedToWindow", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    attach((View) param.thisObject);
                }
            });
        });
    }

    private static void attach(View button) {
        try {
            if (!Settings.get(ModuleSettings.QUICK_EV_RULER)) return;
            if (button.getId() <= 0 || !ENTRY.equals(button.getResources().getResourceEntryName(button.getId()))) return;
            Activity activity = activityOf(button.getContext());
            if (activity == null || attached.containsKey(button)) return;
            ViewGroup content = activity.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout)) return;
            float density = button.getResources().getDisplayMetrics().density;
            QuickRulerView ruler = new QuickRulerView(button.getContext(), new EvBinding(activity));
            ruler.setVisibility(View.GONE);
            content.addView(ruler, new FrameLayout.LayoutParams(
                Math.round(QuickRulerView.WIDTH_DP * density), Math.round(QuickRulerView.HEIGHT_DP * density)));
            Attached state = new Attached(activity, button, content, ruler, density);
            attached.put(button, state);
            content.getViewTreeObserver().addOnPreDrawListener(state);
            button.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) {}

                @Override public void onViewDetachedFromWindow(View view) {
                    view.removeOnAttachStateChangeListener(this);
                    detach(view);
                }
            });
            Log.i(TAG, "attached");
        } catch (Throwable error) {
            Log.e(TAG, "attach failed", error);
        }
    }

    private static void detach(View button) {
        Attached state = attached.remove(button);
        if (state == null) return;
        state.stop();
        ViewTreeObserver observer = state.content.getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnPreDrawListener(state);
        state.content.removeView(state.ruler);
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** Keeps one ruler by its entry button, shown only while the viewfinder is the whole screen. */
    private static final class Attached implements ViewTreeObserver.OnPreDrawListener, QuickRulerView.Mover {
        final Activity activity;
        final View button;
        final ViewGroup content;
        final QuickRulerView ruler;
        final float density;
        private final float width;
        private final float height;
        private boolean fontTaken;
        private long syncedAt;
        /** Where the filter entry button stands (centre x, centre y, size) in the natural frame; null while unknown. */
        private float[] entry;
        /** Height moved from the place above the button, in dp (down is positive in the natural frame), upright and sideways. */
        private float portraitOffset;
        private float landscapeOffset;
        /** Which edge lane the ruler is in: the button's own, or its mirror. */
        private boolean portraitLeft;
        private boolean landscapeLeft;
        /** Horizontal distance from the lane while the ruler is dragged or snapping, in natural-frame pixels. */
        private float dragX;
        private ValueAnimator snap;

        Attached(Activity activity, View button, ViewGroup content, QuickRulerView ruler, float density) {
            this.activity = activity;
            this.button = button;
            this.content = content;
            this.ruler = ruler;
            this.density = density;
            width = QuickRulerView.WIDTH_DP * density;
            height = QuickRulerView.HEIGHT_DP * density;
            SharedPreferences prefs = prefs();
            portraitOffset = prefs.getFloat(PORTRAIT_OFFSET, 0f);
            landscapeOffset = prefs.getFloat(LANDSCAPE_OFFSET, 0f);
            portraitLeft = prefs.getBoolean(PORTRAIT_LEFT, false);
            landscapeLeft = prefs.getBoolean(LANDSCAPE_LEFT, false);
            ruler.setMover(this);
        }

        void stop() {
            if (snap != null) snap.cancel();
        }

        private SharedPreferences prefs() {
            return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }

        // ---- the phone's natural frame ----

        private int rotation() {
            Display display = content.getDisplay();
            return display == null ? Surface.ROTATION_0 : display.getRotation();
        }

        private boolean landscape() {
            int rotation = rotation();
            return rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
        }

        /** Size of the content in the natural frame. */
        private float naturalWidth() {
            return landscape() ? content.getHeight() : content.getWidth();
        }

        private float naturalHeight() {
            return landscape() ? content.getWidth() : content.getHeight();
        }

        /** A point of the screen in the natural frame. */
        private float[] toNatural(float x, float y) {
            switch (rotation()) {
                case Surface.ROTATION_90: return new float[]{naturalWidth() - y, x};
                case Surface.ROTATION_180: return new float[]{naturalWidth() - x, naturalHeight() - y};
                case Surface.ROTATION_270: return new float[]{y, naturalHeight() - x};
                default: return new float[]{x, y};
            }
        }

        /** A point of the natural frame on the screen. */
        private float[] toScreen(float x, float y) {
            switch (rotation()) {
                case Surface.ROTATION_90: return new float[]{y, naturalWidth() - x};
                case Surface.ROTATION_180: return new float[]{naturalWidth() - x, naturalHeight() - y};
                case Surface.ROTATION_270: return new float[]{naturalHeight() - y, x};
                default: return new float[]{x, y};
            }
        }

        /** A movement on the screen in the natural frame. */
        private float[] toNaturalDelta(float dx, float dy) {
            switch (rotation()) {
                case Surface.ROTATION_90: return new float[]{-dy, dx};
                case Surface.ROTATION_180: return new float[]{-dx, -dy};
                case Surface.ROTATION_270: return new float[]{dy, -dx};
                default: return new float[]{dx, dy};
            }
        }

        /** How far the ruler is turned on the screen: the natural frame turned the way the screen was. */
        private float turn() {
            switch (rotation()) {
                case Surface.ROTATION_90: return -90f;
                case Surface.ROTATION_180: return 180f;
                case Surface.ROTATION_270: return 90f;
                default: return 0f;
            }
        }

        /**
         * A view's centre in the natural frame. The camera turns its controls themselves with the screen, and
         * getLocationInWindow only moves a control's top-left corner through that turn, so the centre is carried
         * up through every level's own matrix instead.
         */
        private float[] centerOf(View target) {
            float[] point = {target.getWidth() / 2f, target.getHeight() / 2f};
            View view = target;
            while (view != content) {
                view.getMatrix().mapPoints(point);
                ViewParent parent = view.getParent();
                point[0] += view.getLeft();
                point[1] += view.getTop();
                if (!(parent instanceof View)) break;
                point[0] -= ((View) parent).getScrollX();
                point[1] -= ((View) parent).getScrollY();
                view = (View) parent;
            }
            return toNatural(point[0], point[1]);
        }

        /**
         * Where the filter entry button stands, in the natural frame: its centre and its size. The Ricoh mode hides
         * that button, but the controls row is the same: the left entry button's outer edge and the right one's
         * mirror each other, so the place is worked out from the left one and stays where master mode has it.
         */
        private float[] entryPlace() {
            if (button.getWidth() > 0 && button.isShown()) {
                float[] center = centerOf(button);
                return new float[]{center[0], center[1], button.getHeight()};
            }
            View left = leftButton();
            if (left == null || left.getWidth() <= 0) return null;
            float[] center = centerOf(left);
            float size = left.getHeight();
            return new float[]{naturalWidth() - (center[0] - left.getWidth() / 2f) - size / 2f, center[1], size};
        }

        private View leftButton() {
            String pack = content.getContext().getPackageName();
            return content.findViewById(content.getResources().getIdentifier("camera_menu_left_enter_button", "id", pack));
        }

        // ---- showing ----

        @Override public boolean onPreDraw() {
            boolean show = wholeScreen();
            int visibility = show ? View.VISIBLE : View.GONE;
            if (ruler.getVisibility() != visibility) {
                ruler.setVisibility(visibility);
                if (show) ruler.refresh();
            }
            if (show) {
                // The controls move with rotation and with the camera's own layout passes: follow them every frame.
                entry = entryPlace();
                place();
                if (!fontTaken) takeFont();
                // The stock readouts and the gear can change the value too: the ruler shows what the camera holds.
                long now = System.currentTimeMillis();
                if (now - syncedAt >= SYNC_MS) {
                    syncedAt = now;
                    ruler.refresh();
                }
            }
            return true;
        }

        /**
         * The master or Ricoh mode, the controls row fully shown (the filter entry button in master mode, the left
         * entry button in Ricoh mode, each with everything above it), and no panel open.
         */
        private boolean wholeScreen() {
            String mode = LegacyFilterPanel.modeName(activity);
            boolean ricoh = "gr".equals(mode);
            if (!ricoh && !"professional".equals(mode)) return false;
            if (ExpandedFilterMenu.expanded()) return false;
            View indicator = ricoh ? leftButton() : button;
            if (indicator == null || indicator.getWidth() <= 0 || !indicator.isShown()) return false;
            float alpha = 1f;
            for (View view = indicator; view != null; ) {
                alpha *= view.getAlpha();
                ViewParent parent = view.getParent();
                view = parent instanceof View ? (View) parent : null;
            }
            return alpha > 0.99f;
        }

        /** The value in the top bar's exposure readout ("EV 0.0") is the font reference. */
        private void takeFont() {
            String pack = content.getContext().getPackageName();
            View bar = content.findViewById(content.getResources().getIdentifier("oplus_setting_bar_left", "id", pack));
            int second = content.getResources().getIdentifier("tv_second", "id", pack);
            if (!(bar instanceof ViewGroup) || second == 0) return;
            View text = ((ViewGroup) bar).findViewById(second);
            if (!(text instanceof TextView)) return;
            ruler.useFontOf((TextView) text);
            fontTaken = true;
        }

        // ---- placing, in the natural frame ----

        private boolean left() {
            return landscape() ? landscapeLeft : portraitLeft;
        }

        private float offsetPx() {
            return (landscape() ? landscapeOffset : portraitOffset) * density;
        }

        /** Where the tick column's centre is in an edge lane: the button's column, or its mirror. */
        private float laneCenter(boolean left) {
            return left ? naturalWidth() - entry[0] : entry[0];
        }

        /** The top of the ruler where it stands above the button, before any move. */
        private float homeY() {
            // The pointer stays put; the unit label under the spread ruler ends GAP_DP above the button.
            float labelBottom = QuickRulerView.HEIGHT_DP / 2f + QuickRulerView.ACTIVE_HALF_DP
                + QuickRulerView.LABEL_GAP_DP + QuickRulerView.LABEL_HEIGHT_DP;
            return entry[1] - entry[2] / 2f - (GAP_DP + labelBottom) * density;
        }

        /** The ruler stays inside the screen however far it was moved. */
        private float clampY(float y) {
            return Math.max(0f, Math.min(naturalHeight() - height, y));
        }

        private float clampX(float x) {
            return Math.max(0f, Math.min(naturalWidth() - width, x));
        }

        /** The ruler's left edge in the natural frame. */
        private float naturalLeft() {
            return clampX(laneCenter(left()) - ruler.columnCenterX() + dragX);
        }

        private void place() {
            if (entry == null) return;
            float[] center = toScreen(naturalLeft() + width / 2f, clampY(homeY() + offsetPx()) + height / 2f);
            ruler.setRotation(turn());
            ruler.setX(center[0] - width / 2f);
            ruler.setY(center[1] - height / 2f);
        }

        @Override public void moveBy(float dx, float dy) {
            if (snap != null) snap.cancel();
            float[] delta = toNaturalDelta(dx, dy);
            float lane = laneCenter(left()) - ruler.columnCenterX();
            dragX = clampX(naturalLeft() + delta[0]) - lane;
            float home = homeY();
            float moved = (clampY(clampY(home + offsetPx()) + delta[1]) - home) / density;
            if (landscape()) landscapeOffset = moved;
            else portraitOffset = moved;
            place();
        }

        /** Lets go: the ruler goes to the nearer lane with a snapping animation, and the place is kept. */
        @Override public void moveEnd() {
            float centerNow = naturalLeft() + ruler.columnCenterX();
            snapToSide(centerNow < naturalWidth() / 2f);
        }

        /** Long-press without dragging: flips the ruler to the opposite edge lane. */
        @Override public void flipSide() {
            snapToSide(!left());
        }

        private void snapToSide(boolean toLeft) {
            if (entry == null) return;
            float centerNow = naturalLeft() + ruler.columnCenterX();
            if (landscape()) landscapeLeft = toLeft;
            else portraitLeft = toLeft;
            dragX = centerNow - laneCenter(toLeft);
            if (snap != null) snap.cancel();
            snap = ValueAnimator.ofFloat(dragX, 0f);
            snap.setDuration(SNAP_MS);
            snap.setInterpolator(new OvershootInterpolator(1.1f));
            snap.addUpdateListener(animation -> {
                dragX = (Float) animation.getAnimatedValue();
                place();
                ruler.invalidate();
            });
            snap.start();
            prefs().edit()
                .putFloat(PORTRAIT_OFFSET, portraitOffset).putFloat(LANDSCAPE_OFFSET, landscapeOffset)
                .putBoolean(PORTRAIT_LEFT, portraitLeft).putBoolean(LANDSCAPE_LEFT, landscapeLeft).apply();
        }
    }
}
