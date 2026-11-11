package agency.encrypt.glrenderer.render

internal object Shaders {
    const val POSITION = 0
    const val NORMAL = 1
    const val UV = 2

    val VERTEX = """
        #version 300 es
        layout(location = $POSITION) in vec3 aPosition;
        layout(location = $NORMAL) in vec3 aNormal;
        layout(location = $UV) in vec2 aUv;

        uniform mat4 uModel;
        uniform mat4 uViewProjection;
        uniform mat3 uNormalMatrix;

        out vec3 vWorldPosition;
        out vec3 vNormal;
        out vec2 vUv;

        void main() {
            vec4 world = uModel * vec4(aPosition, 1.0);
            vWorldPosition = world.xyz;
            vNormal = uNormalMatrix * aNormal;
            vUv = aUv;
            gl_Position = uViewProjection * world;
        }
    """.trimIndent()

    /**
     * Per-pixel Blinn-Phong with one directional key light and a sky/ground hemisphere fill.
     * Colours are converted to linear space for lighting and back to sRGB for output.
     * Back faces are lit with a flipped normal, so open meshes (the inside of the teapot) shade correctly.
     */
    val FRAGMENT = """
        #version 300 es
        precision mediump float;

        in vec3 vWorldPosition;
        in vec3 vNormal;
        in vec2 vUv;

        uniform vec3 uCameraPosition;
        uniform vec3 uLightDirection;
        uniform vec3 uLightColor;
        uniform vec3 uSkyColor;
        uniform vec3 uGroundColor;

        uniform vec3 uDiffuse;
        uniform vec3 uSpecular;
        uniform float uShininess;
        uniform float uOpacity;
        uniform int uHasDiffuseMap;
        uniform sampler2D uDiffuseMap;

        out vec4 fragColor;

        vec3 toLinear(vec3 c) { return pow(c, vec3(2.2)); }

        void main() {
            vec3 n = normalize(vNormal);
            if (!gl_FrontFacing) n = -n;
            vec3 v = normalize(uCameraPosition - vWorldPosition);
            vec3 l = uLightDirection;
            vec3 h = normalize(l + v);

            vec3 albedo = toLinear(uDiffuse);
            if (uHasDiffuseMap == 1) albedo *= toLinear(texture(uDiffuseMap, vUv).rgb);

            float diffuse = max(dot(n, l), 0.0);
            float specular = diffuse > 0.0 ? pow(max(dot(n, h), 0.0), uShininess) : 0.0;
            // Normalisation keeps highlights from tight exponents from going dim.
            specular *= (uShininess + 8.0) / 25.0;

            vec3 ambient = mix(uGroundColor, uSkyColor, n.y * 0.5 + 0.5) * albedo;
            vec3 color = ambient + uLightColor * (albedo * diffuse + toLinear(uSpecular) * specular);

            fragColor = vec4(pow(color, vec3(1.0 / 2.2)), uOpacity);
        }
    """.trimIndent()
}
