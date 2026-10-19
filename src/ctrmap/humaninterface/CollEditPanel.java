package ctrmap.humaninterface;

import ctrmap.formats.Triangle;
import ctrmap.formats.gfcollision.GRCollisionFile;
import ctrmap.humaninterface.tools.AbstractTool;
import ctrmap.humaninterface.tools.FillTool;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JFormattedTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;

/**
 * Simple vertex editor.
 */
public class CollEditPanel extends javax.swing.JPanel {

	private DefaultMutableTreeNode root = new DefaultMutableTreeNode("No map loaded");
	public List<GRCollisionFile> files = new ArrayList<>();
	private List<DefaultMutableTreeNode> meshContainers = new ArrayList<>();
	private List<DefaultMutableTreeNode[]> meshNodes = new ArrayList<>();
	public GRCollisionFile coll;
	private int collIndex = -1;
	public int selectedMesh = -1;
	public int selectedTri = -1;

	/** Which tool the editor is holding, handed in: this class only asks. */
	private final ctrmap.humaninterface.tools.ToolSelection tools;

	/**
	 * Creates new form CollEditPanel
	 */
	public CollEditPanel(ctrmap.humaninterface.tools.ToolSelection tools, UndoHistory history) {
		this.tools = tools;
		if (history == null) {
			throw new IllegalArgumentException("CollEditPanel must be handed the zone's history its collision"
					+ " is a document of");
		}
		//each collision file held is one of the zone's documents: watched, not recorded by hand (F209)
		history.watch(() -> {
			java.util.List<UndoHistory.Document<?>> out = new ArrayList<>();
			for (int i = 0; i < files.size(); i++) {
				out.add(collision(i));
			}
			return out;
		});
		initComponents();
		meshTree.addTreeSelectionListener((TreeSelectionEvent e) -> {
			DefaultMutableTreeNode node = (DefaultMutableTreeNode) meshTree.getLastSelectedPathComponent();
			if (node == null) {
				deselectTri();
				deselectMesh();
			} else {
				//THE REGION A NODE IS DRAWN UNDER, asked first and in one place for every branch (census
				//H.26): a mesh was picked by its position under its parent alone, so with two regions
				//loaded, "Mesh 5" under region B edited region A's mesh 5 and saved it into A
				int owner = fileOf(node);
				if (owner >= 0 && owner != collIndex) {
					deselectTri();
					deselectMesh();
					selectFile(owner);
				}
				String label = (String) node.getUserObject();
				if (label.startsWith("Mesh")) {
					selectMesh(node.getParent().getIndex(node));
				} else if (label.startsWith("Triangle")) {
					int parentMesh = meshContainers.get(collIndex).getIndex(node.getParent());
					selectTriangle(parentMesh, node.getParent().getIndex(node));
				} else if (root != node) {
					deselectTri();
					deselectMesh();
				}
			}
		});
		//ONE LISTENER, BOUND PER FIELD: these were nine pasted anonymous classes that differed only in
		//the field and the vertex they wrote (F246)
		JFormattedTextField[][] fields = {{v1x, v1y, v1z}, {v2x, v2y, v2z}, {v3x, v3y, v3z}};
		for (int v = 0; v < 3; v++) {
			for (int axis = 0; axis < 3; axis++) {
				bindCoordinate(fields[v][axis], axis, v);
			}
		}
	}

	public void unload() {
		selectedMesh = -1;
		selectedTri = -1;
		meshTree.setModel(null);
		coll = null;
		collIndex = -1;
		root = new DefaultMutableTreeNode("No map loaded");
		meshNodes.clear();
		meshContainers.clear();
		files.clear();
	}

	/**
	 * Writes each changed collision, asking first when {@code dialog} - false only when the user was asked
	 * and cancelled. It wrote without asking and answered true whatever happened (census H.1): No to every
	 * question of a zone switch still wrote the collisions, and a written one stayed marked changed.
	 */
	public boolean store(boolean dialog) {
		boolean changed = false;
		for (GRCollisionFile file : files) {
			changed |= file.modified;
		}
		if (!changed) {
			return true;
		}
		switch (ctrmap.Ui.askToKeep(dialog, "Collision data", this::discard)) {
			case DISCARD:
				return true;
			case CANCEL:
				return false;
			default:
				break;
		}
		for (GRCollisionFile file : files) {
			if (file.modified) {
				file.write();
				file.modified = false;
			}
		}
		buildTree();
		return true;
	}

