/*
 * Smooth-min, inverse-power refraction and directional reflection adapted from
 * QWEA0/Liquid-Glass-Android, GlassLensRenderer.kt, fixed commit
 * 16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d.
 *
 * MIT License
 *
 * Copyright (c) 2025-2026 pandadog
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.dafeiyu.mobile.ui.components

import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Host-local pixels. Scale is about center; touch is already transformed by the caller. */
internal data class GlassPairShape(
    val center: Offset,
    val radius: Float,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val press: Float = 0f,
    val touch: Offset = Offset.Zero,
    val alpha: Float = 1f,
    val halfSize: Offset = Offset(radius, radius),
    val tint: Int? = null,
)

/** blend is the polynomial smooth-min width in pixels, not a normalized progress. */
internal data class GlassPairFrame(
    val first: GlassPairShape,
    val second: GlassPairShape,
    val blend: Float,
)

/** Signed separation of the actual scaled rounded shapes, independent of their press values. */
internal fun glassPairSurfaceGap(first: GlassPairShape, second: GlassPairShape): Float {
    val delta = second.center - first.center
    if (!delta.x.isFinite() || !delta.y.isFinite()) return Float.POSITIVE_INFINITY
    fun support(shape: GlassPairShape, x: Float, y: Float): Float {
        val sx = shape.scaleX.coerceAtLeast(.001f)
        val sy = shape.scaleY.coerceAtLeast(.001f)
        val vx = x * sx
        val vy = y * sy
        val radius = shape.radius.coerceIn(0f, min(shape.halfSize.x, shape.halfSize.y).coerceAtLeast(0f))
        return kotlin.math.abs(vx) * (shape.halfSize.x - radius) +
            kotlin.math.abs(vy) * (shape.halfSize.y - radius) + radius * sqrt(vx * vx + vy * vy)
    }
    fun separation(angle: Float): Float {
        val x = cos(angle)
        val y = sin(angle)
        return delta.x * x + delta.y * y - support(first, x, y) - support(second, x, y)
    }
    // The maximum separating support plane is the convex shapes' surface gap. Refine around a
    // bounded angular scan; this also handles rounded rectangles, ellipses and diagonal movement.
    val step = (Math.PI * 2.0 / 32.0).toFloat()
    var bestAngle = 0f
    var best = Float.NEGATIVE_INFINITY
    for (index in 0 until 32) {
        val angle = index * step
        val candidate = separation(angle)
        if (candidate > best) { best = candidate; bestAngle = angle }
    }
    var left = bestAngle - step
    var right = bestAngle + step
    repeat(8) {
        val a = left + (right - left) / 3f
        val b = right - (right - left) / 3f
        if (separation(a) < separation(b)) left = a else right = b
    }
    return max(best, separation((left + right) * .5f))
}

/** Smooth-min width in pixels. Callers supply availability; launcher pairs use actual motion. */
internal fun glassPairBlendWidth(first: GlassPairShape, second: GlassPairShape, connection: Float = 1f): Float {
    if (first.alpha <= 0f || second.alpha <= 0f || !connection.isFinite() || connection <= 0f) return 0f
    val shortEdge = minOf(first.halfSize.x * first.scaleX, first.halfSize.y * first.scaleY,
        second.halfSize.x * second.scaleX, second.halfSize.y * second.scaleY) * 2f
    if (!shortEdge.isFinite() || shortEdge <= 0f) return 0f
    val gap = glassPairSurfaceGap(first, second)
    if (!gap.isFinite()) return 0f
    val proximity = (1f - max(gap, 0f) / max(shortEdge * .50f, .001f)).coerceIn(0f, 1f)
    val eased = proximity * proximity * (3f - 2f * proximity)
    return shortEdge * .80f * eased * connection.coerceIn(0f, 1f)
}

/**
 * One RuntimeShader per pair. The caller owns blur, capture, effect caching and API fallback.
 * Output includes margin: it must be drawn at (-margin, -margin) without a single-circle clip.
 */
@RequiresApi(33)
internal class GlassPairLensShader {
    private val shader = RuntimeShader(SOURCE)

