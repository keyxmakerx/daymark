package com.daymark.app.sky

/**
 * The sky's two shaders, as AGSL source, and the few numbers the renderer hands them.
 *
 * **The space** ([SPACE]) is everything behind the stars, and it never draws a star (§D11): a deep
 * layer of dark colour furthest back, long lanes of dust nearer, and, nearer still, gas that gathers
 * as the view closes in. Each layer sits at its own depth behind the stars, so the further back it
 * is the less it grows as the view closes in and the less it slides as the sky is panned (the
 * shader's `at`); that is what makes the sky read as deep rather than as paint on glass. The gas is two levels of cloud a size apart, crossing over as the view closes in,
 * so there is always gas to see at every zoom and none of it swims. It is lit by the stars nearest
 * the middle of the screen ([gasLights]), each in its own colour, and the brightest light at a point
 * wins rather than all of them adding up, so a crowd of stars lights it no more than one does. Its
 * colours are the sky's own ([SkyColours]), blind to data.
 *
 * **A sun** ([SUN]) is one star close in, drawn as the sphere it is: a surface of bright granules
 * with dark lanes between them, slowly boiling while Motion is on, a few spots of its own from its
 * identity ([sunSeed]), the edge darker and deeper in its own colour, warming the way a real star's
 * limb does, and a thin ragged rim and a corona of streamers round it. Its colour is the star's age
 * colour, and its light is the star's. A life event is a giant: bigger, with longer streamers and
 * larger spots (`docs/SKY.md` §3.4).
 *
 * Light is added up in linear light and tone-mapped once: by channel, which turns a hot middle
 * white, and for a sun also by the brightest channel alone, which keeps the star's own colour, the
 * two met at a third. Both shaders keep to the subset of AGSL every Android 13 device compiles: no
 * arrays of uniforms, loops with constant bounds, and gradient noise from a hash rather than from
 * sines, which is several times cheaper on a phone. Neither is ever handed a mood, a count or a date.
 *
 * Import-free, like the rest of `sky/`: the renderer compiles the text and sets the uniforms, and a
 * plain-JVM test reads the uniforms declared here against the ones the renderer sets.
 */
object SkyShaders {

    /** Hashing, gradient noise and fractal noise, shared by both shaders. */
    const val NOISE = """
float h12(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
float2 g2(float2 p) {
    return float2(h12(p), h12(p + 19.19)) * 2.0 - 1.0;
}
float gn(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float a = dot(g2(i), f);
    float b = dot(g2(i + float2(1.0, 0.0)), f - float2(1.0, 0.0));
    float c = dot(g2(i + float2(0.0, 1.0)), f - float2(0.0, 1.0));
    float d = dot(g2(i + float2(1.0, 1.0)), f - float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y) * 0.86 + 0.5;
}
float fbm(float2 p, float octaves) {
    float a = 0.5;
    float s = 0.0;
    float n = 0.0;
    for (int i = 0; i < 8; i++) {
        float w = clamp(octaves - float(i), 0.0, 1.0);
        if (w <= 0.0) break;
        s += a * w * gn(p);
        n += a * w;
        p = float2x2(0.8, 0.6, -0.6, 0.8) * p * 2.03 + 17.1;
        a *= 0.5;
    }
    return s / n;
}
"""