	/** Puts every changed collision back to what its region holds, and the tree with it. */
	void discard() {
		deselectTri();
		deselectMesh();
		for (GRCollisionFile file : files) {
			if (file.modified) {
				file.revert();
			}
		}
		buildTree();
	}

	public void loadCollision(GRCollisionFile f, String name) {
		if (f.unreadable != null) {
			//an empty stand-in for bytes nobody could read: edited here and saved, it would
			//replace them (F058)
			throw new IllegalArgumentException("The collision editor is not given a collision it could not read: "
					+ f.unreadable);
		}
		this.coll = f;
		root.setUserObject("Collision data");
		files.add(coll);
		collIndex = files.size() - 1;   //the file selected and the index that names it never part
		meshTree.setModel(new DefaultTreeModel(root));
		DefaultMutableTreeNode[] newNode = new DefaultMutableTreeNode[16];
		DefaultMutableTreeNode cont = new DefaultMutableTreeNode(name);
		for (int i = 0; i < 16; i++) {
			newNode[i] = new DefaultMutableTreeNode("Mesh " + String.valueOf(i));
			for (int j = 0; j < coll.meshes[i].tris.size(); j++) {
				newNode[i].add(new DefaultMutableTreeNode("Triangle " + String.valueOf(j)));
			}
			cont.add(newNode[i]);
		}
		meshNodes.add(newNode);
		meshContainers.add(cont);
		root.add(cont);
		deselectMesh();
		deselectTri();
		((DefaultTreeModel) meshTree.getModel()).reload();
	}

	/**
	 * Collision file {@code index} as a document of the zone's history (F209): its sixteen meshes'
	 * triangles, each vertex copied, put back whole. The editor had no undo at all.
	 */
	private UndoHistory.Document<java.util.List<java.util.List<float[][]>>> collision(int index) {
		final GRCollisionFile file = files.get(index);
		final String name = String.valueOf(index < meshContainers.size() ? meshContainers.get(index).getUserObject() : index);
		return new UndoHistory.Document<java.util.List<java.util.List<float[][]>>>() {
			@Override
			public Object subject() {
				return file;
			}

			@Override
			public java.util.List<java.util.List<float[][]>> take() {
				java.util.List<java.util.List<float[][]>> out = new ArrayList<>(file.meshes.length);
				for (ctrmap.formats.gfcollision.GRCollisionMesh mesh : file.meshes) {
					java.util.List<float[][]> tris = new ArrayList<>(mesh.tris.size());
					for (Triangle t : mesh.tris) {
						tris.add(new float[][]{t.x.clone(), t.y.clone(), t.z.clone()});
					}
					out.add(tris);
				}
				return out;
			}

			@Override
			public void put(java.util.List<java.util.List<float[][]>> state) {
				//the selection goes first, by its indices alone: the triangles they name are being replaced
				selectedTri = -1;
				selectedMesh = -1;
				for (int m = 0; m < file.meshes.length; m++) {
					file.meshes[m].tris.clear();
					for (float[][] t : state.get(m)) {
						file.meshes[m].tris.add(new Triangle(t[0].clone(), t[1].clone(), t[2].clone()));
					}
				}
				file.modified = true;
				if (files.contains(file)) {
					deselectTri();
					buildTree();
				}
				java.awt.Window shown = javax.swing.SwingUtilities.getWindowAncestor(CollEditPanel.this);
				if (shown != null) {
					shown.repaint();
				}
			}

			@Override
			public boolean same(java.util.List<java.util.List<float[][]>> a, java.util.List<java.util.List<float[][]>> b) {
				if (a.size() != b.size()) {
					return false;
				}
				for (int m = 0; m < a.size(); m++) {
					//each triangle's float[3][3], by content: the readings are fresh arrays every time
					if (!java.util.Arrays.deepEquals(a.get(m).toArray(), b.get(m).toArray())) {
						return false;
					}
				}
				return true;
			}

			@Override
			public String what() {
				return "the collision of " + name;
			}
		};
	}

