#version 150

uniform sampler2D Sampler0;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    gl_FragDepth = texture(Sampler0, texCoord).r;
    fragColor = vec4(0.0);
}
