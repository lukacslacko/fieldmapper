#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;

uniform samplerExternalOES u_CameraTexture;

in vec2 v_TexCoord;
out vec4 fragColor;

void main() {
    fragColor = texture(u_CameraTexture, v_TexCoord);
}
