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
     * Droga światła, jak w prawdziwym aparacie i ciemni:
     * OBIEKTYW  – głębia ostrości (mapa AI), wirujące rozmycie, dyfuzja, miękkość, winieta
     * KLISZA    – czułość widmowa warstw, halacja, przeterminowanie, krzywe charakterystyczne, ziarno
     * CIEMNIA   – efekt krawędziowy, gradacja papieru, barwniki (kolor), starzenie odbitki, ton
     *
     * Czarno-biała klisza = jedna warstwa (wszystkie kanały równe).
     * Kolorowa = trzy warstwy (czuła na czerwień, zieleń, błękit) z własnymi krzywymi,
     * przesłuchem między warstwami i nieczystymi barwnikami.
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
uniform float uInputGain;
uniform float uContrast;
uniform float uToe;
uniform float uGrain;
uniform float uGrainSize;
uniform float uGlow;
uniform float uBloomLod;
uniform float uVignette;
uniform float uMaskAmount;
uniform float uDepthOn;
uniform float uFocus;
uniform float uAperture;
uniform float uSwirl;
uniform float uEdge;
uniform float uDiffusion;
uniform float uExpired;
uniform float uSeed;
uniform float uDamage;
uniform float uTime;
uniform float uAspect;
uniform vec3 uTint;
uniform float uViewfinder;

uniform float uColorStock;
uniform mat3 uCrosstalk;
uniform vec3 uLayerContrast;
uniform vec3 uLayerOffset;
uniform mat3 uDyeMix;
uniform float uSaturation;
uniform vec3 uFadeLift;
uniform vec3 uHighTint;
uniform vec3 uHalation;
uniform vec3 uHueSat;
uniform vec3 uDyeFade;

uniform sampler2D uFlashMap;
uniform float uFlashOn;
uniform float uFlashSynthetic;
uniform vec3 uFlashColor;
uniform float uFlashPower;
uniform float uAmbientKeep;
uniform float uFlashFalloff;
uniform vec2 uShadowOffset;
uniform float uShadowSoft;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

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

// światło padające na emulsję (liniowe RGB)
vec3 lin3(vec3 c) {
    return pow(max(c, vec3(0.0)), vec3(2.2));
}

vec3 expo(vec2 uv, float lod) {
    return lin3(textureLod(uFrame, toTex(clamp(uv, 0.0, 1.0)), lod).rgb);
}

float subjectAt(vec2 uv) {
    return smoothstep(0.25, 0.75, texture(uMask, uv).r);
}

// głębia ostrości: AI mówi, co jest postacią (blisko), a co tłem (daleko)
vec3 optical(vec2 uv, out float coc) {
    float depth = uDepthOn > 0.5 ? 1.0 - subjectAt(uv) : uFocus;
    coc = clamp(abs(depth - uFocus) * uAperture, 0.0, 1.0);
    if (coc < 0.02) return lin3(texture(uFrame, toTex(uv)).rgb);
    float lod = coc * max(uBloomLod - 1.0, 1.0);
    float rad = coc * 0.022;
    vec2 d = uv - 0.5;
    vec2 o1;
    vec2 o2;
    if (uSwirl > 0.5) {
        // Helios: rozmycie układa się w okręgi wokół środka kadru
        vec2 dA = d * vec2(uAspect, 1.0);
        vec2 t = vec2(-dA.y, dA.x) / vec2(uAspect, 1.0);
        o1 = t * rad * 5.0;
        o2 = d * rad * 0.8;
    } else {
        o1 = vec2(rad / uAspect, 0.0);
        o2 = vec2(0.0, rad);
    }
    return expo(uv, lod) * 0.36
        + (expo(uv + o1, lod) + expo(uv - o1, lod)) * 0.2
        + (expo(uv + o2, lod) + expo(uv - o2, lod)) * 0.12;
}

// co "widzą" warstwy emulsji
vec3 layers(vec3 c) {
    if (uColorStock > 0.5) return max(uCrosstalk * c, vec3(0.0));
    return vec3(dot(c, uSpectral));
}

