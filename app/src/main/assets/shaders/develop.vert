#version 300 es
// Cuadrilátero a pantalla completa (triangle strip de 4 vértices, sin buffers).
// La fila 0 de la textura es la parte superior de la foto.
out vec2 vUv;

void main() {
    vec2 p = vec2((gl_VertexID & 1) == 0 ? -1.0 : 1.0,
                  (gl_VertexID & 2) == 0 ? -1.0 : 1.0);
    vUv = vec2(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5);
    gl_Position = vec4(p, 0.0, 1.0);
}
