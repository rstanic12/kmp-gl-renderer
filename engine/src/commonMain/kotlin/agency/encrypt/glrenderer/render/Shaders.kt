package agency.encrypt.glrenderer.render

/**
 * GLSL ES 3.00 sources. Shared pieces (the surface read, the BRDF, shadows, point light falloff) are Kotlin strings
 * spliced into each program, so the deferred and forward+ paths shade with literally the same code.
 */
internal object Shaders {
    const val POSITION = 0
    const val NORMAL = 1
    const val UV = 2

    /** Per-instance attributes: model matrix (4 columns), normal matrix (3 columns), tint. */
    const val MODEL = 3
    const val NORMAL_MATRIX = 7
    const val TINT = 10

    const val MAX_LIGHTS = 64

    private const val HEADER = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler2DShadow;
const float PI = 3.14159265;
"""

    val GEOMETRY_VERTEX = """$HEADER
layout(location = $POSITION) in vec3 aPosition;
layout(location = $NORMAL) in vec3 aNormal;
layout(location = $UV) in vec2 aUv;
layout(location = $MODEL) in mat4 aModel;
layout(location = $NORMAL_MATRIX) in mat3 aNormalMatrix;
layout(location = $TINT) in vec4 aTint;

uniform mat4 uViewProjection;

out vec3 vWorldPosition;
out vec3 vNormal;
out vec2 vUv;
out vec4 vTint;

void main() {
    vec4 world = aModel * vec4(aPosition, 1.0);
    vWorldPosition = world.xyz;
    vNormal = aNormalMatrix * aNormal;
    vUv = aUv;
    vTint = aTint;
    gl_Position = uViewProjection * world;
}
"""

    /** A triangle that covers the screen, generated from gl_VertexID: no vertex buffer needed. */
    val FULLSCREEN_VERTEX = """$HEADER
out vec2 vUv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

    /** Depth only. */
    val SHADOW_FRAGMENT = """$HEADER
void main() {}
"""

    private const val SURFACE = """
struct Surface {
    vec3 albedo;
    float metallic;
    float roughness;
    vec3 normal;
};
"""

    /** Reads the material for the current fragment of a mesh. Back faces flip the normal, for open meshes. */
    private const val MATERIAL = """
in vec3 vWorldPosition;
in vec3 vNormal;
in vec2 vUv;
in vec4 vTint;

uniform vec3 uBaseColor;
uniform float uMetallic;
uniform float uRoughness;
uniform vec3 uEmissive;
uniform float uOpacity;
uniform int uHasBaseColorMap;
uniform sampler2D uBaseColorMap;

Surface materialSurface() {
    Surface s;
    s.albedo = uBaseColor * vTint.rgb;
    // The texture is sRGB8_ALPHA8, so the hardware has already converted it to linear.
    if (uHasBaseColorMap == 1) s.albedo *= texture(uBaseColorMap, vUv).rgb;
    s.metallic = uMetallic;
    s.roughness = uRoughness;
    s.normal = normalize(vNormal);
    if (!gl_FrontFacing) s.normal = -s.normal;
    return s;
}
"""

    /** Cook-Torrance: GGX distribution, Schlick-GGX visibility, Schlick Fresnel; Lambert diffuse. */
    private const val BRDF = """
vec3 brdf(Surface s, vec3 v, vec3 l) {
    vec3 n = s.normal;
    float nDotL = dot(n, l);
    if (nDotL <= 0.0) return vec3(0.0);
    vec3 h = normalize(v + l);
    float nDotV = max(dot(n, v), 1e-4);
    float nDotH = max(dot(n, h), 0.0);
    float vDotH = max(dot(v, h), 0.0);

    float a = s.roughness * s.roughness;
    float a2 = a * a;
    float d = nDotH * nDotH * (a2 - 1.0) + 1.0;
    float distribution = a2 / (PI * d * d);
    float k = a * 0.5;
    float visibility = 1.0 / ((nDotV * (1.0 - k) + k) * (nDotL * (1.0 - k) + k) * 4.0);
    vec3 f0 = mix(vec3(0.04), s.albedo, s.metallic);
    vec3 fresnel = f0 + (1.0 - f0) * pow(1.0 - vDotH, 5.0);

    vec3 specular = distribution * visibility * fresnel;
    vec3 diffuse = (1.0 - fresnel) * (1.0 - s.metallic) * s.albedo / PI;
    return (diffuse + specular) * nDotL;
}
"""

