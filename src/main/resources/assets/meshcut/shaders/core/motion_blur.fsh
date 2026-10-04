#version 150

uniform sampler2D Sampler0; // depth of the opaque scene
uniform vec4 Params;        // aspect, width, gain
uniform vec4 PoseA0;
uniform vec4 PoseB0;
uniform vec4 PoseA1;
uniform vec4 PoseB1;
uniform vec4 PoseA2;
uniform vec4 PoseB2;
uniform vec4 PoseA3;
uniform vec4 PoseB3;
uniform vec4 PoseA4;
uniform vec4 PoseB4;
uniform vec4 PoseA5;
uniform vec4 PoseB5;

in vec2 texCoord;
out vec4 fragColor;

const vec3 BASE = vec3(0.15, 0.55, 1.0);
const vec3 TIP = vec3(0.8, 0.95, 1.0);
const int POSES = 6;
const int STEPS = 4;

// PoseA: base.xy, tip.xy of the blade in clip space; PoseB: base depth, tip depth (0..1), age (0 now .. 1 oldest), valid.
// The blade is swept from pose to pose, as many positions in between as the shutter is open for, and each pixel adds up
// how long the blade was over it: a motion blur of the blade, longer where it moves faster, brighter at its tip.
void main() {
    vec4 A[POSES] = vec4[POSES](PoseA0, PoseA1, PoseA2, PoseA3, PoseA4, PoseA5);
    vec4 B[POSES] = vec4[POSES](PoseB0, PoseB1, PoseB2, PoseB3, PoseB4, PoseB5);
    float aspect = Params.x;
    float width = Params.y;
    vec2 scale = vec2(aspect, 1.0);
    vec2 p = (texCoord * 2.0 - 1.0) * scale;
    float sceneDepth = texture(Sampler0, texCoord).r;
    vec3 sum = vec3(0.0);
    for (int i = 0; i < POSES - 1; i++) {
        if (B[i].w < 0.5 || B[i + 1].w < 0.5) {
            continue;
        }
        for (int j = 0; j < STEPS; j++) {
            float u = (float(j) + 0.5) / float(STEPS);
            vec2 b = mix(A[i].xy, A[i + 1].xy, u) * scale;
            vec2 t = mix(A[i].zw, A[i + 1].zw, u) * scale;
            vec2 d = t - b;
            float s = clamp(dot(p - b, d) / max(dot(d, d), 1e-8), 0.0, 1.0);
            float dist = length(p - (b + d * s));
            float depth = mix(mix(B[i].x, B[i].y, s), mix(B[i + 1].x, B[i + 1].y, s), u);
            if (depth > sceneDepth + 0.0003) {
                continue; // behind something
            }
            float age = mix(B[i].z, B[i + 1].z, u);
            float w = exp(-(dist * dist) / (width * width)) * (1.0 - age) * (1.0 - age);
            sum += mix(BASE, TIP, s) * w * (0.35 + 0.65 * s);
        }
    }
    fragColor = vec4(sum * Params.z / float(STEPS), 0.0);
}
