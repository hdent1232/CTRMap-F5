package ctrmap.formats.tilemap;

import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.texturing.H3DMaterial;
import ctrmap.formats.h3d.texturing.H3DTexture;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * What a map material looks like: the decoded image of a named material's base
 * texture, for the blank-canvas material picker.
 *
 * <p>This class used to promise a top-down textured preview in the tile painter
 * - grass drawn as grass - built per terrain by a name-substring match. Nothing
 * ever called that builder, and its match had none of the geometry builder's
 * filters, so it would have previewed the very cliff and sprite materials the
 * builder rejects. It was deleted with its resolver (F094, F204); the picker's
 * one method is what is left.
 */
public class TerrainTextures {

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