    /**
     * The scene's lights other than point lights: a directional key light with a PCF shadow map,
     * and a sky/ground gradient for ambient, with Karis' analytic split-sum approximation for its specular.
     */
    private const val SCENE_LIGHTING = """
uniform vec3 uCameraPosition;
uniform vec3 uLightDirection;
uniform vec3 uLightColor;
uniform vec3 uSkyColor;
uniform vec3 uGroundColor;
uniform int uShadows;
uniform mat4 uShadowMatrix;
uniform float uShadowNormalOffset;
uniform sampler2DShadow uShadowMap;

float shadow(vec3 worldPosition, vec3 n) {
    if (uShadows == 0) return 1.0;
    vec4 p = uShadowMatrix * vec4(worldPosition + n * uShadowNormalOffset, 1.0);
    vec3 c = p.xyz / p.w;
    if (c.z >= 1.0 || c.x <= 0.0 || c.y <= 0.0 || c.x >= 1.0 || c.y >= 1.0) return 1.0;
    vec2 texel = 1.0 / vec2(textureSize(uShadowMap, 0));
    float lit = 0.0;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            lit += texture(uShadowMap, vec3(c.xy + vec2(float(x), float(y)) * texel, c.z));
        }
    }
    return lit / 9.0;
}

vec3 ambient(Surface s, vec3 v) {
    vec3 n = s.normal;
    vec3 irradiance = mix(uGroundColor, uSkyColor, n.y * 0.5 + 0.5);
    vec3 r = reflect(-v, n);
    vec3 environment = mix(mix(uGroundColor, uSkyColor, smoothstep(-0.15, 0.15, r.y)), irradiance, s.roughness);
    float nDotV = max(dot(n, v), 1e-4);
    vec4 r0 = s.roughness * vec4(-1.0, -0.0275, -0.572, 0.022) + vec4(1.0, 0.0425, 1.04, -0.04);
    float a004 = min(r0.x * r0.x, exp2(-9.28 * nDotV)) * r0.x + r0.y;
    vec2 scaleBias = vec2(-1.04, 1.04) * a004 + r0.zw;
    vec3 f0 = mix(vec3(0.04), s.albedo, s.metallic);
    return irradiance * s.albedo * (1.0 - s.metallic) + environment * (f0 * scaleBias.x + scaleBias.y);
}

vec3 sceneLighting(Surface s, vec3 worldPosition, vec3 v) {
    return ambient(s, v) + uLightColor * brdf(s, v, uLightDirection) * shadow(worldPosition, s.normal);
}
"""

    /** Inverse-square falloff windowed to reach exactly zero at the radius (the Frostbite / UE4 form). */
    private const val POINT_LIGHT = """
vec3 pointLight(Surface s, vec3 worldPosition, vec3 v, vec4 positionRadius, vec3 color) {
    vec3 toLight = positionRadius.xyz - worldPosition;
    float distance2 = max(dot(toLight, toLight), 1e-4);
    float ratio = distance2 / (positionRadius.w * positionRadius.w);
    float window = clamp(1.0 - ratio * ratio, 0.0, 1.0);
    float attenuation = window * window / (distance2 + 1.0);
    return color * attenuation * brdf(s, v, toLight * inversesqrt(distance2));
}
"""

    /** Deferred geometry pass. Albedo is stored as its square root so 8 bits keep detail in the darks. */
    val GBUFFER_FRAGMENT = """$HEADER$SURFACE$MATERIAL
layout(location = 0) out vec4 outAlbedoMetallic;
layout(location = 1) out vec4 outNormalRoughness;
layout(location = 2) out vec4 outEmissive;

void main() {
    Surface s = materialSurface();
    outAlbedoMetallic = vec4(sqrt(s.albedo), s.metallic);
    outNormalRoughness = vec4(s.normal * 0.5 + 0.5, s.roughness);
    outEmissive = vec4(uEmissive * vTint.rgb, 1.0);
}
"""

    private const val GBUFFER_READ = """
in vec2 vUv;
uniform sampler2D uAlbedoMetallic;
uniform sampler2D uNormalRoughness;
uniform sampler2D uDepth;
uniform mat4 uInverseViewProjection;

bool readGBuffer(out Surface s, out vec3 worldPosition) {
    float depth = texture(uDepth, vUv).r;
    if (depth >= 1.0) return false;
    vec4 albedoMetallic = texture(uAlbedoMetallic, vUv);
    vec4 normalRoughness = texture(uNormalRoughness, vUv);
    s.albedo = albedoMetallic.rgb * albedoMetallic.rgb;
    s.metallic = albedoMetallic.a;
    s.normal = normalize(normalRoughness.xyz * 2.0 - 1.0);
    s.roughness = normalRoughness.a;
    vec4 world = uInverseViewProjection * vec4(vec3(vUv, depth) * 2.0 - 1.0, 1.0);
    worldPosition = world.xyz / world.w;
    return true;
}
"""