    /**
     * One star close in, drawn as the sun it is, in a square [SUN_REACH] of its radius either side
     * of its middle. Every pixel outside the disc is the rim and the corona, at zero alpha, so they
     * add to what is behind them and the disc covers it.
     */
    const val SUN = NOISE + """
// One star close in, drawn as the sun it is.
uniform float2 uCentre;   // its middle, in pixels
uniform float uRadius;    // its radius, in pixels
uniform float uDensity;   // pixels per dp
uniform float3 uTint;     // its age colour, linear
uniform float uLight;     // how brightly it burns: age and arrival, 0 to 1
uniform float uSeconds;   // the clock, held still when motion is off
uniform float uSeed;      // its own number, 0 to 1, from its identity
uniform float uGiant;     // 1 for a life event, a bigger sun
uniform float uReach;     // how far its light is drawn, in pixels; nothing past it

// The boiling surface: bright granules with soft dark lanes between them, 0 to 1.
float granules(float2 p, float t) {
    p += 0.35 * float2(gn(p * 0.37 + 3.1), gn(p * 0.37 + 8.7)) - 0.175;
    float2 i = floor(p);
    float2 f = fract(p);
    float f1 = 8.0;
    float f2 = 8.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            float2 g = float2(float(x), float(y));
            float2 o = float2(h12(i + g), h12(i + g + 19.19));
            o = 0.5 + 0.4 * sin(t + 6.2831853 * o);
            float d = length(g + o - f);
            if (d < f1) { f2 = f1; f1 = d; } else if (d < f2) { f2 = d; }
        }
    }
    float heart = 1.0 - smoothstep(0.05, 0.75, f1);
    float lane = 1.0 - smoothstep(0.0, 0.45, f2 - f1);
    return clamp(0.6 * heart + 0.4 * (1.0 - lane * lane), 0.0, 1.0);
}

half4 main(float2 p) {
    float2 v = p - uCentre;
    float d = length(v);
    float R = max(uRadius, 0.5);
    float q = d / R;
    float aa = 1.1 / R;
    float disc = 1.0 - smoothstep(1.0 - aa, 1.0 + aa, q);
    float pk = max(uTint.r, max(uTint.g, uTint.b));
    float3 hue = uTint / max(pk, 1e-4);
    float res = smoothstep(5.0, 26.0, R / uDensity);
    float giant = uGiant;
    float t = uSeconds;
    float3 c = float3(0.0);
    float edgeSeen = 0.0;

    if (q < 1.0 + aa) {
        float z = sqrt(max(0.0, 1.0 - q * q));
        float mu = z;
        float limb = 1.0 - 0.6 * (1.0 - mu) - 0.2 * (1.0 - mu) * (1.0 - mu);
        // how far round the sphere's turn this point is, as the eye sees it (after the tone map)
        edgeSeen = 1.0 - pow(mu, 0.6);
        float gran = 1.0;
        float spot = 0.0;
        float facula = 0.0;
        if (res > 0.0) {
            float2 u = v / R;
            float lat = asin(clamp(u.y, -1.0, 1.0));
            float lon = atan(u.x, max(z, 1e-3)) + t * 0.008 * (0.4 + uSeed);
            float2 ll = float2(lon * cos(lat * 0.35), lat);
            float k = 22.0 + uSeed * 10.0;
            // granules: bright hearts, soft dark lanes, slowly boiling, inside larger slow cells
            float g = granules(ll * k + uSeed * 40.0, t * 0.35);
            float m = smoothstep(0.3, 0.75, gn(ll * k * 0.9 + uSeed * 30.0) * 0.7 + gn(ll * k * 2.2 + 7.0 + float2(t * 0.006, 0.0)) * 0.3);
            float meso = fbm(ll * k * 0.18 + uSeed * 3.0 + float2(t * 0.004, 0.0), 3.0);
            gran = mix(0.8, 1.1, 0.55 * g + 0.45 * m) * (0.76 + 0.48 * meso);
            // supergranulation and the faculae: a brighter network, seen best toward the edge
            float net = fbm(ll * 5.0 + uSeed * 9.0, 4.0);
            facula = smoothstep(0.58, 0.78, net) * pow(1.0 - mu, 1.5);
            gran *= 0.9 + 0.2 * fbm(ll * 2.4 + uSeed * 5.0, 3.0);
            // a few spots of its own, from its own number: a dark umbra in a striated penumbra
            for (int n = 0; n < 3; n++) {
                float fn = float(n);
                float2 at = float2(h12(float2(uSeed * 91.0, fn)) * 6.2831853 + t * 0.008 * (0.4 + uSeed),
                                   (h12(float2(fn, uSeed * 57.0)) - 0.5) * 1.1);
                float size = 0.05 + 0.05 * h12(float2(fn * 3.1, uSeed * 13.0));
                size *= step(0.35, h12(float2(uSeed * 7.0, fn * 5.3))) * (1.0 + 0.8 * giant);
                float2 dd = float2((lon - at.x) * cos(lat), lat - at.y);
                dd.x = dd.x - 6.2831853 * floor(dd.x / 6.2831853 + 0.5);
                float r = length(dd) / max(size, 1e-4);
                float wob = 0.12 * (gn(normalize(dd + 1e-5) * 3.0 + fn) - 0.5);
                float pen = 1.0 - smoothstep(0.85 + wob, 1.0 + wob, r);
                float umb = 1.0 - smoothstep(0.38 + wob, 0.5 + wob, r);
                float stri = 0.8 + 0.2 * gn(normalize(dd + 1e-5) * 7.6 + float2(r * 3.0, fn));
                spot = max(spot, pen * 0.42 * stri + umb * 0.45);
            }
            // toward the edge the cells are seen side-on, smaller than a pixel, and fade out
            float side = smoothstep(0.08, 0.5, mu) * res;
            gran = mix(1.0, gran, side);
            facula *= res;
            spot *= res;
        }
        // in linear light: white-hot in the middle, its own colour deepening and warming toward the
        // edge, and the edge darker, the way a real star's limb is
        float3 tilt = hue * mix(float3(1.0), float3(1.0, 0.9, 0.75), 1.0 - mu);
        tilt /= max(tilt.r, max(tilt.g, tilt.b));
        float3 deep = pow(max(tilt, float3(0.0)), float3(1.0 + 0.9 * (1.0 - mu)));
        float3 surf = mix(deep, float3(1.0), 0.35 * mu * mu);
        c += surf * 2.6 * limb * gran * (1.0 - spot) * disc;
        c += hue * facula * 0.6 * disc;
    }

    float outPx = max(d - R, 0.0);
    float h = max(q - 1.0, 0.0);
    float2 dir = v / max(d, 1e-3);
    // the glow of the disc's own light round its edge
    float glow = 0.28 * exp(-h * 9.0) + 0.06 * exp(-h * 2.4);
    // the chromosphere: a thin ragged edge of brighter gas, flickering slowly
    float ch = gn(dir * 23.0 + uSeed * 13.0 + float2(t * 0.04, 0.0));
    float rim = exp(-outPx / (uDensity * (0.8 + 4.0 * ch * ch))) * 0.4;
    // the corona: streamers running out, slowly turning, falling away with height
    // sampled round a circle rather than by angle, so there is no seam where the angle wraps
    float s1 = gn(dir * 1.9 + uSeed * 20.0 + float2(t * 0.02, 0.0));
    float s2 = gn(dir * 5.7 + uSeed * 7.0 + float2(0.0, h * 1.5 - t * 0.03));
    float streams = 0.45 + 0.9 * s1 * s1 + 0.5 * s2;
    float cor = exp(-h * mix(5.0, 3.25, giant)) * pow(max(streams, 0.0), 1.6) * 0.18;
    float3 air = mix(hue, float3(1.0), 0.25);
    c += air * ((glow + rim) * res + cor * res + (1.0 - res) * 0.5 * exp(-h * 4.0)) * (1.0 - disc);

    c *= uLight * (1.0 - smoothstep(uReach * 0.7, uReach, d));
    // tone-mapped twice and met: by channel, which turns a hot middle white, and by the brightest
    // channel alone, which keeps the star's own colour
    float peak = max(c.r, max(c.g, c.b));
    float3 byChannel = 1.0 - exp(-c);
    float3 byPeak = c * ((1.0 - exp(-peak)) / max(peak, 1e-4));
    c = mix(byChannel, byPeak, 0.3);
    c = pow(max(c, float3(0.0)), float3(1.0 / 2.2));
    // seen, not computed: the turn of the sphere read across the whole face, each channel darkening
    // by how little of it the star's colour holds, so the edge deepens into its own colour
    float3 edgeHue = hue * float3(1.0, 0.9, 0.75);
    edgeHue /= max(edgeHue.r, max(edgeHue.g, edgeHue.b));
    float3 lose = 0.12 + 0.3 * (1.0 - edgeHue);
    c *= mix(float3(1.0), 1.0 - lose * edgeSeen, disc);
    // never green: no pixel has more green in it than red or blue
    c.g = min(c.g, max(c.r, c.b));
    float a = disc * clamp(uLight, 0.0, 1.0);
    return half4(half3(c), half(a));
}
"""

