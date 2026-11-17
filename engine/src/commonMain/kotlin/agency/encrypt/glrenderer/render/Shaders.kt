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

    /**
     * Point light shadows live in one atlas: a row per shadowed light, six cube faces across. Each face is rendered
     * with a field of view a little wider than 90 degrees, so PCF taps at a face's edge stay inside its tile.
     */
    const val POINT_SHADOW_SLOTS = 8
    const val POINT_SHADOW_FACE_SIZE = 256
    const val POINT_SHADOW_NEAR = 0.05f
    const val POINT_SHADOW_FOV_SCALE = POINT_SHADOW_FACE_SIZE / (POINT_SHADOW_FACE_SIZE - 4f)

    /** Cube face directions and up vectors, in face order +x, -x, +y, -y, +z, -z; shared with the renderer. */
    val CUBE_FORWARD = listOf(
        floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f),
        floatArrayOf(0f, -1f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f),
    )
    val CUBE_UP = listOf(
        floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f),
        floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 1f, 0f),
    )

    private fun vec3Array(vectors: List<FloatArray>) =
        "vec3[6](" + vectors.joinToString(", ") { "vec3(${it[0]}, ${it[1]}, ${it[2]})" } + ")"

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

    /**
     * Inverse-square falloff windowed to reach exactly zero at the radius (the Frostbite / UE4 form),
     * times the light's cube shadow when it has an atlas [slot] (-1 for none).
     *
     * The shadow lookup picks the cube face by the major axis of the light-to-surface direction, projects onto that
     * face exactly as the face was rendered (lookAt + perspective), and turns the distance along the face's axis into
     * the depth the perspective projection would have written.
     */
    private val POINT_LIGHT = """
uniform sampler2DShadow uPointShadowAtlas;

const vec3 CUBE_FORWARD[6] = ${vec3Array(CUBE_FORWARD)};
const vec3 CUBE_UP[6] = ${vec3Array(CUBE_UP)};
const float SHADOW_NEAR = $POINT_SHADOW_NEAR;
const float FOV_SCALE = $POINT_SHADOW_FOV_SCALE;
const float FACE_SIZE = ${POINT_SHADOW_FACE_SIZE}.0;
const vec2 ATLAS_TILES = vec2(6.0, ${POINT_SHADOW_SLOTS}.0);

float pointShadow(int slot, vec3 lightPosition, float radius, vec3 worldPosition, vec3 n) {
    if (slot < 0) return 1.0;
    vec3 d = worldPosition - lightPosition;
    // About one and a half texels of normal offset, which grow with distance from the light.
    d += n * (length(d) * 3.0 * FOV_SCALE / FACE_SIZE);
    vec3 a = abs(d);
    int face = a.x >= a.y && a.x >= a.z ? (d.x >= 0.0 ? 0 : 1) : (a.y >= a.z ? (d.y >= 0.0 ? 2 : 3) : (d.z >= 0.0 ? 4 : 5));
    vec3 forward = CUBE_FORWARD[face];
    vec3 right = normalize(cross(forward, CUBE_UP[face]));
    vec3 up = cross(right, forward);
    float along = dot(d, forward);
    vec2 ndc = vec2(dot(d, right), dot(d, up)) / (along * FOV_SCALE);
    float far = radius;
    float depth = ((far + SHADOW_NEAR) / (far - SHADOW_NEAR) - 2.0 * far * SHADOW_NEAR / ((far - SHADOW_NEAR) * along)) * 0.5 + 0.5;
    if (depth >= 1.0) return 1.0;
    vec2 uv = (vec2(float(face), float(slot)) + ndc * 0.5 + 0.5) / ATLAS_TILES;
    vec2 texel = 1.0 / (ATLAS_TILES * FACE_SIZE);
    float lit = 0.0;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            lit += texture(uPointShadowAtlas, vec3(uv + vec2(float(x), float(y)) * texel, depth));
        }
    }
    return lit / 9.0;
}

vec3 pointLight(Surface s, vec3 worldPosition, vec3 v, vec4 positionRadius, vec3 color, int slot) {
    vec3 toLight = positionRadius.xyz - worldPosition;
    float distance2 = max(dot(toLight, toLight), 1e-4);
    float ratio = distance2 / (positionRadius.w * positionRadius.w);
    float window = clamp(1.0 - ratio * ratio, 0.0, 1.0);
    if (window <= 0.0) return vec3(0.0);
    float attenuation = window * window / (distance2 + 1.0);
    vec3 lit = color * attenuation * brdf(s, v, toLight * inversesqrt(distance2));
    if (lit == vec3(0.0)) return lit;
    return lit * pointShadow(slot, positionRadius.xyz, positionRadius.w, worldPosition, s.normal);
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
uniform int uPointShadowSlot;
out vec4 fragColor;

void main() {
    Surface s;
    vec3 worldPosition;
    if (!readGBuffer(s, worldPosition)) discard;
    vec3 v = normalize(uCameraPosition - worldPosition);
    vec4 positionRadius = vec4(uPointLightPosition, uPointLightRadius);
    fragColor = vec4(pointLight(s, worldPosition, v, positionRadius, uPointLightColor, uPointShadowSlot), 1.0);
}
"""

    /**
     * Forward+ (clustered): shades everything in one pass, looping over only the lights listed for this pixel's
     * cluster, found from its screen tile and its view depth's slice.
     * Also draws transparent surfaces in the deferred path, which a G-buffer cannot hold.
     */
    val FORWARD_FRAGMENT = """$HEADER$SURFACE$MATERIAL$BRDF$SCENE_LIGHTING$POINT_LIGHT
uniform sampler2D uLights;
uniform sampler2D uClusters;
uniform sampler2D uLightIndices;
uniform vec3 uCameraForward;
uniform int uClusterTilesX;
out vec4 fragColor;

float lightIndex(int i) {
    int texel = i / 4;
    vec4 t = texelFetch(uLightIndices, ivec2(texel % ${ClusterGrid.INDEX_TEXTURE_WIDTH}, texel / ${ClusterGrid.INDEX_TEXTURE_WIDTH}), 0);
    int component = i - texel * 4;
    return component == 0 ? t.x : component == 1 ? t.y : component == 2 ? t.z : t.w;
}

void main() {
    Surface s = materialSurface();
    vec3 v = normalize(uCameraPosition - vWorldPosition);
    vec3 color = uEmissive * vTint.rgb + sceneLighting(s, vWorldPosition, v);

    float viewDepth = max(dot(vWorldPosition - uCameraPosition, uCameraForward), 1e-4);
    int slice = clamp(int(floor(log(viewDepth) * ${ClusterGrid.SLICE_SCALE} + ${ClusterGrid.SLICE_BIAS})), 0, ${ClusterGrid.SLICES - 1});
    ivec2 tile = ivec2(gl_FragCoord.xy) / ${ClusterGrid.TILE_SIZE};
    vec4 cluster = texelFetch(uClusters, ivec2(tile.x + slice * uClusterTilesX, tile.y), 0);
    int offset = int(cluster.x);
    int count = int(cluster.y);
    for (int i = 0; i < count; i++) {
        int light = int(lightIndex(offset + i));
        vec4 positionRadius = texelFetch(uLights, ivec2(light, 0), 0);
        vec4 colorSlot = texelFetch(uLights, ivec2(light, 1), 0);
        color += pointLight(s, vWorldPosition, v, positionRadius, colorSlot.rgb, int(colorSlot.w));
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