    /** Deferred, once per frame over the whole screen: ambient, key light and the background. */
    val DEFERRED_SCENE_FRAGMENT = """$HEADER$SURFACE$BRDF$SCENE_LIGHTING$GBUFFER_READ
uniform vec3 uBackground;
out vec4 fragColor;

void main() {
    Surface s;
    vec3 worldPosition;
    if (!readGBuffer(s, worldPosition)) {
        fragColor = vec4(uBackground, 1.0);
        return;
    }
    vec3 v = normalize(uCameraPosition - worldPosition);
    fragColor = vec4(sceneLighting(s, worldPosition, v), 1.0);
}
"""

    /** Deferred, once per point light, scissored to the light's screen rectangle and added on. */
    val DEFERRED_POINT_FRAGMENT = """$HEADER$SURFACE$BRDF$POINT_LIGHT$GBUFFER_READ
uniform vec3 uCameraPosition;
uniform vec3 uPointLightPosition;
uniform float uPointLightRadius;
uniform vec3 uPointLightColor;
out vec4 fragColor;

void main() {
    Surface s;
    vec3 worldPosition;
    if (!readGBuffer(s, worldPosition)) discard;
    vec3 v = normalize(uCameraPosition - worldPosition);
    vec4 positionRadius = vec4(uPointLightPosition, uPointLightRadius);
    fragColor = vec4(pointLight(s, worldPosition, v, positionRadius, uPointLightColor), 1.0);
}
"""

    /**
     * Forward+: shades everything in one pass, looping over only the lights listed for this pixel's tile.
     * Also draws transparent surfaces in the deferred path, which a G-buffer cannot hold.
     */
    val FORWARD_FRAGMENT = """$HEADER$SURFACE$MATERIAL$BRDF$SCENE_LIGHTING$POINT_LIGHT
uniform sampler2D uLights;
uniform sampler2D uTiles;
out vec4 fragColor;

float tileSlot(ivec2 tile, int slot) {
    vec4 texel = texelFetch(uTiles, ivec2(tile.x * ${TileGrid.TEXELS_PER_TILE} + slot / 4, tile.y), 0);
    int component = slot - (slot / 4) * 4;
    return component == 0 ? texel.x : component == 1 ? texel.y : component == 2 ? texel.z : texel.w;
}

void main() {
    Surface s = materialSurface();
    vec3 v = normalize(uCameraPosition - vWorldPosition);
    vec3 color = uEmissive * vTint.rgb + sceneLighting(s, vWorldPosition, v);

    ivec2 tile = ivec2(gl_FragCoord.xy) / ${TileGrid.TILE_SIZE};
    int count = int(tileSlot(tile, 0));
    for (int i = 1; i <= count; i++) {
        int light = int(tileSlot(tile, i));
        vec4 positionRadius = texelFetch(uLights, ivec2(light, 0), 0);
        vec3 lightColor = texelFetch(uLights, ivec2(light, 1), 0).rgb;
        color += pointLight(s, vWorldPosition, v, positionRadius, lightColor);
    }
    fragColor = vec4(color, uOpacity * vTint.a);
}
"""

    /**
     * Bloom, downsampling half: four bilinear taps cover a 4x4 block of the source.
     * The first step also keeps only what is brighter than the threshold, with a soft knee.
     */
    val BLOOM_DOWN_FRAGMENT = """$HEADER
in vec2 vUv;
uniform sampler2D uSource;
uniform vec2 uTexel;
uniform int uPrefilter;
uniform float uThreshold;
out vec4 fragColor;

void main() {
    vec3 c = texture(uSource, vUv + uTexel * vec2(-1.0, -1.0)).rgb
           + texture(uSource, vUv + uTexel * vec2(1.0, -1.0)).rgb
           + texture(uSource, vUv + uTexel * vec2(-1.0, 1.0)).rgb
           + texture(uSource, vUv + uTexel * vec2(1.0, 1.0)).rgb;
    c *= 0.25;
    if (uPrefilter == 1) {
        c = min(c, vec3(64.0));
        float brightness = max(c.r, max(c.g, c.b));
        float knee = uThreshold * 0.5;
        float soft = clamp(brightness - uThreshold + knee, 0.0, 2.0 * knee);
        soft = soft * soft / (4.0 * knee + 1e-4);
        c *= max(soft, brightness - uThreshold) / max(brightness, 1e-4);
    }
    fragColor = vec4(c, 1.0);
}
"""

