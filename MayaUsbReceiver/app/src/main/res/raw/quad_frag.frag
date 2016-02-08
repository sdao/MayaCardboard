precision mediump float;

uniform sampler2D u_Bitmap;

//varying vec4 v_Color;
varying vec2 v_TexCoord;

void main() {
    gl_FragColor = texture2D(u_Bitmap, v_TexCoord);
}