    /**
     * The space behind the stars, drawn into a layer [SPACE_DOWN] times smaller than the screen
     * each way and stretched over it: nothing in it is sharper than that, and it costs a sixteenth
     * of the screen to draw.
     */
    const val SPACE = NOISE + """
// The space behind the stars: deep colour, dust and, nearer, gas. No star is ever drawn here.
uniform float2 uSize;     // the layer, in its own pixels
uniform float2 uCentre;   // the point of the sky at the middle of the screen, in the sky's units
uniform float uScale;     // the layer's pixels per unit of the sky, at the stars' own depth
uniform float uAcross;    // screen widths across the sky: 1 for the whole sky
uniform float2 uSeed;     // where in its noise this sky's space begins, from the sky's seed
uniform float3 uSpace0;   // the space's tones, darkest first, linear
uniform float3 uSpace1;
uniform float3 uSpace2;
uniform float3 uAccent;
uniform float3 uGasA;     // the gas's three colours, linear
uniform float3 uGasB;
uniform float3 uGasC;
uniform float uSeconds;   // a slow clock, held still when motion is off
uniform float uShow;      // how far the opening has brought the space in, 0 to 1
uniform float4 uLight0;  // a star lighting the gas: where, in pixels; how far; how much
uniform float4 uLight1;
uniform float4 uLight2;
uniform float4 uLight3;
uniform float4 uLight4;
uniform float4 uLight5;
uniform float4 uLight6;
uniform float4 uLight7;
uniform float3 uLightTint0;
uniform float3 uLightTint1;
uniform float3 uLightTint2;
uniform float3 uLightTint3;
uniform float3 uLightTint4;
uniform float3 uLightTint5;
uniform float3 uLightTint6;
uniform float3 uLightTint7;

// The most light the stars together give any point of gas, so a crowd lights it little more than a
// bright star does.
const float LIGHT_CAP = 1.2;

// Where a pixel lands on a layer that far behind the stars: the further back, the less it
// grows as the view closes in and the less it slides as the sky is panned.
float2 at(float2 p, float depth) {
    float s = uScale / (1.0 + depth * (uAcross - 1.0));
    return uCentre + (p - uSize * 0.5) / s;
}

// One level of the gas: clouds about [freq] to a unit of the sky, in patches with dark space between
// them, lit along the edges that face the light and shadowed in their thick parts. The rgb is the
// light it gives; the alpha is how much dust in front of it dims what is behind. [ld] is the way
// the stars' light comes from at this pixel, and [facing] is set to how much of it reaches here
// past the gas in between, so a cloud has a lit side and a shadowed one (#535).
float4 clouds(float2 w, float freq, float level, float oct, float t, float2 ld, out float facing) {
    facing = 1.0;
    float2 o = float2(h12(float2(level, 1.7)), h12(float2(level, 9.3))) * 61.0;
    float2 q = w * freq + o;
    float region = smoothstep(0.44, 0.7, fbm(q * 0.16 + 11.0, 3.0));
    if (region <= 0.0) return float4(0.0);
    float2 wp = float2(fbm(q + float2(t * 0.012, 0.0), 3.0), fbm(q + 5.2 - float2(0.0, t * 0.009), 3.0));
    float n = fbm(q + wp * 1.7, oct);
    float lit = fbm(q + wp * 1.7 + float2(0.05, 0.08), oct - 1.0);
    float shade = clamp((n - lit) * 10.0, -1.0, 1.0);
    float dens = smoothstep(0.4, 0.82, n) * region;
    // Noise can stray a little past 0 and 1, and pow of a negative base is undefined: phone GPUs
    // answer it with holes. Every base here is held at zero or above.
    float fil = pow(max(0.0, 1.0 - abs(fbm(q * 2.1 + wp * 2.3 + 3.3, oct) * 2.0 - 1.0)), 10.0) * smoothstep(0.38, 0.62, n) * region;
    float thick = smoothstep(0.66, 0.92, n);
    float ct = fbm(q * 0.35 + 2.0, 3.0);
    float3 col = mix(mix(uGasA, uGasB, smoothstep(0.32, 0.52, ct)), uGasC, smoothstep(0.55, 0.75, ct));
    // Depth: the same field drawn coarse says how much gas surrounds a point, and the inside of a
    // thick mass gets less light than its rim, so a mass reads as a volume and not a flat glow.
    float occ = smoothstep(0.42, 0.85, fbm(q + wp * 1.7, 2.0)) * region;
    // Gas lying between this point and the light shades it from that light.
    facing = 1.0 - 0.6 * smoothstep(-0.08, 0.3, fbm(q + wp * 1.7 + ld * 0.45, max(oct - 2.0, 2.0)) - n);
    // The relief never goes below zero: negative light showed as a hard blue patch with a red rim.
    float glow = dens * 0.075 * max(0.0, 0.6 + 0.8 * shade) * (1.0 - 0.45 * thick) * (1.0 - 0.45 * occ) +
        fil * 0.035 * (1.0 - 0.35 * occ);
    float knot = max(smoothstep(0.6, 0.8, fbm(q * 1.4 + 9.1 + wp, oct - 1.0)) * region, occ * dens * 0.55);
    return float4(col * glow, knot);
}

// How one star lights the gas round it: its colour, and how strongly at this pixel. [l].z is its
// reach, from its own size on screen and its brightness (SkyShaders.gasLightReach), and the light
// falls away about as the square of the distance, so a small star lights only the gas near it.
float4 starlight(float2 p, float4 l, float3 tint) {
    float2 d = (p - l.xy) / max(l.z, 1e-3);
    float dd = dot(d, d);
    return float4(tint, l.w * 0.8 / (1.0 + dd * 8.0) * exp(-dd * 0.9));
}

// Adds one star's light into the running sums: colour, amount, and the way it comes from.
void addLight(float2 p, float4 l, float3 tint, inout float3 lc, inout float la, inout float2 lw) {
    float4 s = starlight(p, l, tint);
    lc += s.rgb * s.a;
    la += s.a;
    lw += (l.xy - p) / max(length(l.xy - p), 1.0) * s.a;
}

half4 main(float2 p) {
    float t = uSeconds;
    float zoomIn = log2(max(uAcross, 1.0));

    // 1. Deep space, furthest back: close to black, with broad changes of tone too faint to read
    //    as shapes. Held low and greyed toward its own brightness, because a deep layer in strong
    //    colour is a wash over the whole screen, and a wash reads as wallpaper rather than night.
    float2 w1 = at(p, 0.25) + uSeed;
    float b1 = fbm(w1 * 1.3 + float2(3.1, 1.7), 4.0);
    float b2 = fbm(w1 * 2.9 + float2(9.2, 4.4) + float2(t * 0.0015, 0.0), 4.0);
    float3 c = mix(uSpace0, uSpace1, smoothstep(0.3, 0.75, b1) * 0.6);
    c = mix(c, uSpace2, smoothstep(0.65, 0.9, b1) * 0.25);
    c += uAccent * 0.03 * smoothstep(0.6, 0.95, b2);
    c = mix(float3(dot(c, float3(0.3, 0.4, 0.3))), c, 0.6);

    // 2. Dust, nearer: long dark lanes that dim the glow behind them.
    float2 w2 = at(p, 0.06) + uSeed * 1.7;
    float2 warp2 = float2(fbm(w2 * 4.0 + 1.3, 3.0), fbm(w2 * 4.0 + 7.9, 3.0));
    float lane = fbm(float2(w2.x * 7.0, w2.y * 3.2) + warp2 * 1.8, 5.0);
    float dust = smoothstep(0.52, 0.72, lane);
    c *= 1.0 - 0.55 * dust;

    // 3. Gas, nearest: there from the whole sky, gathering as the view closes in, lit along its
    //    edges and shadowed in its thick parts, so it reads as a volume and not as paint. It is
    //    the one place the space has detail, so it is never taken away entirely.
    float gasShow = mix(0.8, 1.0, smoothstep(0.6, 4.2, zoomIn)) * uShow;
    if (gasShow > 0.0) {
        // Two levels of cloud at once, a size apart, crossing over as the view closes in: finer
        // clouds come in as the coarser ones grow past the screen, so there is always gas to see
        // and none of it swims.
        float depth = 0.02;
        float m = uAcross / (1.0 + depth * (uAcross - 1.0));
        float lv = log2(m);
        float k = floor(lv);
        float f = smoothstep(0.0, 1.0, lv - k);
        float2 w3 = at(p, depth) + uSeed * 2.3;
        float oct = 5.0;
        // The stars light the gas near them, each in its own colour. Every nearby star's light is
        // added, its colour and the way it comes from weighted by how much of it arrives, and the
        // total is capped: picking the brightest one instead drew a hard edge through the gas
        // wherever one star's light handed over to the next (#535).
        float3 lc = float3(0.0);
        float la = 0.0;
        float2 lw = float2(0.0);
        addLight(p, uLight0, uLightTint0, lc, la, lw);
        addLight(p, uLight1, uLightTint1, lc, la, lw);
        addLight(p, uLight2, uLightTint2, lc, la, lw);
        addLight(p, uLight3, uLightTint3, lc, la, lw);
        addLight(p, uLight4, uLightTint4, lc, la, lw);
        addLight(p, uLight5, uLightTint5, lc, la, lw);
        addLight(p, uLight6, uLightTint6, lc, la, lw);
        addLight(p, uLight7, uLightTint7, lc, la, lw);
        float3 tint = lc / max(la, 1e-4);
        float amount = min(la, LIGHT_CAP);
        float2 ld = lw / max(length(lw), 1e-4);
        float fa;
        float fb;
        float4 g0 = clouds(w3, 2.5 * exp2(k), k, oct, t, ld, fa);
        float4 g1 = clouds(w3, 2.5 * exp2(k + 1.0), k + 1.0, oct, t, ld, fb);
        float4 g = g0 * (1.0 - f) + g1 * f;
        float facing = mix(fa, fb, f);
        float lum = max(g.r, max(g.g, g.b));
        c += g.rgb * gasShow;
        c += tint * amount * lum * 2.2 * facing * gasShow;
        c *= 1.0 - 0.55 * g.a * gasShow;
    }

    // 4. A little darker toward the corners, the way an eye looking up sees the sky.
    float2 uv = p / uSize - 0.5;
    c *= 1.0 - 0.22 * pow(clamp(length(uv * float2(1.0, uSize.y / uSize.x)) * 0.9, 0.0, 1.5), 2.2);

    // No negative light reaches the tone map, whose pow it would leave undefined.
    c = max(c, float3(0.0));
    c = 1.0 - exp(-c * 1.05);
    c = pow(max(c, float3(0.0)), float3(1.0 / 2.2));
    // dither, so the darkest gradients never band
    c += (h12(p + fract(t) * 91.0) - h12(p.yx + 17.3)) / 255.0;
    // Never green: two colours that are each allowed can mix to one that is not, a sky blue and a
    // gold to a grey-green between them, so no pixel has more green in it than red or blue.
    c.g = min(c.g, max(c.r, c.b));
    return half4(half3(c), 1.0);
}
"""