void main() {
    vec2 uv = 0.5 + (vUv - 0.5) * uFit;
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    float gain = exp2(uExposure + uInputGain);

    // WIZJER: klin ostrości (obraz dzielony) w środku matówki – połówki schodzą się, gdy ostrość trafia
    vec2 vc = (uv - 0.5) * vec2(uAspect, 1.0);
    float vr = length(vc);
    float splitR = 0.075;
    float prismR = 0.125;
    vec2 ouv = uv;
    if (uViewfinder > 0.5 && vr < splitR) {
        float centerDepth = uDepthOn > 0.5 ? 1.0 - subjectAt(vec2(0.5)) : uFocus;
        float shift = (centerDepth - uFocus) * 0.05;
        ouv.x += (vc.y < 0.0 ? shift : -shift) / uAspect;
    }
    float coc;
    vec3 e = optical(ouv, coc);

    // pierścień mikropryzmatów: migocze, gdy obraz jest nieostry
    if (uViewfinder > 0.5 && vr >= splitR && vr < prismR) {
        vec2 cell = floor(vc * 260.0);
        float sparkle = hash(cell) - 0.5;
        e *= 1.0 + sparkle * 1.6 * clamp(coc * 3.0, 0.0, 1.0);
    }

    // FLESZ: światło z lampy oddzielone od zastanego (porównanie kadru z błyskiem i bez),
    // przerobione na lampę z epoki: inna barwa i siła, ostrzejszy spadek, twardy cień za postacią,
    // a światło zastane prawie znika przez krótki czas synchronizacji
    if (uFlashOn > 0.5) {
        float F;
        if (uFlashSynthetic > 0.5) {
            vec2 dc = (uv - 0.5) * vec2(uAspect, 1.0);
            F = clamp(subjectAt(uv) * 0.85 + 0.25 * (1.0 - length(dc) * 1.6), 0.0, 1.0);
        } else {
            F = texture(uFlashMap, uv).r;
        }
        F = pow(F, uFlashFalloff);
        float shadow = 0.0;
        if (uDepthOn > 0.5) {
            vec2 so = uShadowOffset;
            float caster = (subjectAt(uv - so)
                + subjectAt(uv - so + vec2(uShadowSoft, 0.0))
                + subjectAt(uv - so - vec2(uShadowSoft, 0.0))
                + subjectAt(uv - so + vec2(0.0, uShadowSoft))
                + subjectAt(uv - so - vec2(0.0, uShadowSoft))) * 0.2;
            shadow = caster * (1.0 - subjectAt(uv));
        }
        vec3 flashLight = e * F * uFlashColor * uFlashPower * (1.0 - 0.9 * shadow);
        vec3 ambient = e * (1.0 - F) * uAmbientKeep;
        e = flashLight + ambient;
    }

    // efekt krawędziowy wywoływacza
    if (uEdge > 0.001) {
        vec3 local = expo(uv, max(uBloomLod - 2.5, 0.0));
        vec3 ratio = clamp((e + 1e-4) / (local + 1e-4), vec3(0.25), vec3(4.0));
        e *= pow(ratio, vec3(uEdge * 0.7 * (1.0 - coc)));
    }

    // miękkość starego obiektywu i filtr dyfuzyjny ("pończocha")
    vec3 soft = expo(uv, max(uBloomLod - 3.0, 0.0));
    e = mix(e, max(e, soft), 0.35 * uGlow);
    vec3 wide = expo(uv, max(uBloomLod - 1.0, 0.0));
    e = mix(e, soft, uDiffusion * 0.3);
    e += max(wide - e, vec3(0.0)) * uDiffusion * 0.6;

    // halacja: światło odbite od podłoża kliszy – w kolorowej najgłębiej leży warstwa czerwona
    vec2 r = vec2(0.018 / uAspect, 0.018);
    vec3 g = expo(uv, uBloomLod) * 0.4
        + (expo(uv + vec2(r.x, 0.0), uBloomLod) + expo(uv - vec2(r.x, 0.0), uBloomLod)
        +  expo(uv + vec2(0.0, r.y), uBloomLod) + expo(uv - vec2(0.0, r.y), uBloomLod)) * 0.15;
    float bright = max(dot(g, uSpectral) * gain - 0.6, 0.0);

    // naświetlenie warstw emulsji
    e = layers(e) * gain + bright * uGlow * 0.8 * uHalation;

    // przeterminowana klisza: nierówna czułość emulsji
    if (uExpired > 0.001) {
        float uneven = vnoise(uv * vec2(uAspect, 1.0) * 2.5 + uSeed) - 0.5;
        e *= exp2(uneven * 1.2 * uExpired);
    }

    // AI: postać zostaje w świetle, tło zapada się w ciemność
    float m = subjectAt(uv);
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

    // krzywe charakterystyczne warstw (stopa - prosta - ramię) × gradacja papieru
    float contrast = uContrast * (1.0 - 0.35 * uExpired);
    vec3 x = log2(max(e, vec3(1e-5)) / 0.18) + uLayerOffset;
    vec3 y = 1.0 / (1.0 + exp(-contrast * uLayerContrast * x * 0.9));
    y = clamp((y - uToe) / (0.985 - uToe), 0.0, 1.0);

    // barwniki: nieczyste barwniki, nasycenie, starzenie (kolor cieni i świateł)
    if (uColorStock > 0.5) {
        y = clamp(uDyeMix * y, 0.0, 1.0);
        // nasycenie zależne od barwy (np. mocne czerwienie, stonowane błękity)
        float l = dot(y, LUMA);
        vec3 chroma = y - l;
        vec3 w = abs(chroma);
        float ws = w.r + w.g + w.b;
        float hs = ws > 1e-4 ? dot(w / ws, uHueSat) : 1.0;
        y = clamp(l + chroma * uSaturation * hs, 0.0, 1.0);
        // blaknięcie barwników: każdy barwnik traci gęstość proporcjonalnie do tego, ile go było,
        // więc najmocniej przebarwiają się cienie (cyjan -> czerwień, magenta -> zieleń, żółty -> błękit)
        y = 1.0 - (1.0 - y) * (1.0 - uDyeFade);
        y = uFadeLift + y * (uHighTint - uFadeLift);
    }

    // zadymienie przeterminowanej kliszy: szare czernie, przygaszone biele
    y = mix(y, vec3(0.45), 0.28 * uExpired);

    // ziarno: najsilniejsze w półtonach; w kolorze chmurki barwnika w każdej warstwie osobno
    vec2 gp = vec2(uv.x * uAspect, uv.y) * uGrainSize;
    vec2 seed = vec2(hash(vec2(uTime, 1.3)), hash(vec2(uTime, 9.1))) * 512.0;
    float n = vnoise(gp + seed) * 0.65 + vnoise(gp * 2.3 + seed.yx) * 0.35 - 0.5;
    vec3 n3 = vec3(n);
    if (uColorStock > 0.5) {
        vec3 own = vec3(
            vnoise(gp * 1.3 + seed + 17.0),
            vnoise(gp * 1.3 + seed + 41.0),
            vnoise(gp * 1.3 + seed + 73.0)) - 0.5;
        n3 = n3 * 0.55 + own * 0.45;
    }
    vec3 amp = uGrain * (0.45 + 2.2 * y * (1.0 - y)) * (1.0 + 0.8 * uExpired);
    y = clamp(y + n3 * amp * 2.0, 0.0, 1.0);

    // rysy i kurz na taśmie
    if (uDamage > 0.5) {
        float sx = hash(vec2(frameNo, 3.7));
        float scratchOn = step(0.55, hash(vec2(frameNo, 5.1)));
        float scratch = scratchOn * (1.0 - smoothstep(0.0, 0.0015, abs(uv.x - sx)));
        y = mix(y, vec3(0.95), scratch * 0.5);
        vec2 cellUv = uv * vec2(uAspect, 1.0) * 60.0;
        float dust = hash(floor(cellUv) + frameNo);
        if (dust > 0.997) {
            float spot = 1.0 - smoothstep(0.1, 0.3, length(fract(cellUv) - 0.5));
            y *= mix(1.0, 0.1, spot);
        }
    }

    // matówka wizjera: delikatna faktura szkła, ciemniejsze rogi, obwódki klina i pierścienia
    if (uViewfinder > 0.5) {
        float glass = hash(floor(uv * vec2(uAspect, 1.0) * 700.0));
        y *= 0.95 + 0.07 * glass;
        y *= 1.0 - 0.18 * smoothstep(0.5, 1.1, dist);
        float ring1 = 1.0 - smoothstep(0.0, 0.0025, abs(vr - splitR));
        float ring2 = 1.0 - smoothstep(0.0, 0.0025, abs(vr - prismR));
        float splitLine = (vr < splitR) ? 1.0 - smoothstep(0.0, 0.0015, abs(vc.y)) : 0.0;
        y = mix(y, y * 0.45, max(max(ring1, ring2), splitLine * 0.6));
    }

    fragColor = vec4(clamp(y * uTint, 0.0, 1.0), 1.0);
}
"""
}
