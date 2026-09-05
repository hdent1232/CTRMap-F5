package ctrmap.vault;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a vault holds, and what it was when it was sealed.
 *
 * <p>The manifest IS the seal: {@link Vault#seal} writes it last, so its
 * presence is what distinguishes a finished backup from a folder that was being
 * written when the power went out. Reading it back is therefore also the test
 * for "is this a real backup", and that is why this file is written atomically
 * - to a temporary name and then renamed - rather than streamed in place. A
 * half-written manifest would be a half-written seal, which is the exact state
 * this design exists to make impossible.
 *
 * <p>Written as flat, line-oriented text rather than JSON because it must be
 * readable by a person looking at a folder in Explorer while trying to work out
 * whether their backup is any good, and because it must be parseable without
 * pulling in a dependency the rest of this editor does not have.
 */
public final class VaultManifest {

	/** One file, as it was at seal time. */
	public static final class Entry {

		public long size;
		public long mtime;
		public String sha256 = "";
	}

	public String titleId = "";
	public String gameType = "";
	public String displayName = "";
	public String scope = "";
	public long sealedAt;
	public String takenFrom = "";
	/**
	 * Whether the dump passed {@link ctrmap.setup.DumpCheck} when it was sealed.
	 *
	 * <p>False does NOT mean the vault is useless - it may be the only copy the
	 * user has, and keeping it is better than refusing. It means the copy must
	 * never be described as retail. The flag is stored rather than recomputed
	 * so the judgement survives a reload instead of living in someone's memory.
	 */
	public boolean verifiedVanilla;
	public String dumpCheckStatus = "";
	public String dumpCheckHeadline = "";
	/** relative path -> what it was. Insertion-ordered so the file reads in sorted order. */
	public final Map<String, Entry> entries = new LinkedHashMap<>();

	/** Set by {@link Vault#list()}; not stored in the file. */
	public transient File entryDir;

	static File file(File entry) {
		return new File(entry, "manifest.txt");
	}

	/** A one-line summary safe to show in a list. */
	public String summary() {
		return displayName + (verifiedVanilla ? "" : "  (NOT verified vanilla)")
				+ " - " + entries.size() + " file(s), " + scope;
	}

	static void write(File entry, VaultManifest m) throws IOException {
		File tmp = new File(entry, "manifest.txt.part");
		OutputStream os = new FileOutputStream(tmp);
		try {
			StringBuilder sb = new StringBuilder();
			sb.append("# CTRMap vault manifest. Do not edit - this file is what proves the backup is complete.\r\n");
			sb.append("titleId=").append(m.titleId).append("\r\n");
			sb.append("gameType=").append(m.gameType).append("\r\n");
			sb.append("displayName=").append(m.displayName).append("\r\n");
			sb.append("scope=").append(m.scope).append("\r\n");
			sb.append("sealedAt=").append(m.sealedAt).append("\r\n");
			sb.append("takenFrom=").append(m.takenFrom).append("\r\n");
			sb.append("verifiedVanilla=").append(m.verifiedVanilla).append("\r\n");
			sb.append("dumpCheckStatus=").append(m.dumpCheckStatus).append("\r\n");
			sb.append("dumpCheckHeadline=").append(m.dumpCheckHeadline.replace("\r", " ").replace("\n", " ")).append("\r\n");
			sb.append("files=").append(m.entries.size()).append("\r\n");
			for (Map.Entry<String, Entry> e : m.entries.entrySet()) {
				Entry v = e.getValue();
				//size mtime sha256 path - path last because it is the only field
				//that can contain a space
				sb.append("f ").append(v.size).append(' ').append(v.mtime).append(' ')
						.append(v.sha256).append(' ').append(e.getKey()).append("\r\n");
			}
			os.write(sb.toString().getBytes("UTF-8"));
		} finally {
			os.close();
		}
		File dst = file(entry);
		dst.delete();
		if (!tmp.renameTo(dst)) {
			throw new IOException("could not finish writing the vault manifest at " + dst);
		}
	}

	/** The manifest in {@code entry}, or null when there is not a finished one. */
	static VaultManifest read(File entry) {
		File f = file(entry);
		if (!f.isFile()) {
			return null;
		}
		VaultManifest m = new VaultManifest();
		m.entryDir = entry;
		try {
			byte[] buf = new byte[(int) f.length()];
			FileInputStream is = new FileInputStream(f);
			try {
				int off = 0, n;
				while (off < buf.length && (n = is.read(buf, off, buf.length - off)) > 0) {
					off += n;
				}
			} finally {
				is.close();
			}
			int declared = -1;
			for (String line : new String(buf, "UTF-8").split("\r\n|\n")) {
				if (line.isEmpty() || line.charAt(0) == '#') {
					continue;
				}
				if (line.startsWith("f ")) {
					String[] p = line.substring(2).split(" ", 4);
					if (p.length < 4) {
						continue;
					}
					Entry e = new Entry();
					e.size = Long.parseLong(p[0]);
					e.mtime = Long.parseLong(p[1]);
					e.sha256 = p[2];
					m.entries.put(p[3], e);
					continue;
				}
				int eq = line.indexOf('=');
				if (eq < 0) {
					continue;
				}
				String k = line.substring(0, eq), v = line.substring(eq + 1);
				if ("titleId".equals(k)) {
					m.titleId = v;
				} else if ("gameType".equals(k)) {
					m.gameType = v;
				} else if ("displayName".equals(k)) {
					m.displayName = v;
				} else if ("scope".equals(k)) {
					m.scope = v;
				} else if ("sealedAt".equals(k)) {
					m.sealedAt = Long.parseLong(v);
				} else if ("takenFrom".equals(k)) {
					m.takenFrom = v;
				} else if ("verifiedVanilla".equals(k)) {
					m.verifiedVanilla = Boolean.parseBoolean(v);
				} else if ("dumpCheckStatus".equals(k)) {
					m.dumpCheckStatus = v;
				} else if ("dumpCheckHeadline".equals(k)) {
					m.dumpCheckHeadline = v;
				} else if ("files".equals(k)) {
					declared = Integer.parseInt(v);
				}
			}
			//A manifest that lists fewer files than it says it has is a
			//truncated manifest, and a truncated manifest describes a backup
			//that is missing files while looking complete - the exact state
			//Workspace.snapshotMissingArchives exists to catch. Refuse it.
			if (declared >= 0 && declared != m.entries.size()) {
				return null;
			}
		} catch (IOException | RuntimeException ex) {
			return null;
		}
		return m;
	}
}
