#version 150

uniform sampler2D Sampler0;
uniform vec2 TexelSize; // of the source
uniform float Strength;

in vec2 texCoord;
out vec4 fragColor;

// Dual-filter upsample: eight taps in a tent around the pixel.
void main() {
    vec2 h = TexelSize * 0.5;
    vec4 sum = texture(Sampler0, texCoord + vec2(-h.x * 2.0, 0.0));
    sum += texture(Sampler0, texCoord + vec2(-h.x, h.y)) * 2.0;
    sum += texture(Sampler0, texCoord + vec2(0.0, h.y * 2.0));
    sum += texture(Sampler0, texCoord + vec2(h.x, h.y)) * 2.0;
    sum += texture(Sampler0, texCoord + vec2(h.x * 2.0, 0.0));
    sum += texture(Sampler0, texCoord + vec2(h.x, -h.y)) * 2.0;
    sum += texture(Sampler0, texCoord + vec2(0.0, -h.y * 2.0));
    sum += texture(Sampler0, texCoord + vec2(-h.x, -h.y)) * 2.0;
    fragColor = vec4(sum.rgb / 12.0 * Strength, 0.0);
}
