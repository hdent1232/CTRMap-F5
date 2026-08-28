package ctrmap.formats.tilemap;

import java.awt.Color;

/**
 * Per-map baked lighting for painted terrain - the same lever GameFreak varied
 * per area (bright routes, dim caves, warm evenings). ORAS bakes lighting into
 * each ground vertex's color, which the renderer multiplies against the
 * texture; this class defines the values the tile painter bakes:
 * <ul>
 * <li>{@code brightness} - overall light level (0.3 dim .. 1.2 bright);</li>
 * <li>{@code tint} - light color (warm white by day, orange dusk, blue-grey
 *     cave);</li>
 * <li>{@code edgeShadow} - procedural ambient occlusion darkening ground next
 *     to walls/rock, so boundaries read like the game's baked edge shadows.</li>
 * </ul>
 */
public class TerrainLighting {

	public float brightness = 1.0f;
	public int tint = 0xFFFFFF;
	public float edgeShadow = 0.35f;

	public TerrainLighting() {
	}

	public TerrainLighting(float brightness, int tint, float edgeShadow) {
		this.brightness = brightness;
		this.tint = tint;
		this.edgeShadow = edgeShadow;
	}

	public Color tintColor() {
		return new Color(tint);
	}

	/** Natural daytime (default): full, faintly warm, soft edge shadows. */
	public static TerrainLighting daytime() {
		return new TerrainLighting(1.0f, 0xFFF6E6, 0.35f);
	}

	/** The baked u8 color for a vertex, given its ambient-occlusion factor (0..1). */
	public int[] vertexColor(float ao) {
		float b = brightness * (1f - edgeShadow * (1f - ao));
		int r = clamp(((tint >> 16) & 0xFF) * b);
		int g = clamp(((tint >> 8) & 0xFF) * b);
		int bl = clamp((tint & 0xFF) * b);
		return new int[]{r, g, bl, 255};
	}

	private static int clamp(float v) {
		int i = Math.round(v);
		return i < 0 ? 0 : i > 255 ? 255 : i;
	}
}
