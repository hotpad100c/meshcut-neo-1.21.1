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
            vec2 past = b + d * s;
            if (length(p - past) > width * 4.0) {
                continue; // only the blade's light, not other glowing things near where it is now
            }
            float depth = mix(mix(B[i].x, B[i].y, s), mix(B[i + 1].x, B[i + 1].y, s), u);
            if (depth > sceneDepth + 0.0003) {
                continue; // behind something
            }
            float age = mix(B[i].z, B[i + 1].z, u);
            // the blade as it is now is drawn already: the trail fades in behind it, and out as it gets older
            float w = smoothstep(0.0, 0.12, age) * (1.0 - age) * (1.0 - age);
            vec2 now = mix(nowBase, nowTip, s) + (p - past);
            vec2 uv = (now / scale) * 0.5 + 0.5;
            if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) {
                continue;
            }
            vec3 light = texture(Sampler1, uv).rgb + texture(Sampler2, uv).rgb * Params.w;
            sum += light * w;
        }
    }
    fragColor = vec4(sum * Params.z / float(STEPS), 0.0);
}
