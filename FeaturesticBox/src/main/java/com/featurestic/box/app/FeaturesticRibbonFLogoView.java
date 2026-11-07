package com.featurestic.box.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Custom Vector/Canvas 3D Folded-Ribbon "F" Emblem View for the Featurestic SDK Launch Screen.
 * Renders the exact 3D folded neon ribbon "F" (Electric Magenta -> Violet -> Royal Blue -> Aqua Cyan)
 * with dual ambient neon glows and a subtle specular edge shimmer — zero bitmap/photo artifacts.
 */
public class FeaturesticRibbonFLogoView extends View {

    private final Paint paintGlowPurple = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintGlowCyan = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintFoldBridge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintLowerStem = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintStemShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintMidBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintTopBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintEdgeRim = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path pathFoldBridge = new Path();
    private final Path pathLowerStem = new Path();
    private final Path pathStemShadow = new Path();
    private final Path pathMidBar = new Path();
    private final Path pathTopBar = new Path();

    private int lastW = -1;
    private int lastH = -1;
    private float shimmerPhase = 0f;
    private ValueAnimator shimmerAnimator;

    public FeaturesticRibbonFLogoView(Context context) {
        super(context);
        init();
    }

    public FeaturesticRibbonFLogoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FeaturesticRibbonFLogoView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paintEdgeRim.setStyle(Paint.Style.STROKE);
        paintEdgeRim.setStrokeCap(Paint.Cap.ROUND);
        paintEdgeRim.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (shimmerAnimator == null) {
            shimmerAnimator = ValueAnimator.ofFloat(0f, 1f);
            shimmerAnimator.setDuration(3200L);
            shimmerAnimator.setRepeatCount(ValueAnimator.INFINITE);
            shimmerAnimator.setRepeatMode(ValueAnimator.RESTART);
            shimmerAnimator.setInterpolator(new LinearInterpolator());
            shimmerAnimator.addUpdateListener(anim -> {
                shimmerPhase = (float) anim.getAnimatedValue();
                invalidate();
            });
            shimmerAnimator.start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (shimmerAnimator != null) {
            shimmerAnimator.cancel();
            shimmerAnimator = null;
        }
        super.onDetachedFromWindow();
    }

