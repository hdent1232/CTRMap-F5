package ctrmap.formats.tilemap;

import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.model.H3DModel;
import ctrmap.formats.h3d.texturing.H3DMaterial;
import ctrmap.formats.h3d.texturing.H3DTexture;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves each {@link TilePalette} terrain to the decoded texture image its
 * material uses in the tileset donor region, so the tile painter can show a
 * top-down "in-game" textured preview (grass looks like grass, water like
 * water) instead of flat swatches. The mapping matches the geometry builder:
 * a terrain -&gt; the donor material whose name contains a terrain hint -&gt; that
 * material's texture (name0) -&gt; the decoded {@link H3DTexture} from the zone's
 * world textures. Falls back silently (returns null) when a texture is absent,
 * and the caller draws the flat color instead.
 */
public class TerrainTextures {

	private final Map<TilePalette, BufferedImage> images = new HashMap<>();

	/** Builds the terrain-&gt;image map from the donor model and the zone's world textures. */
	public static TerrainTextures build(byte[] donorModel, List<H3DTexture> worldTextures) {
		TerrainTextures tt = new TerrainTextures();
		if (donorModel == null || worldTextures == null || worldTextures.isEmpty()) {
			return tt;
		}
		try {
			BCHFile bch = new BCHFile(donorModel);
			if (bch.errorlevel != 0 || bch.models.isEmpty()) {
				return tt;
			}
			H3DModel model = bch.models.get(0);
			Map<String, H3DTexture> byName = new HashMap<>();
			for (H3DTexture t : worldTextures) {
				byName.putIfAbsent(t.textureName, t);
			}
			for (TilePalette terrain : TilePalette.values()) {
				H3DMaterial mat = resolveMaterial(model, terrain);
				if (mat == null || mat.name0 == null) {
					continue;
				}
				H3DTexture tex = byName.get(mat.name0);
				if (tex != null) {
					BufferedImage img = toImage(tex);
					if (img != null) {
						tt.images.put(terrain, img);
					}
				}
			}
		} catch (Exception ex) {
			// leave whatever resolved; the painter falls back to flat colors
		}
		return tt;
	}

	public BufferedImage image(TilePalette terrain) {
		return images.get(terrain);
	}

	public boolean any() {
		return !images.isEmpty();
	}

	/**
	 * Decoded image of the NAMED material's base texture - what that material
	 * actually looks like in-game - or null. Used for visual pickers (e.g. the
	 * blank-canvas ground material list).
	 */
	public static BufferedImage materialImage(byte[] modelBytes, List<H3DTexture> worldTextures, String materialName) {
		try {
			if (modelBytes == null || worldTextures == null || materialName == null) {
				return null;
			}
			BCHFile bch = new BCHFile(modelBytes);
			if (bch.errorlevel != 0 || bch.models.isEmpty()) {
				return null;
			}
			for (H3DMaterial mat : bch.models.get(0).materials) {
				if (mat != null && materialName.equals(mat.name) && mat.name0 != null) {
					for (H3DTexture t : worldTextures) {
						if (mat.name0.equals(t.textureName)) {
							return toImage(t);
						}
					}
				}
			}
		} catch (Exception ex) {
			// no preview - the caller shows text only
		}
		return null;
	}

	private static H3DMaterial resolveMaterial(H3DModel model, TilePalette terrain) {
		for (String hint : terrain.matHints) {
			for (H3DMaterial mat : model.materials) {
				String n = mat.name != null ? mat.name.toLowerCase() : "";
				if (n.contains(hint)) {
					return mat;
				}
			}
		}
		return null;
	}

	/** Decoded RGBA (bottom-up, as TextureCodec emits it) -&gt; an upright ARGB image. */
	private static BufferedImage toImage(H3DTexture tex) {
		if (tex.textureData == null || tex.textureSize == null) {
			return null;
		}
		int w = tex.textureSize.width, h = tex.textureSize.height;
		if (w <= 0 || h <= 0 || tex.textureData.length < w * h * 4) {
			return null;
		}
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		byte[] d = tex.textureData;
		for (int y = 0; y < h; y++) {
			int srcRow = (h - 1 - y) * w * 4; // flip vertically (GL origin is bottom-left)
			for (int x = 0; x < w; x++) {
				int i = srcRow + x * 4;
				int r = d[i] & 0xFF, g = d[i + 1] & 0xFF, b = d[i + 2] & 0xFF, a = d[i + 3] & 0xFF;
				img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
			}
		}
		return img;
	}
}
