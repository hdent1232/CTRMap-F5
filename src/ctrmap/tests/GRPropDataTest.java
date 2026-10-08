package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.formats.containers.GR;
import ctrmap.formats.containers.MM;
import ctrmap.formats.mapmatrix.MapMatrix;
import ctrmap.formats.propdata.GRProp;
import ctrmap.formats.propdata.GRPropData;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Saving a map's props never drops one, and never writes over props it could not read (F083).
 *
 * <p>WHY THIS SUITE EXISTS. The prop editor saves a whole matrix by handing every prop to the
 * region of the cell it stands in. A prop whose cell held no region was meant to go to the nearest
 * one that did; the search's inner loop sat under an unbraced {@code if (y < 0 || y >= height)}, so
 * it ran only for rows OFF the grid, where reading a cell throws. A prop in an empty cell was left
 * out of every region's list and the save said nothing; a prop past the grid's edge threw on the
 * EDT. And a region whose prop section would not read came back as an empty list, which the next
 * save wrote over everything that region held.
 *
 * <p>ORDER: needs no dump and no display. The regions are containers on a fake game, under
 * {@link Scratch}.
 *
 * Usage: java ctrmap.tests.GRPropDataTest
 */
public class GRPropDataTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		Workspace.reset();
		try {
			aPropInACellWithNoRegionIsKept();
			aPropOffTheGridIsKept();
			aMapWithNoRegionRefusesInWords();
			propsThatCouldNotBeReadAreNeverWrittenOver();
		} finally {
			Workspace.reset();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** A 3 x 2 matrix with a region at (0,0) and (2,1) and none anywhere else. */
	static final class Map {
		final MapMatrix mm;
		final GR a;
		final GR b;

		Map(String name) throws IOException {
			FakeGameFiles fake = new FakeGameFiles();
			a = region(fake, 1);
			b = region(fake, 2);
			mm = new MapMatrix(new MM(new File(fake.scratch(), name + ".mm"), 2, MM.MM_MAP_MATRIX, fake), 3, 2, 0);
			mm.regions.set(0, 0, a);
			mm.regions.set(2, 1, b);
		}
	}

	static GR region(FakeGameFiles fake, int entry) throws IOException {
		GR gr = new GR(fake.staged(ArchiveType.FIELD_DATA, entry), 7, fake);
		gr.storeFile(3, new byte[4]); //a prop section holding no props
		return gr;
	}

	static GRProp prop(float x, float z, int uid) {
		GRProp p = new GRProp();
		p.x = x;
		p.z = z;
		p.uid = uid;
		return p;
	}

	static List<Integer> uids(GR gr) {
		List<Integer> out = new ArrayList<>();
		for (GRProp p : new GRPropData(gr).props) {
			out.add(p.uid);
		}
		return out;
	}

	static void aPropInACellWithNoRegionIsKept() throws Exception {
		System.out.println("--- a prop standing in a cell with no region is written to the nearest region");
		Map m = new Map("empty-cell");
		GRPropData props = new GRPropData();
		props.props.add(prop(100, 100, 1));    //in (0,0)
		props.props.add(prop(730, 10, 2));     //in (1,0), which has no region: nearest is (0,0)
		props.props.add(prop(2140, 1420, 3));  //in (2,1)
		props.write(m.mm);
		check(uids(m.a).equals(Arrays.asList(1, 2)),
				"the prop in the empty cell is written into the nearest region, not dropped: " + uids(m.a));
		check(uids(m.b).equals(Arrays.asList(3)), "and the one in its own region stays there: " + uids(m.b));
		check(uids(m.a).size() + uids(m.b).size() == 3, "every prop is written exactly once (3 of 3)");
	}

	static void aPropOffTheGridIsKept() throws Exception {
		System.out.println("--- a prop past the grid's edge is written to the nearest region, not thrown on");
		Map m = new Map("off-grid");
		GRPropData props = new GRPropData();
		props.props.add(prop(-800, 100, 4));    //left of the grid: nearest is (0,0)
		props.props.add(prop(5000, 3000, 5));   //beyond the far corner: nearest is (2,1)
		try {
			props.write(m.mm);
			check(uids(m.a).equals(Arrays.asList(4)) && uids(m.b).equals(Arrays.asList(5)),
					"each is written into the region nearest to it: " + uids(m.a) + " " + uids(m.b));
		} catch (RuntimeException threw) {
			check(false, "each is written into the region nearest to it - instead the save threw " + threw);
		}
	}

	static void aMapWithNoRegionRefusesInWords() throws Exception {
		System.out.println("--- a map with no region refuses to save its props, in words");
		FakeGameFiles fake = new FakeGameFiles();
		MapMatrix empty = new MapMatrix(new MM(new File(fake.scratch(), "none.mm"), 2, MM.MM_MAP_MATRIX, fake), 2, 2, 0);
		GRPropData props = new GRPropData();
		props.props.add(prop(10, 10, 6));
		String said = null;
		try {
			props.write(empty);
		} catch (IOException | RuntimeException refused) {
			//an unchecked one too: it must be the refusal in words that answers, and anything
			//else is reported here rather than ending the suite
			said = refused.toString();
		}
		check(said != null && said.contains("no region") && said.contains("1 prop"),
				"a prop with nowhere to go is refused, saying so - never left out of the file: " + said);
		try {
			new GRPropData().write(empty);
			check(true, "while no props on no regions is nothing to refuse");
		} catch (IOException refused) {
			check(false, "while no props on no regions is nothing to refuse - it said " + refused.getMessage());
		}
	}

	static void propsThatCouldNotBeReadAreNeverWrittenOver() throws Exception {
		System.out.println("--- a region whose props could not be read is never written over");
		Map m = new Map("unread");
		//a section that says it holds five props and holds none: reading it runs out of bytes
		byte[] damaged = {5, 0, 0, 0};
		m.b.storeFile(3, damaged);
		byte[] asStored = m.b.getFile(3); //as the container holds it, padding and all
		GRPropData read = new GRPropData(m.b);
		check(read.unreadable != null && read.unreadable.contains("could not be read"),
				"the read says it failed, rather than coming back empty: " + read.unreadable);

		GRPropData shown = new GRPropData();
		shown.absorb(new GRPropData(m.a));
		shown.absorb(read);
		shown.props.add(prop(100, 100, 7));
		String said = null;
		try {
			shown.write(m.mm);
		} catch (IOException refused) {
			said = refused.getMessage();
		}
		check(said != null && said.contains("could not be read") && said.contains("Nothing was saved"),
				"saving the whole map refuses, naming the region: " + said);
		check(Arrays.equals(m.b.getFile(3), asStored), "and the region it could not read is exactly as it was");
		check(uids(m.a).isEmpty(), "and neither is any other region written");

		said = null;
		try {
			read.write();
		} catch (IOException refused) {
			said = refused.getMessage();
		}
		check(said != null && said.contains("Nothing was saved"), "and so does saving that one region: " + said);
		check(Arrays.equals(m.b.getFile(3), asStored), "which is still exactly as it was");
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok   " : "  FAIL: ") + what);
		if (!ok) {
			fails++;
		}
	}
}