    /**
     * width/height exclude the recording margin. sampleBounds is capture's safe intersection
     * in recording coordinates, including margin; it must not be translated again.
     * Each call snapshots updated uniforms into a new effect, without recompiling the shader.
     * Invalid recording dimensions/bounds throw so the caller can restore individual materials.
     */
    @MainThread
    fun effect(
        width: Int,
        height: Int,
        margin: Int,
        tint: Int,
        density: Float,
        darkTheme: Boolean,
        frame: GlassPairFrame,
        sampleBounds: Rect,
        profile: GlassMaterialProfile = GlassMaterialProfile.Button,
    ): RenderEffect {
        require(width > 0 && height > 0 && margin >= 0) { "Invalid pair recording dimensions" }
        val recordingWidth = width.toLong() + margin.toLong() * 2L
        val recordingHeight = height.toLong() + margin.toLong() * 2L
        require(recordingWidth <= Int.MAX_VALUE && recordingHeight <= Int.MAX_VALUE) {
            "Pair recording dimensions overflow"
        }
        require(sampleBounds.left.isFinite() && sampleBounds.top.isFinite() &&
            sampleBounds.right.isFinite() && sampleBounds.bottom.isFinite()) {
            "Pair sample bounds must be finite recording coordinates"
        }
        val left = max(.5f, sampleBounds.left)
        val top = max(.5f, sampleBounds.top)
        val right = min(recordingWidth.toFloat() - .5f, sampleBounds.right)
        val bottom = min(recordingHeight.toFloat() - .5f, sampleBounds.bottom)
        require(left <= right && top <= bottom) { "Pair sample bounds miss the recording" }

        val safeDensity = density.finiteOr(1f).coerceIn(.1f, 16f)
        val limit = min(65536f, max(recordingWidth, recordingHeight).toFloat() * 4f)
        val first = frame.first.sanitized(limit)
        val second = frame.second.sanitized(limit)
        val firstOptics = first.optics(safeDensity)
        val secondOptics = second.optics(safeDensity)
        val shortEdge = when {
            first.alpha <= 0f -> second.shortEdge()
            second.alpha <= 0f -> first.shortEdge()
            else -> min(first.shortEdge(), second.shortEdge())
        }
        shader.setFloatUniform("recordingSize", recordingWidth.toFloat(), recordingHeight.toFloat())
        shader.setFloatUniform("margin", margin.toFloat())
        uploadShape("1", first, firstOptics, tint)
        uploadShape("2", second, secondOptics, tint)
        shader.setFloatUniform("blendK", frame.blend.finiteOr(0f).coerceIn(0f, limit))
        val shadow = glassButtonShadowGeometry(shortEdge, safeDensity)
        shader.setFloatUniform("shadowGeometry", shadow.offsetY, shadow.spread, shadow.blurSize, shadow.opacity)
        val quietPanel = profile == GlassMaterialProfile.SidebarPanel
        shader.setFloatUniform("specScale", glassSpecScale(profile.buttonStyle, darkTheme, quietPanel))
        shader.setFloatUniform("rimFloor", glassRimFloor(profile.buttonStyle, darkTheme, quietPanel))
        shader.setFloatUniform("satFactor", glassVibrancy(quietPanel))
        shader.setFloatUniform("accentScatter", glassAccentScatter(darkTheme))
        shader.setFloatUniform("falloff", 2f)
        shader.setFloatUniform("dimAmount", profile.dimAmount)
        shader.setFloatUniform("sampleLo", left, top)
        shader.setFloatUniform("sampleHi", right, bottom)
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }

    private fun uploadShape(suffix: String, shape: GlassPairShape, optics: GlassLensGeometry, fallbackTint: Int) {
        shader.setFloatUniform("shape$suffix", shape.center.x, shape.center.y, shape.radius, shape.alpha)
        shader.setFloatUniform("scale$suffix", shape.scaleX, shape.scaleY)
        shader.setFloatUniform("halfSize$suffix", shape.halfSize.x, shape.halfSize.y)
        shader.setFloatUniform("press$suffix", shape.press)
        shader.setFloatUniform("touch$suffix", shape.touch.x, shape.touch.y)
        shader.setFloatUniform("optics$suffix", optics.bevel, optics.refraction, optics.hairWidth)
        val shade = shape.tint ?: fallbackTint
        shader.setFloatUniform("tint$suffix", Color.red(shade) / 255f, Color.green(shade) / 255f,
            Color.blue(shade) / 255f, Color.alpha(shade) / 255f)
    }