	public void selectFile(int idx) {
		coll = files.get(idx);
		collIndex = idx;
	}

	/** The index in {@code files} of the region {@code node} is drawn under - the child of the root on its path - or -1 for the root itself. */
	int fileOf(DefaultMutableTreeNode node) {
		javax.swing.tree.TreeNode at = node;
		while (at != null && at.getParent() != root) {
			at = at.getParent();
		}
		return at == null ? -1 : root.getIndex(at);
	}

	public void selectTriangle(int mesh, int tri) {
		deselectTri();
		deselectMesh();
		if (mesh == -1 || tri == -1 || tri >= coll.meshes[mesh].tris.size()) {
			return;
		}
		Triangle triobj = coll.meshes[mesh].tris.get(tri);
		v1x.setValue(triobj.getX(0));
		v1y.setValue(triobj.getY(0));
		v1z.setValue(triobj.getZ(0));
		v2x.setValue(triobj.getX(1));
		v2y.setValue(triobj.getY(1));
		v2z.setValue(triobj.getZ(1));
		v3x.setValue(triobj.getX(2));
		v3y.setValue(triobj.getY(2));
		v3z.setValue(triobj.getZ(2));
		triobj.setSelected(true);
		selectedMesh = mesh;
		selectedTri = tri;
		btnRemTriangle.setEnabled(true);
	}

	public void deselectTri() {
		if (selectedTri != -1) {
			coll.meshes[selectedMesh].tris.get(selectedTri).setSelected(false);
		}
		selectedTri = -1;
		//REMOVE OFFERED ONLY WITH A TRIANGLE TO REMOVE (census M.148): it stayed live with nothing selected and
		//answered a press with nothing at all
		ctrmap.Ui.offer(btnRemTriangle, "Select a triangle to remove it");
		v1x.setValue(0f);
		v1y.setValue(0f);
		v1z.setValue(0f);
		v2x.setValue(0f);
		v2y.setValue(0f);
		v2z.setValue(0f);
		v3x.setValue(0f);
		v3y.setValue(0f);
		v3z.setValue(0f);
	}

	public void selectMesh(int num) {
		deselectTri();
		deselectMesh();
		for (int i = 0; i < coll.meshes[num].tris.size(); i++) {
			coll.meshes[num].tris.get(i).setSelected(true);
		}
		selectedMesh = num;
	}

	public void deselectMesh() {
		if (selectedMesh != -1) {
			for (int i = 0; i < coll.meshes[selectedMesh].tris.size(); i++) {
				coll.meshes[selectedMesh].tris.get(i).setSelected(false);
			}
		}
		selectedMesh = -1;
	}

	/**
	 * This method is called from within the constructor to initialize the form.
	 * WARNING: Do NOT modify this code. The content of this method is always
	 * regenerated by the Form Editor.
	 */
	@SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        treeScrollPane = new javax.swing.JScrollPane();
        meshTree = new javax.swing.JTree();
        v1label = new javax.swing.JLabel();
        v1xlabel = new javax.swing.JLabel();
        v1ylabel = new javax.swing.JLabel();
        v1zlabel = new javax.swing.JLabel();
        v1x = new javax.swing.JFormattedTextField();
        v1y = new javax.swing.JFormattedTextField();
        v1z = new javax.swing.JFormattedTextField();
        v2label = new javax.swing.JLabel();
        v2xlabel = new javax.swing.JLabel();
        v2ylabel = new javax.swing.JLabel();
        v2zlabel = new javax.swing.JLabel();
        v2x = new javax.swing.JFormattedTextField();
        v2y = new javax.swing.JFormattedTextField();
        v2z = new javax.swing.JFormattedTextField();
        v3label = new javax.swing.JLabel();
        v3xlabel = new javax.swing.JLabel();
        v3ylabel = new javax.swing.JLabel();
        v3zlabel = new javax.swing.JLabel();
        v3x = new javax.swing.JFormattedTextField();
        v3y = new javax.swing.JFormattedTextField();
        v3z = new javax.swing.JFormattedTextField();
        btnNewTriangle = new javax.swing.JButton();
        btnRemTriangle = new javax.swing.JButton();
        btnFillCoords = new javax.swing.JButton();

