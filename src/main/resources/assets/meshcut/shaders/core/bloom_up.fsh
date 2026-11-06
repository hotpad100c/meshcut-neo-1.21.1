#version 150

uniform sampler2D Sampler0;
uniform vec2 TexelSize;
uniform float Strength;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 h = TexelSize;
    vec3 sum = texture(Sampler0, texCoord).rgb * 4.0;
    sum += texture(Sampler0, texCoord + vec2(h.x, 0.0)).rgb * 2.0;
    sum += texture(Sampler0, texCoord - vec2(h.x, 0.0)).rgb * 2.0;
    sum += texture(Sampler0, texCoord + vec2(0.0, h.y)).rgb * 2.0;
    sum += texture(Sampler0, texCoord - vec2(0.0, h.y)).rgb * 2.0;
    sum += texture(Sampler0, texCoord + h).rgb;
    sum += texture(Sampler0, texCoord - h).rgb;
    sum += texture(Sampler0, texCoord + vec2(h.x, -h.y)).rgb;
    sum += texture(Sampler0, texCoord + vec2(-h.x, h.y)).rgb;
    fragColor = vec4(sum / 16.0 * Strength, 0.0);
}