    /** Bloom, upsampling half: a 3x3 tent filter, added onto the next larger level. */
    val BLOOM_UP_FRAGMENT = """$HEADER
in vec2 vUv;
uniform sampler2D uSource;
uniform vec2 uTexel;
out vec4 fragColor;

void main() {
    vec3 c = texture(uSource, vUv).rgb * 4.0;
    c += (texture(uSource, vUv + vec2(uTexel.x, 0.0)).rgb + texture(uSource, vUv - vec2(uTexel.x, 0.0)).rgb
        + texture(uSource, vUv + vec2(0.0, uTexel.y)).rgb + texture(uSource, vUv - vec2(0.0, uTexel.y)).rgb) * 2.0;
    c += texture(uSource, vUv + uTexel).rgb + texture(uSource, vUv - uTexel).rgb
       + texture(uSource, vUv + vec2(uTexel.x, -uTexel.y)).rgb + texture(uSource, vUv + vec2(-uTexel.x, uTexel.y)).rgb;
    fragColor = vec4(c / 16.0, 1.0);
}
"""

    /** HDR to display: bloom, exposure, the ACES filmic curve (Narkowicz's fit), then sRGB gamma. */
    val COMPOSITE_FRAGMENT = """$HEADER
in vec2 vUv;
uniform sampler2D uHdr;
uniform sampler2D uBloom;
uniform int uBloomEnabled;
uniform float uBloomStrength;
uniform float uExposure;
out vec4 fragColor;

vec3 aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

void main() {
    vec3 c = texture(uHdr, vUv).rgb;
    if (uBloomEnabled == 1) c += texture(uBloom, vUv).rgb * uBloomStrength;
    fragColor = vec4(pow(aces(c * uExposure), vec3(1.0 / 2.2)), 1.0);
}
"""

    /** FXAA in its compact form (Lottes' FXAA 2): blur along the local edge direction where contrast is high. */
    val FXAA_FRAGMENT = """$HEADER
in vec2 vUv;
uniform sampler2D uSource;
uniform vec2 uTexel;
out vec4 fragColor;

const vec3 LUMA = vec3(0.299, 0.587, 0.114);
const float REDUCE_MIN = 1.0 / 128.0;
const float REDUCE_MUL = 1.0 / 8.0;
const float SPAN_MAX = 8.0;

void main() {
    vec3 rgbNW = texture(uSource, vUv + vec2(-1.0, -1.0) * uTexel).rgb;
    vec3 rgbNE = texture(uSource, vUv + vec2(1.0, -1.0) * uTexel).rgb;
    vec3 rgbSW = texture(uSource, vUv + vec2(-1.0, 1.0) * uTexel).rgb;
    vec3 rgbSE = texture(uSource, vUv + vec2(1.0, 1.0) * uTexel).rgb;
    vec3 rgbM = texture(uSource, vUv).rgb;
    float lumaNW = dot(rgbNW, LUMA);
    float lumaNE = dot(rgbNE, LUMA);
    float lumaSW = dot(rgbSW, LUMA);
    float lumaSE = dot(rgbSE, LUMA);
    float lumaM = dot(rgbM, LUMA);
    float lumaMin = min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)));
    float lumaMax = max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));

    vec2 dir = vec2(-((lumaNW + lumaNE) - (lumaSW + lumaSE)), (lumaNW + lumaSW) - (lumaNE + lumaSE));
    float dirReduce = max((lumaNW + lumaNE + lumaSW + lumaSE) * 0.25 * REDUCE_MUL, REDUCE_MIN);
    float rcpDirMin = 1.0 / (min(abs(dir.x), abs(dir.y)) + dirReduce);
    dir = clamp(dir * rcpDirMin, vec2(-SPAN_MAX), vec2(SPAN_MAX)) * uTexel;

    vec3 rgbA = 0.5 * (texture(uSource, vUv + dir * (1.0 / 3.0 - 0.5)).rgb + texture(uSource, vUv + dir * (2.0 / 3.0 - 0.5)).rgb);
    vec3 rgbB = rgbA * 0.5 + 0.25 * (texture(uSource, vUv - dir * 0.5).rgb + texture(uSource, vUv + dir * 0.5).rgb);
    float lumaB = dot(rgbB, LUMA);
    fragColor = vec4(lumaB < lumaMin || lumaB > lumaMax ? rgbA : rgbB, 1.0);
}
"""
}