        treeScrollPane.setAlignmentX(0.0F);
        treeScrollPane.setAlignmentY(0.0F);

        javax.swing.tree.DefaultMutableTreeNode treeNode1 = new javax.swing.tree.DefaultMutableTreeNode("No map loaded");
        meshTree.setModel(new javax.swing.tree.DefaultTreeModel(treeNode1));
        treeScrollPane.setViewportView(meshTree);

        v1label.setText("Vertex 1:");

        v1xlabel.setText("X:");

        v1ylabel.setText("Y:");

        v1zlabel.setText("Z:");

        v1x.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0.00"))));

        v1y.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0.00"))));

        v1z.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0.00"))));

        v2label.setText("Vertex 2:");

        v2xlabel.setText("X:");

        v2ylabel.setText("Y:");

        v2zlabel.setText("Z:");

        v2x.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0.00"))));

        v2y.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0.00"))));

        v2z.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0.00"))));

        v3label.setText("Vertex 3:");

        v3xlabel.setText("X:");

        v3ylabel.setText("Y:");

        v3zlabel.setText("Z:");

        v3x.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0.00"))));

        v3y.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0.00"))));

        v3z.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#0.00"))));

        btnNewTriangle.setText("New triangle");
        btnNewTriangle.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnNewTriangleActionPerformed(evt);
            }
        });

        btnRemTriangle.setText("Remove triangle");
        btnRemTriangle.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnRemTriangleActionPerformed(evt);
            }
        });

        btnFillCoords.setText("New quad from fill tool");
        btnFillCoords.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnFillCoordsActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
        this.setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(treeScrollPane)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addComponent(v2label)
                        .addGroup(layout.createSequentialGroup()
                            .addComponent(v2xlabel)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                            .addComponent(v2x, javax.swing.GroupLayout.PREFERRED_SIZE, 60, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                            .addComponent(v2ylabel)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                            .addComponent(v2y, javax.swing.GroupLayout.PREFERRED_SIZE, 59, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                            .addComponent(v2zlabel)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                            .addComponent(v2z, javax.swing.GroupLayout.PREFERRED_SIZE, 60, javax.swing.GroupLayout.PREFERRED_SIZE)))
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addComponent(v3label)
                        .addGroup(layout.createSequentialGroup()
                            .addComponent(v3xlabel)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                            .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                                .addComponent(btnFillCoords, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                .addGroup(layout.createSequentialGroup()
                                    .addComponent(v3x, javax.swing.GroupLayout.PREFERRED_SIZE, 60, javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                    .addComponent(v3ylabel)
                                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                    .addComponent(v3y, javax.swing.GroupLayout.PREFERRED_SIZE, 59, javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                    .addComponent(v3zlabel)
                                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                    .addComponent(v3z, javax.swing.GroupLayout.PREFERRED_SIZE, 60, javax.swing.GroupLayout.PREFERRED_SIZE))
                                .addComponent(btnNewTriangle, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                .addComponent(btnRemTriangle, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))))
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addComponent(v1label)
                        .addGroup(layout.createSequentialGroup()
                            .addComponent(v1xlabel)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                            .addComponent(v1x, javax.swing.GroupLayout.PREFERRED_SIZE, 60, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                            .addComponent(v1ylabel)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                            .addComponent(v1y, javax.swing.GroupLayout.PREFERRED_SIZE, 59, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                            .addComponent(v1zlabel)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                            .addComponent(v1z, javax.swing.GroupLayout.PREFERRED_SIZE, 60, javax.swing.GroupLayout.PREFERRED_SIZE))))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addComponent(treeScrollPane, javax.swing.GroupLayout.DEFAULT_SIZE, 284, Short.MAX_VALUE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(v1label)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(v1xlabel)
                    .addComponent(v1ylabel)
                    .addComponent(v1zlabel)
                    .addComponent(v1x, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(v1y, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(v1z, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(v2label)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(v2xlabel)
                    .addComponent(v2ylabel)
                    .addComponent(v2zlabel)
                    .addComponent(v2x, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(v2y, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(v2z, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(v3label)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(v3xlabel)
                    .addComponent(v3ylabel)
                    .addComponent(v3zlabel)
                    .addComponent(v3x, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(v3y, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(v3z, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(btnNewTriangle)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(btnRemTriangle)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(btnFillCoords))
        );
    }// </editor-fold>//GEN-END:initComponents

    private void btnNewTriangleActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnNewTriangleActionPerformed
		if (selectedMesh != -1) {
			int index = coll.meshes[selectedMesh].tris.size();
			int originalMesh = selectedMesh; //sometimes it gets fucked up by listeners
			coll.meshes[originalMesh].tris.add(new Triangle(new float[3], new float[3], new float[3]));
			meshNodes.get(collIndex)[originalMesh].add(new DefaultMutableTreeNode("Triangle " + index));
			((DefaultTreeModel) meshTree.getModel()).reload(meshNodes.get(collIndex)[originalMesh]);
			selectedMesh = originalMesh;
			selectTriangle(selectedMesh, index);
			//CHANGED ONLY WHEN SOMETHING WAS ADDED (census M.74): marked outside the guard, it threw with no
			//collision loaded and called an untouched collision changed
			coll.modified = true;
		}
    }//GEN-LAST:event_btnNewTriangleActionPerformed

    private void btnFillCoordsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnFillCoordsActionPerformed
		AbstractTool tool = tools.current();
		if (tool != null && tool instanceof FillTool && selectedMesh != -1) {
			FillTool t = (FillTool) tool;
			if (t.lastX != -1) {
				Triangle[] quad;
				try {
					quad = quadOver(coll, t.originX, t.originY, t.lastX, t.lastY);
				} catch (IllegalStateException noGround) {
					ctrmap.Ui.error(this, ctrmap.Ui.reason(noGround), "New quad from fill tool");
					return;
				}
				coll.meshes[selectedMesh].tris.add(quad[0]);
				coll.meshes[selectedMesh].tris.add(quad[1]);
				int index = meshNodes.get(collIndex)[selectedMesh].getChildCount();
				meshNodes.get(collIndex)[selectedMesh].add(new DefaultMutableTreeNode("Triangle " + index));
				meshNodes.get(collIndex)[selectedMesh].add(new DefaultMutableTreeNode("Triangle " + (index + 1)));
				reloadMeshNode(selectedMesh);
				selectTriangle(selectedMesh, index);
				coll.modified = true;   //only when the quad was added (census M.74)
			}
		}
    }//GEN-LAST:event_btnFillCoordsActionPerformed

    private void btnRemTriangleActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnRemTriangleActionPerformed
		int mesh = selectedMesh;
		int tri = selectedTri;
		if (mesh == -1 || tri == -1) {
			return;
		}
		deselectTri();
		coll.meshes[mesh].tris.remove(tri);
		meshNodes.get(collIndex)[mesh].remove(tri);
		reloadMeshNode(mesh);
		coll.modified = true;
    }//GEN-LAST:event_btnRemTriangleActionPerformed

	private void reloadMeshNode(int num) {
		((DefaultTreeModel) meshTree.getModel()).reload(meshNodes.get(collIndex)[num]);
	}

	/** Typing in {@code field} writes that coordinate - 0 x, 1 y, 2 z - of that vertex of the selected triangle. */
	private void bindCoordinate(final JFormattedTextField field, final int axis, final int vertex) {
		field.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				adjust(field, axis, vertex);
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				adjust(field, axis, vertex);
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
			}
		});
	}

	/**
	 * Writes what a coordinate field holds - 0 x, 1 y, 2 z of that vertex - into the selected triangle, and
	 * only a number (census M.10). An empty field, a lone minus, half a number was written as 0 on every
	 * keystroke, snapping the corner to the region's origin; now nothing is written, and the field shows
	 * it was not taken.
	 */
	private void adjust(JFormattedTextField source, int axis, int vertex) {
		Float typed = ctrmap.humaninterface.Forms.typedFloat(source);
		ctrmap.humaninterface.Forms.markTaken(source, typed != null || selectedTri == -1);
		if (selectedTri == -1 || typed == null) {
			return;
		}
		Triangle t = coll.meshes[selectedMesh].tris.get(selectedTri);
		if (axis == 0) {
			t.setX(vertex, typed);
		} else if (axis == 1) {
			t.setY(vertex, typed);
		} else {
			t.setZ(vertex, typed);
		}
		coll.modified = true;
	}

	/**
	 * The two triangles of a quad over the fill tool's tiles (fromX, fromY) to (toX, toY), each corner
	 * standing on the ground the collision has under it - sampled one unit inside the quad, so a
	 * corner on a region edge is asked about the ground it covers (F246: all four sat at height 0).
	 *
	 * @throws IllegalStateException naming the corner, where the collision has no ground under it
	 */
	static Triangle[] quadOver(GRCollisionFile coll, int fromX, int fromY, int toX, int toY) {
		int startX = (Math.min(fromX, toX) % 40) * 18 - 360;
		int startZ = (Math.min(fromY, toY) % 40) * 18 - 360;
		int width = Math.abs(toX - fromX) * 18 + 18;
		int depth = Math.abs(toY - fromY) * 18 + 18;
		float[][] corner = {{startX, startZ}, {startX, startZ + depth}, {startX + width, startZ},
			{startX + width, startZ + depth}};
		float[] y = new float[4];
		for (int c = 0; c < 4; c++) {
			float inX = corner[c][0] == startX ? corner[c][0] + 1 : corner[c][0] - 1;
			float inZ = corner[c][1] == startZ ? corner[c][1] + 1 : corner[c][1] - 1;
			y[c] = coll.getHeightAtPoint(inX, inZ);
			if (Float.isNaN(y[c])) {
				throw new IllegalStateException("The collision has no ground under the corner at (" + (int) corner[c][0]
						+ ", " + (int) corner[c][1] + "), so there is no height to stand the quad on. Nothing was added.");
			}
		}
		return new Triangle[]{
			new Triangle(new float[]{corner[0][0], corner[1][0], corner[2][0]}, new float[]{y[0], y[1], y[2]},
					new float[]{corner[0][1], corner[1][1], corner[2][1]}),
			new Triangle(new float[]{corner[3][0], corner[1][0], corner[2][0]}, new float[]{y[3], y[1], y[2]},
					new float[]{corner[3][1], corner[1][1], corner[2][1]})};
	}

	public void buildTree() {
		if (files.size() > 0) {
			root.removeAllChildren();
			meshNodes.clear();
			for (int file = 0; file < files.size(); file++) {
				meshNodes.add(new DefaultMutableTreeNode[16]);
				meshContainers.get(file).removeAllChildren();
				for (int i = 0; i < 16; i++) {
					meshNodes.get(file)[i] = new DefaultMutableTreeNode("Mesh " + String.valueOf(i));
					for (int j = 0; j < files.get(file).meshes[i].tris.size(); j++) {
						meshNodes.get(file)[i].add(new DefaultMutableTreeNode("Triangle " + String.valueOf(j)));
					}
					meshContainers.get(file).add(meshNodes.get(file)[i]);
				}
				root.add(meshContainers.get(file));
			}
			((DefaultTreeModel) meshTree.getModel()).reload();
		}
	}

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnFillCoords;
    private javax.swing.JButton btnNewTriangle;
    private javax.swing.JButton btnRemTriangle;
    private javax.swing.JTree meshTree;
    private javax.swing.JScrollPane treeScrollPane;
    private javax.swing.JLabel v1label;
    private javax.swing.JFormattedTextField v1x;
    private javax.swing.JLabel v1xlabel;
    private javax.swing.JFormattedTextField v1y;
    private javax.swing.JLabel v1ylabel;
    private javax.swing.JFormattedTextField v1z;
    private javax.swing.JLabel v1zlabel;
    private javax.swing.JLabel v2label;
    private javax.swing.JFormattedTextField v2x;
    private javax.swing.JLabel v2xlabel;
    private javax.swing.JFormattedTextField v2y;
    private javax.swing.JLabel v2ylabel;
    private javax.swing.JFormattedTextField v2z;
    private javax.swing.JLabel v2zlabel;
    private javax.swing.JLabel v3label;
    private javax.swing.JFormattedTextField v3x;
    private javax.swing.JLabel v3xlabel;
    private javax.swing.JFormattedTextField v3y;
    private javax.swing.JLabel v3ylabel;
    private javax.swing.JFormattedTextField v3z;
    private javax.swing.JLabel v3zlabel;
    // End of variables declaration//GEN-END:variables
}