    private fun GlassPairShape.sanitized(limit: Float): GlassPairShape {
        val valid = center.x.isFinite() && center.y.isFinite() && radius.isFinite() &&
            radius >= 0f && scaleX.isFinite() && scaleY.isFinite() && scaleX > 0f && scaleY > 0f &&
            halfSize.x.isFinite() && halfSize.y.isFinite() && halfSize.x > 0f && halfSize.y > 0f
        if (!valid) return GlassPairShape(Offset.Zero, 1f, alpha = 0f)
        val validTouch = touch.x.isFinite() && touch.y.isFinite()
        return copy(
            center = Offset(center.x.coerceIn(-limit, limit), center.y.coerceIn(-limit, limit)),
            radius = radius.coerceIn(0f, min(limit, min(halfSize.x, halfSize.y))),
            halfSize = Offset(halfSize.x.coerceIn(.001f, limit), halfSize.y.coerceIn(.001f, limit)),
            scaleX = scaleX.coerceIn(.05f, 8f),
            scaleY = scaleY.coerceIn(.05f, 8f),
            press = if (validTouch) press.finiteOr(0f).coerceIn(0f, 1f) else 0f,
            touch = if (validTouch) Offset(touch.x.coerceIn(-limit, limit), touch.y.coerceIn(-limit, limit))
                else center,
            alpha = alpha.finiteOr(0f).coerceIn(0f, 1f),
        )
    }

    private fun GlassPairShape.shortEdge(): Float = 2f * min(halfSize.x * scaleX, halfSize.y * scaleY)

    private fun GlassPairShape.optics(density: Float): GlassLensGeometry {
        val diameter = ceil(shortEdge().coerceAtLeast(1f)).toInt()
        return glassLensGeometry(diameter, diameter, density)
    }

    private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

