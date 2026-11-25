package pl.noir.kamera

object Shaders {

    const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
out vec2 vUv;
uniform float uFlip;
void main() {
    vUv = vec2(aUv.x, uFlip > 0.5 ? 1.0 - aUv.y : aUv.y);
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    /**
     * Emulacja czarno-białej kliszy:
     * światło (liniowe) -> czułość widmowa -> poświata/halacja -> maska AI -> winieta
     * -> krzywa charakterystyczna w skali logarytmicznej (jak H&D) -> ziarno zależne od gęstości -> uszkodzenia taśmy.
     */
    const val FRAGMENT = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uFrame;
uniform sampler2D uMask;
uniform int uRotation;
uniform vec2 uFit;
uniform vec3 uSpectral;
uniform float uExposure;
uniform float uContrast;
uniform float uToe;
uniform float uGrain;
uniform float uGrainSize;
uniform float uGlow;
uniform float uBloomLod;
uniform float uVignette;
uniform float uMaskAmount;
uniform float uDamage;
uniform float uTime;
uniform float uAspect;
uniform vec3 uTint;

vec2 toTex(vec2 uv) {
    if (uRotation == 1) return vec2(uv.y, 1.0 - uv.x);
    if (uRotation == 2) return vec2(1.0 - uv.x, 1.0 - uv.y);
    if (uRotation == 3) return vec2(1.0 - uv.y, uv.x);
    return uv;
}

float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float lum(vec3 c) {
    return dot(pow(max(c, vec3(0.0)), vec3(2.2)), uSpectral);
}

float expo(vec2 uv, float lod) {
    return lum(textureLod(uFrame, toTex(clamp(uv, 0.0, 1.0)), lod).rgb);
}

void main() {
    vec2 uv = 0.5 + (vUv - 0.5) * uFit;
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    float gain = exp2(uExposure);
    float e = lum(texture(uFrame, toTex(uv)).rgb);

    // miękkość starego obiektywu: światła lekko "rozlewają się"
    float soft = expo(uv, max(uBloomLod - 3.0, 0.0));
    e = mix(e, max(e, soft), 0.35 * uGlow);

    // halacja: szeroka poświata wokół jasnych źródeł
    vec2 r = vec2(0.018 / uAspect, 0.018);
    float g = expo(uv, uBloomLod) * 0.4
        + (expo(uv + vec2(r.x, 0.0), uBloomLod) + expo(uv - vec2(r.x, 0.0), uBloomLod)
        +  expo(uv + vec2(0.0, r.y), uBloomLod) + expo(uv - vec2(0.0, r.y), uBloomLod)) * 0.15;
    float bright = max(g * gain - 0.6, 0.0);
    e = e * gain + bright * uGlow * 0.8;

    // AI: postać zostaje w świetle, tło zapada się w ciemność
    float m = smoothstep(0.25, 0.75, texture(uMask, uv).r);
    e *= mix(1.0 - 0.93 * uMaskAmount, 1.0, m);

    // naturalna winieta obiektywu
    vec2 d = (uv - 0.5) * vec2(uAspect, 1.0);
    float dist = length(d) / length(vec2(uAspect, 1.0) * 0.5);
    e *= mix(1.0, 1.0 - smoothstep(0.2, 1.05, dist), uVignette);

    // migotanie projektora
    float frameNo = floor(uTime * 18.0);
    if (uDamage > 0.5) {
        e *= 1.0 + (hash(vec2(frameNo, 7.0)) - 0.5) * 0.14;
    }

    // krzywa charakterystyczna kliszy (stopa - prosta - ramię) w skali log ekspozycji
    float x = log2(max(e, 1e-5) / 0.18);
    float y = 1.0 / (1.0 + exp(-uContrast * x * 0.9));
    y = clamp((y - uToe) / (0.985 - uToe), 0.0, 1.0);

    // ziarno: najsilniejsze w półtonach, każde zdjęcie/klatka ma inny układ
    vec2 gp = vec2(uv.x * uAspect, uv.y) * uGrainSize;
    vec2 seed = vec2(hash(vec2(uTime, 1.3)), hash(vec2(uTime, 9.1))) * 512.0;
    float n = vnoise(gp + seed) * 0.65 + vnoise(gp * 2.3 + seed.yx) * 0.35 - 0.5;
    float amp = uGrain * (0.45 + 2.2 * y * (1.0 - y));
    y = clamp(y + n * amp * 2.0, 0.0, 1.0);

    // rysy i kurz na taśmie
    if (uDamage > 0.5) {
        float sx = hash(vec2(frameNo, 3.7));
        float scratchOn = step(0.55, hash(vec2(frameNo, 5.1)));
        float scratch = scratchOn * (1.0 - smoothstep(0.0, 0.0015, abs(uv.x - sx)));
        y = mix(y, 0.95, scratch * 0.5);
        vec2 cellUv = uv * vec2(uAspect, 1.0) * 60.0;
        float dust = hash(floor(cellUv) + frameNo);
        if (dust > 0.997) {
            float spot = 1.0 - smoothstep(0.1, 0.3, length(fract(cellUv) - 0.5));
            y *= mix(1.0, 0.1, spot);
        }
    }

    fragColor = vec4(clamp(vec3(y) * uTint, 0.0, 1.0), 1.0);
}
"""
}
