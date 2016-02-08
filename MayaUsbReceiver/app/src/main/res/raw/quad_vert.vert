attribute vec4 a_Position;
attribute vec2 a_TexCoord;

//varying vec4 v_Color;
varying vec2 v_TexCoord;

void main() {
    //v_Color = vec4(a_TexCoord.x, a_TexCoord.y, 1, 1);
    v_TexCoord = a_TexCoord;
    gl_Position = a_Position;
}