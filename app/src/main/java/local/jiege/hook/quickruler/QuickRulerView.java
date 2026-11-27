package local.jiege.hook.quickruler;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import java.util.Collections;
import java.util.Locale;

/**
 * A vertical ruler for one value. The ticks are horizontal lines stacked along the screen; the pointer is a fixed
 * line of the long tick's length at mid height and the ruler slides under it. The ticks fade out toward both ends:
 * the value is written above the upper end and the unit below the lower end, so both follow the ends as they move.
 * At rest the ticks are dense and the shown stretch is short; a finger on the ruler starts the adjustment at once
 * and the ticks spread over a longer stretch, so one finger travel is worth fewer steps. Letting go packs them
 * again. Where the finger lands does not matter, only how far it moves.
 *
 * Higher values are toward the top; dragging the ruler down brings the higher ticks to the pointer.
 */
final class QuickRulerView extends View {
    /** What the ruler adjusts. */
    interface Binding {
        /** The unit or name written below the ruler. */
        String name();
        float min();
        float max();
        /** Distance between two ticks, in value units. */
        float step();
        /** Every this many ticks a long tick is drawn. */
        int ticksPerLong();
        /** The value the ruler gives its stronger haptic at. */
        float home();
        float read();
        void write(float value);
        String format(float value);
    }

    static final float WIDTH_DP = 48f;
    static final float HEIGHT_DP = 196f;
    /** Half the shown stretch of ticks, at rest and under a finger. */
    static final float REST_HALF_DP = 52f;
    static final float ACTIVE_HALF_DP = 78f;
    /** Gap between the end of the stretch and its label. */
    static final float LABEL_GAP_DP = 3f;
    static final float LABEL_HEIGHT_DP = 9f;

    private static final float REST_SPACING_DP = 4.8f;
    private static final float ACTIVE_SPACING_DP = 12.48f;
    private static final float LONG_DP = 13f;
    private static final float SHORT_DP = 7f;
    /** The pointer is a little longer than the long ticks. */
    private static final float POINTER_DP = 16f;
    private static final long MOVE_HOLD_MS = 450;
    private static final int NONE = 0, RULER = 1, VALUE = 2, UNIT = 3;
    private static final float PAD_RIGHT_DP = 14f;
    private static final float SLOP_DP = 12f;
    /** Where the fade starts, as a share of the half stretch. */
    private static final float FADE_FROM = 0.45f;
    private static final long SPREAD_MS = 140;
    /** The orange of the camera's shutter button. */
    private static final int ORANGE = Color.rgb(235, 139, 55);

    private final Binding binding;
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private float value;
    /** 0 at rest, 1 under a finger. */
    private float blend;
    private float lastY;
    private float lastQuantized;
    private boolean active;
    private ValueAnimator spread;
    private Mover mover;
    private int zone = NONE;
    private boolean moving;
    private boolean moved;
    private boolean draggingRuler;
    private boolean resetHandled;
    private float downRawX;
    private float downRawY;
    private float lastRawX;
    private float lastRawY;
    private final android.view.GestureDetector doubleTap;
    private final Runnable startMoving = () -> {
        moving = true;
        moved = false;
        if (active) {
            value = lastQuantized;
            setActive(false);
        }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    };

    /** Moves the whole ruler up or down; the host keeps and saves the position. */
    interface Mover {
        void moveBy(float dx, float dy);
        void moveEnd();
        void flipSide();
    }

