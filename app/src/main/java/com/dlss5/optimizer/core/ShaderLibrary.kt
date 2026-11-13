package com.dlss5.optimizer.core

/**
 * GLSL ES 1.00 sources with **compatibility tiers**.
 *
 * Why tiers: drivers reject shaders for reasons that have nothing to do with the device's power —
 * `highp` in fragment stage on old Adreno, dynamic array indexing, loop bodies that exceed temp limits,
 * 6×6 texture taps blowing the instruction budget. Shipping one "best" shader is what produced
 * "This GPU blocked the HDR shader". Instead every stage has a ladder, and [ProgramCache] walks it until
 * something compiles — so the *slowest* device still gets a working, visible effect.
 *
 * Rules honoured by every shader below:
 *  * ES 1.00 only, `gl_FragColor`, `varying`/`attribute`/`uniform` (never `in`/`out`).
 *  * `precision mediump float` only (no highp requirement → works on old Mali/Adreno).
 *  * constant loop bounds, integer-only index arithmetic, fixed-size unrolled taps.
 *  * max 2 samplers per program (guaranteed by the spec, all drivers comply).
 */
object ShaderLibrary {

    const val VERTEX = """
        attribute vec2 aPos;
        varying vec2 vUv;
        void main() {
            vUv = aPos * 0.5 + 0.5;
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
    """

    // ------------------------------------------------------------------ upscale tiers

    data class Tier(val id: String, val label: String, val fragment: String)

    /** Best → safest. The first that compiles wins. */
    fun upscaleTiers(): List<Tier> = listOf(TIER_LANCZOS, TIER_BICUBIC, TIER_LINEAR)

    val TIER_LANCZOS = Tier(
        id = "lanczos3",
        label = "Lanczos-3 (6×6 taps)",
        fragment = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform vec2 uTexel;
            float sincK(float x) {
                if (abs(x) < 0.0001) return 1.0;
                float px = 3.14159265 * x;
                return sin(px) / px;
            }
            float lanc(float x) {
                if (abs(x) >= 3.0) return 0.0;
                return sincK(x) * sincK(x / 3.0);
            }
            void main() {
                vec2 pos = vUv / uTexel - 0.5;
                vec2 base = floor(pos);
                vec2 f = pos - base;
                vec3 acc = vec3(0.0);
                float wsum = 0.0;
                for (int j = -2; j <= 3; j++) {
                    float wy = lanc(float(j) - f.y);
                    for (int i = -2; i <= 3; i++) {
                        float wx = lanc(float(i) - f.x);
                        vec2 uv = (base + vec2(float(i), float(j))) * uTexel;
                        acc += texture2D(uTex, uv).rgb * (wx * wy);
                        wsum += wx * wy;
                    }
                }
                gl_FragColor = vec4(clamp(acc / max(wsum, 0.0001), 0.0, 1.0), 1.0);
            }
        """
    )

    val TIER_BICUBIC = Tier(
        id = "bicubic",
        label = "Bicubic 4×4",
        fragment = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform vec2 uTexel;
            float w0(float t) { return ((-0.5 * t + 1.0) * t - 0.5) * t; }
            float w1(float t) { return ((1.5 * t - 2.5) * t) * t + 1.0; }
            float w2(float t) { return ((-1.5 * t + 2.0) * t + 0.5) * t; }
            float w3(float t) { return ((0.5 * t - 0.5) * t) * t; }
            void main() {
                vec2 pos = vUv / uTexel - 0.5;
                vec2 f = fract(pos);
                vec2 base = (pos - f) * uTexel;
                float wx0 = w0(f.x); float wx1 = w1(f.x); float wx2 = w2(f.x); float wx3 = w3(f.x);
                float wy0 = w0(f.y); float wy1 = w1(f.y); float wy2 = w2(f.y); float wy3 = w3(f.y);
                vec3 acc = vec3(0.0);
                for (int j = 0; j < 4; j++) {
                    float wy = (j == 0) ? wy0 : (j == 1) ? wy1 : (j == 2) ? wy2 : wy3;
                    vec3 rowAcc = vec3(0.0);
                    for (int i = 0; i < 4; i++) {
                        float wx = (i == 0) ? wx0 : (i == 1) ? wx1 : (i == 2) ? wx2 : wx3;
                        vec2 uv = base + vec2(float(i - 1), float(j - 1)) * uTexel;
                        rowAcc += texture2D(uTex, uv).rgb * wx;
                    }
                    acc += rowAcc * wy;
                }
                gl_FragColor = vec4(clamp(acc, 0.0, 1.0), 1.0);
            }
        """
    )