    /** How far a sun's light is drawn, as a multiple of its radius. Nothing is drawn past it. */
    const val SUN_REACH = 3f

    /** How much smaller than the screen the space is drawn, each way. */
    const val SPACE_DOWN = 4

    /** How many stars light the gas at once. */
    const val LIGHTS = 8

    /** How far a star's light reaches into the gas, in widths of the star as it is drawn. */
    const val GAS_LIGHT_WIDTHS = 16f

    /** The least a star's light reaches, in dp, so the faintest point still lights a wisp. */
    const val GAS_LIGHT_LEAST_DP = 4f

    /** The farthest a star's light reaches into the gas, as a share of the screen's width. */
    const val GAS_LIGHT_MOST = 0.3f

    /**
     * How far a star's light reaches into the gas, in pixels (#535): [GAS_LIGHT_WIDTHS] of its own
     * radius as drawn, [radiusPx], a little further for a bright star ([brightness], `0..2`), never
     * under [GAS_LIGHT_LEAST_DP] and never more than [GAS_LIGHT_MOST] of a screen [width] pixels
     * across. It used to be a fixed share of the sky, so a small star made a whole cloud glow.
     * Only the star's size and brightness reach it, and neither carries a mood or a count.
     */
    fun gasLightReach(radiusPx: Float, brightness: Float, pxPerDp: Float, width: Float): Float {
        val wide = radiusPx * GAS_LIGHT_WIDTHS * (0.7f + 0.3f * brightness.coerceIn(0f, 2f))
        return wide.coerceIn(GAS_LIGHT_LEAST_DP * pxPerDp, maxOf(GAS_LIGHT_MOST * width, GAS_LIGHT_LEAST_DP * pxPerDp))
    }