    private companion object {
        private const val SOURCE = """
            uniform shader content;
            uniform float2 recordingSize;
            uniform float margin;
            uniform float4 shape1;
            uniform float4 shape2;
            uniform float2 scale1;
            uniform float2 scale2;
            uniform float2 halfSize1;
            uniform float2 halfSize2;
            uniform float press1;
            uniform float press2;
            uniform float2 touch1;
            uniform float2 touch2;
            uniform float3 optics1;
            uniform float3 optics2;
            uniform float blendK;
            uniform float4 shadowGeometry;
            uniform float specScale;
            uniform float rimFloor;
            uniform float satFactor;
            uniform float accentScatter;
            uniform float falloff;
            uniform float dimAmount;
            uniform float4 tint1;
            uniform float4 tint2;
            uniform float2 sampleLo;
            uniform float2 sampleHi;

            float shapeField(float2 p, float4 shape, float2 scale, float2 halfSize) {
                float2 q = (p - shape.xy) / scale;
                float2 inner = abs(q) - halfSize + shape.z;
                float2 outside = max(inner, float2(0.0));
                float outsideLength = length(outside);
                float localField = outsideLength + min(max(inner.x, inner.y), 0.0) - shape.z;
                float2 localNormal = outsideLength > 0.0001 ? outside / outsideLength * sign(q) :
                    (inner.x > inner.y ? float2(sign(q.x), 0.0) : float2(0.0, sign(q.y)));
                float grad = length(localNormal / scale);
                // Inverse transform preserves circles, ellipses and rounded-rectangle contours.
                // Correct only pixel-distance near the edge; zero-gradient interiors stay bounded.
                return localField / max(grad, 1.0 / max(scale.x, scale.y));
            }

            float coverage(float d) {
                return clamp(0.5 - d / 1.5, 0.0, 1.0);
            }

            // x = common field, y = first-shape contribution. Zero-alpha shapes are
            // removed, rather than assigned a distance that can leave a ghost union.
            float2 pairField(float2 p) {
                if (shape1.w <= 0.0) {
                    return float2(shapeField(p, shape2, scale2, halfSize2), 0.0);
                }
                if (shape2.w <= 0.0) {
                    return float2(shapeField(p, shape1, scale1, halfSize1), 1.0);
                }
                float a = shapeField(p, shape1, scale1, halfSize1);
                float b = shapeField(p, shape2, scale2, halfSize2);
                float k = blendK * min(shape1.w, shape2.w);
                if (k <= 0.0001) return a <= b ? float2(a, 1.0) : float2(b, 0.0);
                float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
                return float2(mix(b, a, h) - k * h * (1.0 - h), h);
            }

            float pairOpacity(float2 p, float h) {
                if (shape1.w <= 0.0) return shape2.w;
                if (shape2.w <= 0.0) return shape1.w;
                float own1 = coverage(shapeField(p, shape1, scale1, halfSize1)) * shape1.w;
                float own2 = coverage(shapeField(p, shape2, scale2, halfSize2)) * shape2.w;
                // A fading deeper shape must not erase an opaque overlapping survivor.
                // Individual coverage here only attributes opacity; material coverage
                // and every optical contour are still driven by pairField.
                return max(mix(shape2.w, shape1.w, h), max(own1, own2));
            }

            float touchWeight(float2 p, float4 shape, float2 scale, float2 halfSize, float2 touch, float press) {
                if (shape.w <= 0.0 || press <= 0.0) return 0.0;
                float2 delta = (p - touch) / scale;
                float dist = length(delta);
                float sigma = max(min(halfSize.x, halfSize.y), 0.001);
                float bounded = 1.0 - smoothstep(sigma * 0.8, sigma * 1.4, dist);
                float own = coverage(shapeField(p, shape, scale, halfSize));
                return press * shape.w * own * bounded *
                    exp(-dot(delta, delta) / max(sigma * sigma * 0.30, 0.000001));
            }

            float2 towardTouch(float2 p, float2 touch, float weight) {
                float2 delta = p - touch;
                return delta / max(length(delta), 1.0) * weight;
            }

            float4 readContent(float2 p) {
                float4 tap = float4(content.eval(clamp(p, sampleLo, sampleHi)));
                // Child shaders return premultiplied RGBA. Unpremultiply each tap
                // before RGB dispersion; final composition premultiplies once.
                float3 rgb = tap.a > 0.00001 ? tap.rgb / tap.a : float3(0.0);
                return float4(clamp(rgb, float3(0.0), float3(1.0)), clamp(tap.a, 0.0, 1.0));
            }

            // QWEA0's coloured-medium model: absorption and a small scattering term preserve
            // transmitted detail. Surface reflection is applied afterwards and stays untinted.
            float3 transmitColour(float3 col, float4 shade) {
                float chroma = max(shade.r, max(shade.g, shade.b)) - min(shade.r, min(shade.g, shade.b));
                if (shade.a > 0.24 && chroma > 0.08) {
                    float luminance = dot(col, float3(0.2126, 0.7152, 0.0722));
                    float3 absorbed = col * mix(float3(1.0), shade.rgb, 0.85);
                    float3 scattered = shade.rgb * (accentScatter * (1.0 - luminance));
                    return mix(col, clamp(absorbed + scattered, float3(0.0), float3(1.0)), shade.a);
                }
                return mix(col, shade.rgb, shade.a);
            }

            half4 main(float2 coord) {
                if (coord.x < 0.0 || coord.y < 0.0 ||
                    coord.x >= recordingSize.x || coord.y >= recordingSize.y ||
                    max(shape1.w, shape2.w) <= 0.0) return half4(0.0);
                float2 p = coord - float2(margin);
                float2 field = pairField(p);
                float d = field.x;
                float cov = coverage(d);
                float opacity = pairOpacity(p, field.y);
                // The same normalized downward shadow as cached independent contours.
                // Evaluate its shifted union separately: material bounds cannot clip its tail.
                float2 shadowPoint = p - float2(0.0, shadowGeometry.x);
                float2 shadowField = pairField(shadowPoint);
                float tail = max(shadowField.x - shadowGeometry.y, 0.0);
                float blurSize = max(shadowGeometry.z, 0.001);
                float gaussian = tail / (blurSize * 0.50);
                float shadow = shadowGeometry.w * exp(-0.5 * gaussian * gaussian) *
                    (1.0 - smoothstep(blurSize * 0.75, blurSize, tail)) *
                    pairOpacity(shadowPoint, shadowField.y);
                if (cov <= 0.0 && shadow < 0.00001) return half4(0.0);
                if (cov <= 0.0) return half4(0.0, 0.0, 0.0, half(shadow));

                float2 gradient = float2(
                    pairField(p + float2(1.0, 0.0)).x - pairField(p - float2(1.0, 0.0)).x,
                    pairField(p + float2(0.0, 1.0)).x - pairField(p - float2(0.0, 1.0)).x
                ) * 0.5;
                float gradientLength = length(gradient);
                float2 normal = gradient / max(gradientLength, 0.0001);
                // At a symmetric critical neck there is no preferred normal; fade
                // directional optics instead of introducing an arbitrary bright seam.
                float normalStrength = smoothstep(0.0001, 0.05, gradientLength);
                float3 optics = mix(optics2, optics1, field.y);
                float bevel = max(optics.x, 1.0);
                float hairWidth = max(optics.z, 1.0);
                float t = clamp(-d / bevel, 0.0, 1.0);
                float gB = pow(5.0, -falloff);
                float slope = (pow(1.0 + 4.0 * t, -falloff) - gB) / max(1.0 - gB, 0.0001);
                float weight1 = touchWeight(p, shape1, scale1, halfSize1, touch1, press1);
                float weight2 = touchWeight(p, shape2, scale2, halfSize2, touch2, press2);
                float localPress = clamp(weight1 + weight2, 0.0, 1.0);
                float refr = optics.y * (1.0 + 0.6 * localPress);
                float2 offset = -normal * slope * refr * normalStrength;
                offset -= (towardTouch(p, touch1, weight1) +
                    towardTouch(p, touch2, weight2)) * (refr * 0.5);
                // Bound the actual combined displacement, including both touches and
                // the largest dispersion tap. This is a budget, not a no-fold proof.
                float maxOffset = min(optics.y, bevel * 0.50) / 1.10;
                offset *= min(1.0, maxOffset / max(length(offset), 0.0001));
                float dispersion = 0.10 * slope;
                float4 red = readContent(coord + offset * (1.0 - dispersion));
                float4 green = readContent(coord + offset);
                float4 blue = readContent(coord + offset * (1.0 + dispersion));
                // A common transmission alpha keeps all dispersed RGB channels valid
                // on captures that contain transparent pixels.
                float transmission = min(red.a, min(green.a, blue.a));
                float3 col = float3(red.r, green.g, blue.b);
                // Same QWEA0 vibrancy curve as LensShader: protected boost, clamped to [0,1].
                float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
                float satNow = max(col.r, max(col.g, col.b)) - min(col.r, min(col.g, col.b));
                float room = 1.0 - smoothstep(0.2, 0.85, satNow);
                float hl = 1.0 - smoothstep(0.75, 0.98, lum);
                float amount = 1.0 + (satFactor - 1.0) * mix(0.3, 1.0, room * hl);
                col = clamp(mix(float3(lum), col, amount), float3(0.0), float3(1.0));
                float4 shade = mix(tint2, tint1, field.y);
                col = transmitColour(col, shade) * (1.0 - dimAmount);
                float materialAlpha = transmission + shade.a * (1.0 - transmission);
                float3 rgb = col * transmission + shade.rgb * shade.a * (1.0 - transmission);
                float facing = dot(normal, float2(-0.6, -0.8));
                float lobeF = pow(max(facing, 0.0), 4.5);
                float lobeB = pow(max(-facing, 0.0), 4.5);
                float inside = 1.0 - smoothstep(-0.75, 0.0, d);
                float hair = clamp(1.0 - abs(d + hairWidth * 0.5) / hairWidth, 0.0, 1.0);
                float glow = clamp((-d - hairWidth) / hairWidth, 0.0, 1.0) *
                    pow(clamp(1.0 + d / max(bevel * 0.5, 1.0), 0.0, 1.0), 1.5);
                float spec = inside * normalStrength *
                    (hair * (rimFloor + 0.70 * (lobeF + lobeB) * specScale) +
                        glow * 0.10 * lobeF * specScale) * (1.0 - localPress * 0.20);
                float front = inside * localPress * 0.10;
                float reflection = clamp(spec + front, 0.0, 0.90);
                rgb = rgb * (1.0 - reflection) + float3(reflection);
                materialAlpha += reflection * (1.0 - materialAlpha);
                float surface = cov * opacity;
                rgb = clamp(rgb, float3(0.0), float3(materialAlpha)) * surface;
                float alpha = materialAlpha * surface;
                // Black shadow under the premultiplied surface.
                alpha += shadow * (1.0 - alpha);
                return half4(half3(rgb), half(clamp(alpha, 0.0, 1.0)));
            }
        """
    }
}