    private void rebuildGeometry(int w, int h) {
        if (w <= 0 || h <= 0) return;
        lastW = w;
        lastH = h;
        float s = Math.min(w, h);
        float ox = (w - s) * 0.5f;
        float oy = (h - s) * 0.5f;

        // 1. Ambient Purple-Magenta Glow (Top-Left behind fold)
        paintGlowPurple.setShader(new RadialGradient(
                ox + s * 0.36f, oy + s * 0.35f, s * 0.42f,
                new int[]{0x78A21CFF, 0x32581CFF, 0x00000000},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));

        // 2. Ambient Blue-Cyan Glow (Center-Right & Lower Stem)
        paintGlowCyan.setShader(new RadialGradient(
                ox + s * 0.54f, oy + s * 0.58f, s * 0.44f,
                new int[]{0x6600DCFF, 0x280055FF, 0x00000000},
                new float[]{0f, 0.60f, 1f},
                Shader.TileMode.CLAMP));

        // 3. Segment 1: 3D Left Under-Fold Bridge (Back Layer)
        pathFoldBridge.reset();
        pathFoldBridge.moveTo(ox + s * 0.275f, oy + s * 0.405f);
        pathFoldBridge.cubicTo(
                ox + s * 0.285f, oy + s * 0.370f,
                ox + s * 0.320f, oy + s * 0.355f,
                ox + s * 0.368f, oy + s * 0.355f);
        pathFoldBridge.lineTo(ox + s * 0.448f, oy + s * 0.445f);
        pathFoldBridge.lineTo(ox + s * 0.376f, oy + s * 0.552f);
        pathFoldBridge.lineTo(ox + s * 0.282f, oy + s * 0.432f);
        pathFoldBridge.close();
        paintFoldBridge.setShader(new LinearGradient(
                ox + s * 0.28f, oy + s * 0.36f,
                ox + s * 0.43f, oy + s * 0.54f,
                new int[]{0xFF8C1EFF, 0xFF3A16D8, 0xFF0A1D7E},
                new float[]{0f, 0.48f, 1f},
                Shader.TileMode.CLAMP));

        // 4. Segment 2: Lower Diagonal Stem (Glowing Cyan Bottom Tip -> Electric Blue)
        pathLowerStem.reset();
        pathLowerStem.moveTo(ox + s * 0.366f, oy + s * 0.548f);
        pathLowerStem.lineTo(ox + s * 0.548f, oy + s * 0.548f);
        pathLowerStem.lineTo(ox + s * 0.412f, oy + s * 0.758f);
        pathLowerStem.cubicTo(
                ox + s * 0.392f, oy + s * 0.788f,
                ox + s * 0.368f, oy + s * 0.808f,
                ox + s * 0.335f, oy + s * 0.808f);
        pathLowerStem.lineTo(ox + s * 0.278f, oy + s * 0.808f);
        pathLowerStem.lineTo(ox + s * 0.278f, oy + s * 0.735f);
        pathLowerStem.cubicTo(
                ox + s * 0.278f, oy + s * 0.700f,
                ox + s * 0.286f, oy + s * 0.674f,
                ox + s * 0.304f, oy + s * 0.646f);
        pathLowerStem.close();
        paintLowerStem.setShader(new LinearGradient(
                ox + s * 0.28f, oy + s * 0.81f,
                ox + s * 0.52f, oy + s * 0.54f,
                new int[]{0xFF02FFFF, 0xFF00E2FF, 0xFF0695FF, 0xFF1A4DFF},
                new float[]{0f, 0.36f, 0.74f, 1f},
                Shader.TileMode.CLAMP));

        // Cast Shadow under Middle Crossbar on the Lower Stem
        pathStemShadow.reset();
        pathStemShadow.moveTo(ox + s * 0.352f, oy + s * 0.588f);
        pathStemShadow.cubicTo(
                ox + s * 0.382f, oy + s * 0.562f,
                ox + s * 0.418f, oy + s * 0.556f,
                ox + s * 0.460f, oy + s * 0.556f);
        pathStemShadow.lineTo(ox + s * 0.548f, oy + s * 0.556f);
        pathStemShadow.lineTo(ox + s * 0.506f, oy + s * 0.622f);
        pathStemShadow.lineTo(ox + s * 0.334f, oy + s * 0.616f);
        pathStemShadow.close();
        paintStemShadow.setShader(new LinearGradient(
                ox + s * 0.45f, oy + s * 0.55f,
                ox + s * 0.42f, oy + s * 0.63f,
                new int[]{0xC807166E, 0x0007166E},
                null,
                Shader.TileMode.CLAMP));

        // 5. Segment 3: Middle Crossbar + Upper Diagonal Stem Ribbon
        pathMidBar.reset();
        pathMidBar.moveTo(ox + s * 0.342f, oy + s * 0.594f);
        pathMidBar.lineTo(ox + s * 0.418f, oy + s * 0.468f);
        pathMidBar.cubicTo(
                ox + s * 0.436f, oy + s * 0.438f,
                ox + s * 0.458f, oy + s * 0.432f,
                ox + s * 0.490f, oy + s * 0.432f);
        pathMidBar.lineTo(ox + s * 0.702f, oy + s * 0.432f);
        pathMidBar.lineTo(ox + s * 0.624f, oy + s * 0.542f);
        pathMidBar.cubicTo(
                ox + s * 0.608f, oy + s * 0.560f,
                ox + s * 0.586f, oy + s * 0.562f,
                ox + s * 0.556f, oy + s * 0.562f);
        pathMidBar.lineTo(ox + s * 0.448f, oy + s * 0.562f);
        pathMidBar.cubicTo(
                ox + s * 0.404f, oy + s * 0.562f,
                ox + s * 0.372f, oy + s * 0.572f,
                ox + s * 0.342f, oy + s * 0.594f);
        pathMidBar.close();
        paintMidBar.setShader(new LinearGradient(
                ox + s * 0.34f, oy + s * 0.56f,
                ox + s * 0.70f, oy + s * 0.43f,
                new int[]{0xFF3344FF, 0xFF059BFF, 0xFF00E6FF, 0xFF28FFFF},
                new float[]{0f, 0.45f, 0.85f, 1f},
                Shader.TileMode.CLAMP));

        // 6. Segment 4: Top Horizontal Ribbon with Rounded Left Fold (Magenta -> Violet -> Blue -> Cyan)
        pathTopBar.reset();
        pathTopBar.moveTo(ox + s * 0.275f, oy + s * 0.412f);
        pathTopBar.lineTo(ox + s * 0.358f, oy + s * 0.276f);
        pathTopBar.cubicTo(
                ox + s * 0.382f, oy + s * 0.238f,
                ox + s * 0.414f, oy + s * 0.228f,
                ox + s * 0.456f, oy + s * 0.228f);
        pathTopBar.lineTo(ox + s * 0.835f, oy + s * 0.228f);
        pathTopBar.lineTo(ox + s * 0.754f, oy + s * 0.342f);
        pathTopBar.cubicTo(
                ox + s * 0.736f, oy + s * 0.362f,
                ox + s * 0.712f, oy + s * 0.364f,
                ox + s * 0.680f, oy + s * 0.364f);
        pathTopBar.lineTo(ox + s * 0.354f, oy + s * 0.364f);
        pathTopBar.cubicTo(
                ox + s * 0.312f, oy + s * 0.364f,
                ox + s * 0.284f, oy + s * 0.382f,
                ox + s * 0.275f, oy + s * 0.412f);
        pathTopBar.close();
        paintTopBar.setShader(new LinearGradient(
                ox + s * 0.27f, oy + s * 0.36f,
                ox + s * 0.83f, oy + s * 0.23f,
                new int[]{0xFFF624FF, 0xFF8C1EFF, 0xFF2862FF, 0xFF00E8FF},
                new float[]{0f, 0.28f, 0.64f, 1f},
                Shader.TileMode.CLAMP));

        paintEdgeRim.setStrokeWidth(Math.max(2f, s * 0.0065f));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (w != lastW || h != lastH) {
            rebuildGeometry(w, h);
        }

        float s = Math.min(w, h);
        float ox = (w - s) * 0.5f;
        float oy = (h - s) * 0.5f;

        // Draw Dual Ambient Neon Glows
        canvas.drawCircle(ox + s * 0.36f, oy + s * 0.35f, s * 0.42f, paintGlowPurple);
        canvas.drawCircle(ox + s * 0.54f, oy + s * 0.58f, s * 0.44f, paintGlowCyan);

        // Draw 3D Folded Ribbon Layers in Back-to-Front Order
        canvas.drawPath(pathFoldBridge, paintFoldBridge);
        canvas.drawPath(pathLowerStem, paintLowerStem);
        canvas.drawPath(pathStemShadow, paintStemShadow);
        canvas.drawPath(pathMidBar, paintMidBar);
        canvas.drawPath(pathTopBar, paintTopBar);

        // Subtle Animated Specular Rim Highlight
        float sx = ox + s * (0.15f + shimmerPhase * 0.75f);
        float sy = oy + s * (0.80f - shimmerPhase * 0.60f);
        paintEdgeRim.setShader(new RadialGradient(
                sx, sy, s * 0.32f,
                new int[]{0x99FFFFFF, 0x3300F6FF, 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawPath(pathTopBar, paintEdgeRim);
        canvas.drawPath(pathMidBar, paintEdgeRim);
        canvas.drawPath(pathLowerStem, paintEdgeRim);
    }
}
