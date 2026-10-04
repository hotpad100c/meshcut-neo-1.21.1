#version 150

uniform sampler2D Sampler0; // depth of the opaque scene
uniform sampler2D Sampler1; // the glow this frame: what shines, sharp
uniform sampler2D Sampler2; // the bloom this frame: the glow blurred
uniform vec4 Params;        // aspect, width, gain, bloom strength
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

const int POSES = 6;
const int STEPS = 4;

// PoseA: base.xy, tip.xy of the blade in clip space; PoseB: base depth, tip depth (0..1), age (0 now .. 1 oldest), valid.
// Pose 0 is the blade as it is now.
//
// The blade is swept from pose to pose, as many positions in between as the shutter is open for. Where a position of
// the blade passed over a pixel, the pixel takes the light of that same point of the blade as it shines now: the glow
// and the bloom of this frame, read where that point is now. The trail is the blade's own light, dragged back along its
// path: its colour, its width, its halo, whatever the blade looks like.
void main() {
    vec4 A[POSES] = vec4[POSES](PoseA0, PoseA1, PoseA2, PoseA3, PoseA4, PoseA5);
    vec4 B[POSES] = vec4[POSES](PoseB0, PoseB1, PoseB2, PoseB3, PoseB4, PoseB5);
    float aspect = Params.x;
    float width = Params.y;
    vec2 scale = vec2(aspect, 1.0);
    vec2 p = (texCoord * 2.0 - 1.0) * scale;
    float sceneDepth = texture(Sampler0, texCoord).r;
    vec2 nowBase = A[0].xy * scale;
    vec2 nowTip = A[0].zw * scale;
    vec3 sum = vec3(0.0);
    float weightSum = 0.0;
    if (B[0].w < 0.5) {
        fragColor = vec4(0.0);
        return;
    }
    for (int i = 0; i < POSES - 1; i++) {
        if (B[i].w < 0.5 || B[i + 1].w < 0.5) {
            continue;
        }
        for (int j = 0; j < STEPS; j++) {
            float u = (float(j) + 0.5) / float(STEPS);
            vec2 b = mix(A[i].xy, A[i + 1].xy, u) * scale;
            vec2 t = mix(A[i].zw, A[i + 1].zw, u) * scale;
            vec2 d = t - b;
            float age = mix(B[i].z, B[i + 1].z, u);
            float w = smoothstep(0.0, 0.12, age) * (1.0 - age) * (1.0 - age);
            weightSum += w;
            float s = clamp(dot(p - b, d) / max(dot(d, d), 1e-8), 0.0, 1.0);
            vec2 past = b + d * s;
            float distance = length(p - past);
            float edgeFade = 1.0 - smoothstep(width * 2.0, width * 4.0, distance);
            vec2 current = mix(nowBase, nowTip, s);
            // A stationary blade is already present in the main image and bloom. Do not add it again
            // for every shutter sample, especially where the clamped segment becomes a disc at the tip.
            float motion = smoothstep(width * 0.05, width * 0.75, length(current - past));
            if (edgeFade <= 0.0 || motion <= 0.0) {
                continue; // only the blade's light, not other glowing things near where it is now
            }
            float depth = mix(mix(B[i].x, B[i].y, s), mix(B[i + 1].x, B[i + 1].y, s), u);
            if (depth > sceneDepth + 0.0003) {
                continue; // behind something
            }
            vec2 now = current + (p - past);
            vec2 uv = (now / scale) * 0.5 + 0.5;
            if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) {
                continue;
            }
            vec3 light = texture(Sampler1, uv).rgb + texture(Sampler2, uv).rgb * Params.w;
            sum += light * w * motion * edgeFade;
        }
    }
    // Missing/occluded samples contribute no light without brightening the remaining samples.
    fragColor = vec4(sum * Params.z / max(weightSum, 1e-6), 0.0);
}
