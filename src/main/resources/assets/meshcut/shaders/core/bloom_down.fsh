#version 150

uniform sampler2D Sampler0;
uniform vec2 TexelSize;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 h = TexelSize * 0.5;
    vec4 sum = texture(Sampler0, texCoord) * 4.0;
    sum += texture(Sampler0, texCoord - h);
    sum += texture(Sampler0, texCoord + h);
    sum += texture(Sampler0, texCoord + vec2(h.x, -h.y));
    sum += texture(Sampler0, texCoord + vec2(-h.x, h.y));
    fragColor = sum / 8.0;
}
