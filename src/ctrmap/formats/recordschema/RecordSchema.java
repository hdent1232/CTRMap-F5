package ctrmap.formats.recordschema;

import ctrmap.Workspace;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One fixed-stride record array, described as data instead of as a class.
 *
 * <p>WHY THIS EXISTS. Every structure the ORAS investigations have turned up so
 * far has the same shape - N records of a fixed size, laid out back to back,
 * indexed by position: items are 776 x 0x24 in a GARC, species are 826 x 0x50 in
 * another, the item-to-icon table is 776 x 4 inside the executable, shop stock is
 * 215 x 2 behind a string anchor. CTRMap has been encoding each discovery as a
 * new hand-written Java class and a new hand-built form, which is why every one
 * of them needs a developer and why no two of them behave the same. Describing
 * the discovery instead means the next one is a few lines here and inherits an
 * editor that already works.
 *
 * <p>WHAT A SCHEMA DELIBERATELY DOES NOT PROMISE. It says where the bytes are
 * and what they are called. It does NOT say what the engine will do with them.
 * That distinction is the whole point for items: the hold-effect byte selects
 * one of 183 behaviours that already exist in code, and no schema, editor or
 * patch can add a 184th - some behaviour is not in the record at all but keyed
 * on the item id at scattered code sites. A tool that blurred that would promise
 * an evening's work it cannot deliver, so {@link Tier} is part of the schema and
 * the editor is required to show it.
 */
public final class RecordSchema {

	/**
	 * How far the blast radius of an edit reaches. There are two, and the
	 * editor must never imply a third.
	 */
	public enum Tier {
		/**
		 * Pure data in an archive. Reversible, deploys through the normal
		 * pipeline, no executable patch. Because the records are fixed-size and
		 * contiguous, a writer pokes bytes in place and never repacks the
		 * archive - which also sidesteps the stale-pack corruption this project
		 * has already been bitten by once.
		 */
		DATA,
		/**
		 * In the executable. Ships as an IPS merged into the same code.ips as
		 * the zone-limit and shop patches, needs Game Patching enabled, and is
		 * refused outright against a build whose stock bytes do not match.
		 */
		CODE_PATCH
	}

	private final String name;
	private final String about;
	private final Tier tier;
	private final Workspace.ArchiveType archive;
	private final int codeFileOffset;
	private final int recordCount;
	private final int stride;
	private final List<RecordField> fields;

	private RecordSchema(String name, String about, Tier tier, Workspace.ArchiveType archive,
			int codeFileOffset, int recordCount, int stride, List<RecordField> fields) {
		this.name = name;
		this.about = about;
		this.tier = tier;
		this.archive = archive;
		this.codeFileOffset = codeFileOffset;
		this.recordCount = recordCount;
		this.stride = stride;
		this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
		for (RecordField f : fields) {
			if (f.endByte() > stride) {
				throw new IllegalArgumentException(name + ": field " + f
						+ " runs past the end of a " + stride + "-byte record. A field that"
						+ " overruns its record reads the NEXT record's bytes and still looks"
						+ " like data.");
			}
		}
	}

	/**
	 * A schema over records stored one per entry in a GARC.
	 *
	 * <p>{@code recordCount} is -1 when the archive itself decides - the item
	 * table is 776 entries in this dump and a caller must read the archive
	 * rather than trust the number, because a count written here is a claim
	 * about somebody else's file.
	 */
	public static RecordSchema archive(String name, String about, Workspace.ArchiveType archive,
			int recordCount, int stride, List<RecordField> fields) {
		return new RecordSchema(name, about, Tier.DATA, archive, -1, recordCount, stride, fields);
	}

	/** A schema over a table at a fixed file offset in the decompressed executable. */
	public static RecordSchema code(String name, String about, int fileOffset,
			int recordCount, int stride, List<RecordField> fields) {
		return new RecordSchema(name, about, Tier.CODE_PATCH, null, fileOffset, recordCount,
				stride, fields);
	}

	public String name() {
		return name;
	}

	/** One paragraph saying what this is and, more importantly, what it is not. */
	public String about() {
		return about;
	}

	public Tier tier() {
		return tier;
	}

	/** The archive holding the records, or null for a {@link Tier#CODE_PATCH} schema. */
	public Workspace.ArchiveType archive() {
		return archive;
	}

	/** File offset in the decompressed code.bin, or -1 for a {@link Tier#DATA} schema. */
	public int codeFileOffset() {
		return codeFileOffset;
	}

	/** Records the table is known to hold, or -1 when the container decides. */
	public int recordCount() {
		return recordCount;
	}

	public int stride() {
		return stride;
	}

	public List<RecordField> fields() {
		return fields;
	}

	/** The field groups in the order they were declared, for laying out a form. */
	public List<String> groups() {
		Set<String> seen = new LinkedHashSet<>();
		for (RecordField f : fields) {
			seen.add(f.group());
		}
		return new ArrayList<>(seen);
	}

	public List<RecordField> fieldsIn(String group) {
		List<RecordField> out = new ArrayList<>();
		for (RecordField f : fields) {
			if (f.group().equals(group)) {
				out.add(f);
			}
		}
		return out;
	}

	/**
	 * Bytes of the record no field describes.
	 *
	 * <p>Reported rather than hidden. An unmapped byte is a hole in the
	 * understanding of the format, and a form that silently omits it lets a user
	 * believe they are looking at the whole record. Two of the item record's
	 * bytes (0x22 and 0x23) are zero in all 776 retail records and nobody knows
	 * what they are for; that is a fact worth showing.
	 */
	public List<Integer> unmappedBytes() {
		boolean[] covered = new boolean[stride];
		for (RecordField f : fields) {
			for (int i = f.byteOffset(); i < f.endByte(); i++) {
				covered[i] = true;
			}
		}
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < stride; i++) {
			if (!covered[i]) {
				out.add(i);
			}
		}
		return out;
	}

	@Override
	public String toString() {
		return name + " [" + tier + "] " + (recordCount < 0 ? "?" : String.valueOf(recordCount))
				+ " x " + stride + " bytes, " + fields.size() + " field(s)";
	}
}