    /** Bicubic-style sharpening reduced to 4 taps + bilinear hardware fetch. Always compiles. */
    val TIER_LINEAR = Tier(
        id = "linear_sharpen",
        label = "Bilinear + edge sharpen",
        fragment = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform vec2 uTexel;
            uniform float uStrength;
            void main() {
                vec3 c = texture2D(uTex, vUv).rgb;
                vec3 l = texture2D(uTex, vUv - vec2(uTexel.x, 0.0)).rgb;
                vec3 r = texture2D(uTex, vUv + vec2(uTexel.x, 0.0)).rgb;
                vec3 u = texture2D(uTex, vUv - vec2(0.0, uTexel.y)).rgb;
                vec3 d = texture2D(uTex, vUv + vec2(0.0, uTexel.y)).rgb;
                vec3 sharp = c + uStrength * (c * 4.0 - l - r - u - d);
                gl_FragColor = vec4(clamp(sharp, 0.0, 1.0), 1.0);
            }
        """
    )

    /** Single-pass unsharp used after an upscale tier that already sharpened (temporal/quality stages). */
    val SHARPEN = Tier(
        id = "unsharp",
        label = "Unsharp mask",
        fragment = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform vec2 uTexel;
            uniform float uStrength;
            void main() {
                vec3 c = texture2D(uTex, vUv).rgb;
                vec3 l = texture2D(uTex, vUv - vec2(uTexel.x, 0.0)).rgb;
                vec3 r = texture2D(uTex, vUv + vec2(uTexel.x, 0.0)).rgb;
                vec3 u = texture2D(uTex, vUv - vec2(0.0, uTexel.y)).rgb;
                vec3 d = texture2D(uTex, vUv + vec2(0.0, uTexel.y)).rgb;
                vec3 sharp = c + uStrength * (c * 4.0 - l - r - u - d);
                gl_FragColor = vec4(clamp(sharp, 0.0, 1.0), 1.0);
            }
        """
    )

    /** Straight copy / temporal blend (two samplers). */
    val BLEND = Tier(
        id = "temporal_blend",
        label = "Temporal blend",
        fragment = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uPrev;
            uniform sampler2D uCur;
            uniform float uAlpha;
            void main() {
                vec3 p = texture2D(uPrev, vUv).rgb;
                vec3 c = texture2D(uCur, vUv).rgb;
                gl_FragColor = vec4(mix(p, c, uAlpha), 1.0);
            }
        """
    )

    // ------------------------------------------------------------------ HDR composite tiers

    fun hdrTiers(): List<Tier> = listOf(HDR_FULL, HDR_LITE, HDR_MINIMAL)

    /**
     * Full grade: exposure, white balance, contrast pivot, Reinhard shoulder, shadow lift, saturation,
     * bloom, unsharp — then the src-over correction solve.
     */
    val HDR_FULL = Tier(
        id = "hdr_full",
        label = "HDR full (bloom + sharpen)",
        fragment = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform sampler2D uBloom;
            uniform vec2 uTexel;
            uniform float uExposure;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uTemperature;
            uniform float uShadowLift;
            uniform float uRolloff;
            uniform float uBloomStrength;
            uniform float uSharpen;
            uniform float uBlend;
            uniform float uAlpha;

            float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

            void main() {
                vec3 original = texture2D(uTex, vUv).rgb;
                vec3 c = pow(max(original, vec3(0.0)), vec3(2.2));

                c *= uExposure;
                c.r *= (1.0 + uTemperature);
                c.b *= (1.0 - uTemperature);
                c = (c - 0.5) * uContrast + 0.5;

                float knee = 0.62;
                vec3 over = max(c - knee, vec3(0.0));
                c = min(c, vec3(knee)) + over / (1.0 + over * max(uRolloff, 0.001) * 2.0);

                float lum = luma(c);
                float shadowMask = 1.0 - smoothstep(0.0, 0.42, lum);
                c += uShadowLift * shadowMask;

                float gray = luma(c);
                c = mix(vec3(gray), c, uSaturation);

                vec3 bloom = texture2D(uBloom, vUv).rgb;
                c += bloom * uBloomStrength * 0.6;

                vec3 graded = pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2));

                vec3 l = texture2D(uTex, vUv - vec2(uTexel.x, 0.0)).rgb;
                vec3 r = texture2D(uTex, vUv + vec2(uTexel.x, 0.0)).rgb;
                vec3 u = texture2D(uTex, vUv - vec2(0.0, uTexel.y)).rgb;
                vec3 d = texture2D(uTex, vUv + vec2(0.0, uTexel.y)).rgb;
                vec3 sharpened = graded + uSharpen * (graded * 4.0 - l - r - u - d);

                vec3 target = mix(original, clamp(sharpened, 0.0, 1.0), uBlend);
                float a = clamp(uAlpha, 0.05, 0.95);
                vec3 src = (target - original * (1.0 - a)) / a;
                gl_FragColor = vec4(clamp(src, 0.0, 1.0), a);
            }
        """
    )