    /** Where the farthest of the lights starts to ease out, as a share of the next star's distance. */
    const val LIGHT_EASE_FROM = 0.75f

    /**
     * The stars that light the gas: the [LIGHTS] nearest ([cx], [cy]), the middle of the screen in
     * the sky's units, leaving out every star [hidden] marks. Their indices go into [chosen] and how
     * strongly each lights the gas into [weights], `0..1`; the count is returned.
     *
     * Each light eases out as it nears the distance of the next star in line, and is gone at it, so
     * as the view moves a light hands over to the next one smoothly and never pops. Nearness to the
     * middle of the screen is all that is asked: nothing about a star but where it is reaches here.
     */
    fun gasLights(
        xs: FloatArray,
        ys: FloatArray,
        hidden: BooleanArray?,
        cx: Float,
        cy: Float,
        chosen: IntArray,
        weights: FloatArray,
    ): Int {
        val keep = LIGHTS + 1
        val index = IntArray(keep) { -1 }
        val distance = FloatArray(keep) { Float.MAX_VALUE }
        var found = 0
        for (i in xs.indices) {
            if (hidden != null && i < hidden.size && hidden[i]) continue
            val dx = xs[i] - cx
            val dy = ys[i] - cy
            val d = dx * dx + dy * dy
            if (found == keep && d >= distance[keep - 1]) continue
            var at = if (found < keep) found++ else keep - 1
            while (at > 0 && distance[at - 1] > d) {
                distance[at] = distance[at - 1]
                index[at] = index[at - 1]
                at--
            }
            distance[at] = d
            index[at] = i
        }
        val count = minOf(found, LIGHTS, chosen.size, weights.size)
        val edge = if (found > LIGHTS) Math.sqrt(distance[LIGHTS].toDouble()).toFloat() else Float.MAX_VALUE
        for (k in 0 until count) {
            chosen[k] = index[k]
            val d = Math.sqrt(distance[k].toDouble()).toFloat()
            weights[k] = if (edge == Float.MAX_VALUE) 1f else 1f - smoothstep(LIGHT_EASE_FROM * edge, edge, d)
        }
        return count
    }

