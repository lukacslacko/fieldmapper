#version 300 es

uniform mat4 u_ViewProjection;

layout(location = 0) in vec3 a_Position;
layout(location = 1) in float a_Confidence;
layout(location = 2) in float a_ArcLength;
layout(location = 3) in float a_FieldStrength;

out float v_Confidence;
out float v_ArcLength;
out float v_FieldStrength;

void main() {
    gl_Position = u_ViewProjection * vec4(a_Position, 1.0);
    v_Confidence = a_Confidence;
    v_ArcLength = a_ArcLength;
    v_FieldStrength = a_FieldStrength;
}