    QuickRulerView(Context context, Binding binding) {
        super(context);
        this.binding = binding;
        doubleTap = new android.view.GestureDetector(context, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent event) {
                removeCallbacks(startMoving);
                resetHandled = true;
                moving = false;
                draggingRuler = false;
                if (active) setActive(false);
                reset();
                return true;
            }
        });
        density = context.getResources().getDisplayMetrics().density;
        tickPaint.setColor(Color.WHITE);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);
        tickPaint.setStrokeWidth(0.9f * density);
        pointerPaint.setColor(ORANGE);
        pointerPaint.setStrokeCap(Paint.Cap.ROUND);
        pointerPaint.setStrokeWidth(1.4f * density);
        // The value and the unit are set like the value in the camera's top bar: 12 sp, medium weight.
        for (Paint paint : new Paint[]{unitPaint, valuePaint}) {
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
            paint.setTextSize(sp(12));
        }
        setClickable(true);
        value = clamp(binding.read());
        lastQuantized = quantize(value);
    }

    /** Sets the value and the unit in the font of a text of the camera's own UI (size, typeface, letter spacing). */
    void useFontOf(android.widget.TextView source) {
        for (Paint paint : new Paint[]{unitPaint, valuePaint}) {
            paint.setTextSize(source.getTextSize());
            paint.setTypeface(source.getTypeface());
            paint.setLetterSpacing(source.getLetterSpacing());
        }
        invalidate();
    }

    /** Distance of the tick column's centre from the view's left edge. */
    float columnCenterX() {
        return (WIDTH_DP - PAD_RIGHT_DP - LONG_DP / 2f) * density;
    }

    /** Shows what the binding holds now; ignored while a finger is on the ruler. */
    void refresh() {
        if (active) return;
        float read = clamp(binding.read());
        if (read == value) return;
        value = read;
        lastQuantized = quantize(value);
        invalidate();
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // The back gesture of the edge must not take a drag that starts on the ruler.
        setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, right - left, bottom - top)));
    }

    void setMover(Mover mover) {
        this.mover = mover;
    }

    /** Back to the home value (a double tap on the written value). */
    private void reset() {
        value = binding.home();
        lastQuantized = value;
        binding.write(value);
        performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        invalidate();
    }

    /** What a touch at this place is for: the written value, the unit below, the ticks, or nothing. */
    private int zoneAt(float x, float y) {
        float center = getHeight() / 2f;
        float half = half();
        float gap = LABEL_GAP_DP * density;
        float centerX = columnCenterX();
        // A label may stand sideways for the reader, so its touch box is a square around its centre.
        float valueY = center - half - gap - valuePaint.getTextSize() * 0.35f;
        float valueReach = Math.max(valuePaint.measureText("+0.0"), valuePaint.getTextSize()) / 2f + 6 * density;
        if (Math.abs(x - centerX) <= valueReach && Math.abs(y - valueY) <= valueReach) return VALUE;
        float unitY = center + half + gap + LABEL_HEIGHT_DP * density - unitPaint.getTextSize() * 0.35f;
        float unitReach = Math.max(unitPaint.measureText(binding.name()), unitPaint.getTextSize()) / 2f + 8 * density;
        if (Math.abs(x - centerX) <= unitReach && Math.abs(y - unitY) <= unitReach) return UNIT;
        if (Math.abs(x - centerX) <= (LONG_DP / 2f + SLOP_DP) * density) return RULER;
        return NONE;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            zone = zoneAt(event.getX(), event.getY());
            if (zone == NONE) return false;
            getParent().requestDisallowInterceptTouchEvent(true);
            downRawX = lastRawX = event.getRawX();
            downRawY = lastRawY = event.getRawY();
            lastY = event.getY();
            moving = false;
            moved = false;
            draggingRuler = false;
            resetHandled = false;
            doubleTap.onTouchEvent(event);
            if (!resetHandled) {
                postDelayed(startMoving, MOVE_HOLD_MS);
                if (zone == RULER) setActive(true);
            }
            return true;
        }
        if (zone == NONE) return false;
        if (!moving && !draggingRuler) doubleTap.onTouchEvent(event);
        if (resetHandled) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                resetHandled = false;
                zone = NONE;
            }
            return true;
        }
        if (moving) {
            if (action == MotionEvent.ACTION_MOVE) {
                float dx = event.getRawX() - lastRawX;
                float dy = event.getRawY() - lastRawY;
                lastRawX = event.getRawX();
                lastRawY = event.getRawY();
                if (!moved && Math.hypot(event.getRawX() - downRawX, event.getRawY() - downRawY) > 8 * density) {
                    moved = true;
                }
                if (moved && mover != null) mover.moveBy(dx, dy);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                moving = false;
                if (mover != null) {
                    if (moved) mover.moveEnd();
                    else mover.flipSide();
                }
                moved = false;
                zone = NONE;
                invalidate();
            }
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(event.getRawX() - downRawX, event.getRawY() - downRawY) > 8 * density) {
                    removeCallbacks(startMoving);
                    if (zone == RULER) draggingRuler = true;
                }
                if (zone == RULER && active && draggingRuler) {
                    float dy = event.getY() - lastY;
                    lastY = event.getY();
                    value = clamp(value + dy / spacing() * binding.step());
                    float quantized = quantize(value);
                    if (quantized != lastQuantized) {
                        lastQuantized = quantized;
                        binding.write(quantized);
                        performHapticFeedback(quantized == binding.home() ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.CLOCK_TICK);
                    }
                    invalidate();
                } else if (zone == RULER) {
                    lastY = event.getY();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(startMoving);
                if (active) {
                    value = lastQuantized;
                    setActive(false);
                }
                draggingRuler = false;
                zone = NONE;
                return true;
            default:
                return true;
        }
    }

    private void setActive(boolean on) {
        active = on;
        if (spread != null) spread.cancel();
        spread = ValueAnimator.ofFloat(blend, on ? 1f : 0f);
        spread.setDuration(SPREAD_MS);
        spread.setInterpolator(new DecelerateInterpolator());
        spread.addUpdateListener(animation -> {
            blend = (Float) animation.getAnimatedValue();
            invalidate();
        });
        spread.start();
    }

    private float spacing() {
        return lerp(REST_SPACING_DP, ACTIVE_SPACING_DP) * density;
    }

    private float half() {
        return lerp(REST_HALF_DP, ACTIVE_HALF_DP) * density;
    }

    private float lerp(float rest, float active) {
        return rest + (active - rest) * blend;
    }

    @Override protected void onDraw(Canvas canvas) {
        float center = getHeight() / 2f;
        float centerX = columnCenterX();
        float spacing = spacing();
        float half = half();
        float step = binding.step();
        int first = (int) Math.floor((value - half / spacing * step) / step);
        int last = (int) Math.ceil((value + half / spacing * step) / step);
        int minIndex = Math.round(binding.min() / step);
        int maxIndex = Math.round(binding.max() / step);
        for (int index = Math.max(first, minIndex); index <= Math.min(last, maxIndex); index++) {
            float y = center - (index * step - value) / step * spacing;
            float distance = Math.abs(y - center) / half;
            if (distance >= 1f) continue;
            float fade = distance < FADE_FROM ? 1f : 1f - (distance - FADE_FROM) / (1f - FADE_FROM);
            boolean major = index % binding.ticksPerLong() == 0;
            float length = (major ? LONG_DP : SHORT_DP) * density;
            tickPaint.setAlpha(Math.round(255 * fade * fade * (major ? 0.95f : 0.6f)));
            canvas.drawLine(centerX - length / 2f, y, centerX + length / 2f, y, tickPaint);
        }
        canvas.drawLine(centerX - POINTER_DP * density / 2f, center, centerX + POINTER_DP * density / 2f, center, pointerPaint);
        float gap = LABEL_GAP_DP * density;
        // The unit turns orange while the ruler is being moved.
        unitPaint.setColor(moving ? ORANGE : Color.WHITE);
        drawLabel(canvas, binding.format(quantize(value)), centerX, center - half - gap, valuePaint);
        drawLabel(canvas, binding.name(), centerX, center + half + gap + LABEL_HEIGHT_DP * density, unitPaint);
    }

    /** Solid text that stays upright for the reader while the whole ruler is turned with the screen. */
    private void drawLabel(Canvas canvas, String text, float x, float baseline, Paint paint) {
        float pivotY = baseline - paint.getTextSize() * 0.35f;
        canvas.save();
        canvas.rotate(-getRotation(), x, pivotY);
        canvas.drawText(text, x, baseline, paint);
        canvas.restore();
    }

    private float quantize(float v) {
        return Math.round(v / binding.step()) * binding.step();
    }

    private float clamp(float v) {
        return Math.max(binding.min(), Math.min(binding.max(), v));
    }

    private float sp(float size) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, getResources().getDisplayMetrics());
    }

    /** Text of a value with a sign, one decimal. */
    static String signed(float v) {
        return String.format(Locale.US, "%+.1f", v);
    }
}
