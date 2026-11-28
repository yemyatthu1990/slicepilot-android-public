package world.yemyat.slicepilot.preview

object Shaders {
    const val VERTEX = """#version 300 es
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
uniform mat4 uModel;
uniform mat4 uViewProj;
out vec3 vNormal;
out vec3 vWorldPos;
void main() {
    vec4 world = uModel * vec4(aPosition, 1.0);
    vWorldPos = world.xyz;
    vNormal = mat3(uModel) * aNormal;
    gl_Position = uViewProj * world;
}
"""

    const val FRAGMENT = """#version 300 es
precision highp float;
in vec3 vNormal;
in vec3 vWorldPos;
uniform vec3 uColor;
uniform float uAlpha;
uniform float uRevealZ;
uniform vec2 uPlateMin;
uniform vec2 uPlateMax;
uniform float uPlateEdge;
out vec4 fragColor;
void main() {
    vec3 fn = cross(dFdx(vWorldPos), dFdy(vWorldPos));
    if (vWorldPos.z > uRevealZ) {
        discard;
    }
    vec3 n;
    if (dot(vNormal, vNormal) > 0.0001) {
        n = normalize(vNormal);
        if (!gl_FrontFacing) {
            n = -n;
        }
    } else {
        float len2 = dot(fn, fn);
        n = len2 > 1e-12 ? fn * inversesqrt(len2) : vec3(0.0, 0.0, 1.0);
    }
    vec3 keyDir = normalize(vec3(0.55, 0.20, 0.85));
    vec3 fillDir = normalize(vec3(-0.45, -0.55, 0.40));
    float key = max(dot(n, keyDir), 0.0);
    float fill = max(dot(n, fillDir), 0.0);
    float hemi = 0.38 + 0.20 * (0.5 * n.z + 0.5);
    vec3 lit = uColor * (hemi + key * 0.62 + fill * 0.25);
    float band = smoothstep(uRevealZ - 1.2, uRevealZ, vWorldPos.z);
    lit = mix(lit, vec3(1.0, 0.85, 0.32), band * 0.6);
    if (uPlateEdge > 0.0) {
        vec2 d = min(vWorldPos.xy - uPlateMin, uPlateMax - vWorldPos.xy);
        float border = 1.0 - smoothstep(0.0, uPlateEdge, min(d.x, d.y));
        lit = mix(lit, vec3(0.82, 0.86, 0.93), border * 0.85);
    }
    fragColor = vec4(min(lit, vec3(1.0)), uAlpha);
}
"""
}