    /**
     * Where in its noise this sky's space begins, `[0, 50)` each way, from the sky's seed alone. Two
     * skies are two different spaces, and one sky is the same space every time it is opened.
     */
    fun spaceSeed(seed: Long): FloatArray = floatArrayOf(
        SkyRandom.unit(SkyRandom.mix(seed xor SPACE_SALT, 1L)) * SPACE_SEED_RANGE,
        SkyRandom.unit(SkyRandom.mix(seed xor SPACE_SALT, 2L)) * SPACE_SEED_RANGE,
    )

    /**
     * A sun's own number, `[0, 1)`, from its identity ([Sky.identityOf]): where its granules lie,
     * how fast it turns and where its spots are. Never from its mood or its day.
     */
    fun sunSeed(identity: Long): Float = SkyRandom.unit(SkyRandom.mix(identity xor SUN_SALT))

    /** One channel of a packed `0xRRGGBB` colour, `0..255`, in linear light, for a shader. */
    fun linear(rgb: Int, shift: Int): Float =
        Math.pow(((rgb shr shift) and 0xFF) / 255.0, 2.2).toFloat()

    private fun smoothstep(from: Float, to: Float, x: Float): Float {
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private const val SPACE_SEED_RANGE = 50f
    private const val SPACE_SALT = 0x5_9A_CEL
    private const val SUN_SALT = 0x5_0_5_0_5L
}
