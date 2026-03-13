#version 300 es
precision mediump float;

in float v_Confidence;
in float v_ArcLength;
in float v_FieldStrength;

out vec4 fragColor;

void main() {
    // Dashed pattern for under-sampled regions
    if (v_Confidence < 0.99) {
        float dashPhase = fract(v_ArcLength / 0.06);
        if (dashPhase > 0.5) {
            discard;
        }
    }

    // Color: cyan for weak fields, transitioning to bright blue for strong fields
    float strength = clamp(v_FieldStrength, 0.0, 1.0);
    vec3 color = mix(vec3(0.0, 0.85, 0.95), vec3(0.3, 0.4, 1.0), strength);

    // Alpha: fully opaque where well-sampled, faded where under-sampled
    float alpha = mix(0.3, 0.9, v_Confidence);

    fragColor = vec4(color, alpha);
}
