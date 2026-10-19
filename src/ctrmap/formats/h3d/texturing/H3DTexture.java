package ctrmap.formats.h3d.texturing;

import ctrmap.formats.h3d.PICACommandReader;
import ctrmap.formats.h3d.RandomAccessBAIS;
import ctrmap.formats.h3d.StringUtils;
import java.awt.Dimension;
import java.io.IOException;

public class H3DTexture {
	
	public String textureName;
	public Dimension textureSize;
	public byte[] textureData;
	
	public H3DTexture(RandomAccessBAIS in, byte[] data) throws IOException{
		int dataOffset = in.readInt();
		in.seek(dataOffset);

		int texUnit0CommandsOffset = in.readInt();
		int texUnit0CommandsWordCount = in.readInt();
		//texture units 1 and 2: commands offset and word count, stepped over
		in.readInt();
		in.readInt();
		in.readInt();
		in.readInt();
		in.readInt();
		int textureNameOffset = in.readInt();
		textureName = StringUtils.readString(textureNameOffset, data);

		in.seek(texUnit0CommandsOffset);
		PICACommandReader textureCommands = new PICACommandReader(in, texUnit0CommandsWordCount, false);

		in.seek(textureCommands.getTexUnit0Address());
		textureSize = textureCommands.getTexUnit0Size();
		//WHOLE OR REFUSED (census M.110): this read width*height*4 bytes whatever the format and dropped how many
		//came, so a texture cut short decoded with a zero tail and was drawn as if it were whole. Exactly what the
		//format stores, read whole - an EOFException that names where, rather than half a picture
		byte[] buffer = new byte[TextureCodec.storedSize(textureSize.width, textureSize.height, textureCommands.getTexUnit0Format())];
		in.readFully(buffer);
		textureData = TextureCodec.decode(
				buffer,
				textureSize.width,
				textureSize.height,
				textureCommands.getTexUnit0Format()
		);
	}
}