    /** No bloom sampler (saves a texture unit + the blur passes). Everything else preserved. */
    val HDR_LITE = Tier(
        id = "hdr_lite",
        label = "HDR lite (no bloom)",
        fragment = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform vec2 uTexel;
            uniform float uExposure;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uTemperature;
            uniform float uShadowLift;
            uniform float uRolloff;
            uniform float uSharpen;
            uniform float uBlend;
            uniform float uAlpha;

            float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

            void main() {
                vec3 original = texture2D(uTex, vUv).rgb;
                vec3 c = pow(max(original, vec3(0.0)), vec3(2.2));
                c *= uExposure;
                c.r *= (1.0 + uTemperature);
                c.b *= (1.0 - uTemperature);
                c = (c - 0.5) * uContrast + 0.5;

                float knee = 0.62;
                vec3 over = max(c - knee, vec3(0.0));
                c = min(c, vec3(knee)) + over / (1.0 + over * max(uRolloff, 0.001) * 2.0);

                float lum = luma(c);
                c += uShadowLift * (1.0 - smoothstep(0.0, 0.42, lum));

                float gray = luma(c);
                c = mix(vec3(gray), c, uSaturation);

                vec3 graded = pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2));
                vec3 l = texture2D(uTex, vUv - vec2(uTexel.x, 0.0)).rgb;
                vec3 r = texture2D(uTex, vUv + vec2(uTexel.x, 0.0)).rgb;
                vec3 u = texture2D(uTex, vUv - vec2(0.0, uTexel.y)).rgb;
                vec3 d = texture2D(uTex, vUv + vec2(0.0, uTexel.y)).rgb;
                vec3 sharpened = graded + uSharpen * (graded * 4.0 - l - r - u - d);

                vec3 target = mix(original, clamp(sharpened, 0.0, 1.0), uBlend);
                float a = clamp(uAlpha, 0.05, 0.95);
                vec3 src = (target - original * (1.0 - a)) / a;
                gl_FragColor = vec4(clamp(src, 0.0, 1.0), a);
            }
        """
    )

    /** Absolute floor: exposure/contrast/saturation + correction solve. No pow(), no sharpen taps. */
    val HDR_MINIMAL = Tier(
        id = "hdr_minimal",
        label = "HDR minimal (compat)",
        fragment = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform float uExposure;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uBlend;
            uniform float uAlpha;
            void main() {
                vec3 original = texture2D(uTex, vUv).rgb;
                vec3 c = original * uExposure;
                c = (c - 0.5) * uContrast + 0.5;
                float gray = dot(c, vec3(0.299, 0.587, 0.114));
                c = mix(vec3(gray), c, uSaturation);
                vec3 target = mix(original, clamp(c, 0.0, 1.0), uBlend);
                float a = clamp(uAlpha, 0.05, 0.95);
                vec3 src = (target - original * (1.0 - a)) / a;
                gl_FragColor = vec4(clamp(src, 0.0, 1.0), a);
            }
        """
    )

    // ------------------------------------------------------------------ helpers

    val BRIGHT = Tier(
        id = "bright_pass",
        label = "Bright pass",
        fragment = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform vec2 uTexel;
            uniform float uThreshold;
            void main() {
                vec3 c = texture2D(uTex, vUv + vec2(-uTexel.x, -uTexel.y)).rgb;
                c += texture2D(uTex, vUv + vec2(uTexel.x, -uTexel.y)).rgb;
                c += texture2D(uTex, vUv + vec2(-uTexel.x, uTexel.y)).rgb;
                c += texture2D(uTex, vUv + vec2(uTexel.x, uTexel.y)).rgb;
                c *= 0.25;
                float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
                gl_FragColor = vec4(c * smoothstep(uThreshold, uThreshold + 0.28, lum), 1.0);
            }
        """
    )

    val BLUR = Tier(
        id = "separable_blur",
        label = "Separable blur",
        fragment = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform vec2 uTexel;
            uniform vec2 uDir;
            void main() {
                vec2 stepUV = uTexel * uDir;
                vec3 sum = texture2D(uTex, vUv).rgb * 0.227027;
                sum += texture2D(uTex, vUv + stepUV * 1.384615).rgb * 0.316216;
                sum += texture2D(uTex, vUv - stepUV * 1.384615).rgb * 0.316216;
                sum += texture2D(uTex, vUv + stepUV * 3.230769).rgb * 0.070270;
                sum += texture2D(uTex, vUv - stepUV * 3.230769).rgb * 0.070270;
                gl_FragColor = vec4(sum, 1.0);
            }
        """
    )
}
