#version 150

uniform sampler2D Sampler0; // depth to copy

in vec2 texCoord;
out vec4 fragColor;

// Writes the depth of the texture as the depth of the buffer drawn into (colour writes are off).
void main() {
    gl_FragDepth = texture(Sampler0, texCoord).r;
    fragColor = vec4(0.0);
}
