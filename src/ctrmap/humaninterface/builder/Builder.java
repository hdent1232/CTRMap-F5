package ctrmap.humaninterface.builder;

import ctrmap.Workspace;
import ctrmap.formats.containers.AbstractGamefreakContainer;
import ctrmap.formats.containers.ContainerIdentifier;
import ctrmap.formats.containers.ContentType;
import ctrmap.formats.containers.GR;
import ctrmap.formats.containers.MM;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.gfcollision.GRCollisionFile;
import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.model.H3DModel;
import ctrmap.formats.mapmatrix.MapMatrix;
import ctrmap.formats.tilemap.Tilemap;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameProfile;
import ctrmap.humaninterface.ESPICAControl;
import ctrmap.humaninterface.LoadingDialog;
import ctrmap.resources.ResourceAccess;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import javax.swing.DefaultListModel;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;

public class Builder extends javax.swing.JPanel {

	/**
	 * Subfile slot of a region container's shadow ("KAGE") entry - the seventh,
	 * present only in containers that have a seventh. Its index is shared by
	 * every ORAS region, including the 34 nine-slot and 4 eleven-slot ones,
	 * which append pairs BEHIND it.
	 */
	public static final int KAGE_SLOT = 6;

	public DefaultListModel<String> GARCmodel = new DefaultListModel<>();
	public DefaultListModel<String> contModel = new DefaultListModel<>();
	public ArrayList<BuilderFile> currentFiles = new ArrayList<>();
	public AbstractGamefreakContainer currentAGFC;
	public ArchiveType currentGARC;

	/**
	 * What puts a tool in the Extras tab this browser lives in, by its title - HANDED, not a call up into
	 * the window (F007): the model converter's output shows there.
	 */
	private final java.util.function.BiConsumer<String, javax.swing.JComponent> showInExtras;

	/**
	 * This browser's history (F209c): every write it makes is one change kept here, Undo and Redo under the browser
	 * putting its files back and again and reading the archive and the container shown again.
	 */
	private final ctrmap.humaninterface.SaveHistory saves = new ctrmap.humaninterface.SaveHistory(
			() -> java.util.Collections.<Object>singletonList(Workspace.session()));

	/** The browser with Undo and Redo under it. */
	public javax.swing.JComponent withUndo() {
		return saves.around(this);
	}

	/** The history: a suite undoes through it. */
	public ctrmap.humaninterface.UndoHistory history() {
		return saves.history();
	}

	/**
	 * Keeps what {@code unit} wrote as one change of this browser's history, the archive and the container shown read
	 * again when it is taken back or put again. Each write runs in its own unit, in its own handler, and hands it here.
	 */
	private void kept(ctrmap.WorkspaceUnit unit) {
		ctrmap.WorkspaceUnit.inMemory(this::readAgain, this::readAgain);
		saves.keep(unit.commitKeeping());
	}

	/** The archive's entries and the container shown, read again - after Undo or Redo moved their files. */
	private void readAgain() {
		if (currentAGFC != null && currentAGFC.getOriginFile().isFile()) {
			currentAGFC = ContainerIdentifier.makeAGFC(currentAGFC.getOriginFile(), Workspace.session());
			reloadContainer();
		}
		loadGARC(garc.getSelectedIndex());
	}

	/**
	 * Creates new form Builder
	 */
	public Builder(java.util.function.BiConsumer<String, javax.swing.JComponent> showInExtras) {
		if (showInExtras == null) {
			throw new IllegalArgumentException("the raw browser must be handed what shows a tool in the Extras tab");
		}
		this.showInExtras = showInExtras;
		initComponents();
		garcFileList.setModel(GARCmodel);
		contFileList.setModel(contModel);
		offerContainerButtons();   //nothing is loaded yet (census M.80)
		ctrmap.Ui.offer(btnAddFileToGARC, "Choose an archive first");   //census M.136, L.59
		ctrmap.util.Selections.onSettled(garcFileList, () -> {
			contModel.removeAllElements();
			if (currentGARC != null && garcFileList.getSelectedIndex() != -1) {
				int index = garcFileList.getSelectedIndex();
				if (index < Workspace.getArchive(currentGARC).length) {
					try {
						File decFile = Workspace.getWorkspaceFile(currentGARC, index);
						byte[] magicarr = new byte[3];
						try (InputStream in = new FileInputStream(decFile)) {
							if (in.available() > 3) {
								in.read(magicarr);
							}
						}
						if (ctrmap.util.Bytes.isUTF8Capital(magicarr[0]) && ctrmap.util.Bytes.isUTF8Capital(magicarr[1]) && !ctrmap.util.Bytes.isUTF8Capital(magicarr[2])) {
							loadContainer(ContainerIdentifier.makeAGFC(decFile, magicarr, Workspace.session()));
						}
					} catch (IOException ex) {
						Logger.getLogger(Builder.class.getName()).log(Level.SEVERE, null, ex);
					}
				}
			}
		});
	}

	public void loadGARCs() {
		garc.removeAllItems();
		ArchiveType[] arcTypeEnumValues = ArchiveType.values();
		for (int i = 0; i < arcTypeEnumValues.length; i++) {
			garc.addItem(arcTypeEnumValues[i].name());
		}
	}

	/**
	 * The buttons that act on the loaded container, offered only while there is one (census M.80): Import and
	 * Import from file were the first things clicked in a panel that opens empty, and both threw on the null
	 * container - one after its body was skipped, one two lines before its own null test.
	 */
	private void offerContainerButtons() {
		for (javax.swing.JButton b : new javax.swing.JButton[]{btnFAImport, btnFAExport, btnFADummy, btnImportContainer,
				btnContSaveExternal, btnClearContainer}) {
			b.setEnabled(currentAGFC != null);
		}
	}

	public void loadContainer(AbstractGamefreakContainer cont) {
		if (cont == null) {
			throw new IllegalArgumentException("there is no container to load - open one first");
		}
		//SHOWN, NOT NAMED (census M.16): setName set the component's hidden name, and the label went on saying <none>
		container.setText(cont.getOriginFile().getAbsolutePath());
		contModel.removeAllElements();
		currentAGFC = cont;
		offerContainerButtons();
		currentFiles.clear();
		for (int i = 0; i < cont.len; i++) {
			BuilderFile bf = new BuilderFile();
			byte[] file = cont.getFile(i);
			StringBuilder name = new StringBuilder();
			name.append(i);
			String type = "";
			if (ctrmap.formats.h3d.BchSections.hasMagic(file)) {
				BCHFile bch = new BCHFile(file);
				Iterator mdlIt = bch.models.iterator();
				if (bch.models.isEmpty()) {
					if (!bch.textures.isEmpty()) {
						bf.type = ContentType.H3D_TEXTURE_PACK;
					} else {
						if (bch.contentHeader.skeletalAnimationsPointerTableEntries > 0) {
							bf.type = ContentType.H3D_ANIM_S;
						} else if (bch.contentHeader.materialAnimationsPointerTableEntries > 0) {
							bf.type = ContentType.H3D_ANIM_M;
						} else if (bch.contentHeader.visibilityAnimationsPointerTableEntries > 0) {
							bf.type = ContentType.H3D_ANIM_V;
						}
					}
				} else {
					bf.type = ContentType.H3D_MODEL;
					while (mdlIt.hasNext()) {
						type += ((H3DModel) mdlIt.next()).name;
						if (mdlIt.hasNext()) {
							type += ", ";
						}
					}
				}
			} else if (ctrmap.util.Bytes.checkMagic(file, "coll")) {
				bf.type = ContentType.COLLISION;
			} else if (ctrmap.util.Bytes.checkMagic(file, "CGFX")) {
				bf.type = ContentType.CGFX;
			} else if (file.length >= 6400 && file[0] == 0x28 && file[1] == 0x0 && file[2] == 0x28 && file[3] == 0x0) {
				bf.type = ContentType.TILEMAP;
			} else {
				bf.type = cont.getDefaultContentType(i);
			}
			if (!"".equals(type)) {
				name.append(" - ");
				name.append(type);
			} else if (!"".equals(bf.getTypeString())) {
				name.append(" - ");
				name.append(bf.getTypeString());
			}
			currentFiles.add(bf);
			contModel.addElement(name.toString());
		}
	}

	public static class BuilderFile {

		public ContentType type;

		public String getTypeString() {
			if (type == null) {
				return "";
			}
			switch (type) {
				default:
					return type.toString();
				case CGFX:
					return "CGFX";
				case COLLISION:
					return "Standard GFCollision";
				case H3D_ANIM_M:
					return "Material animation";
				case H3D_ANIM_S:
					return "Skeletal animation";
				case H3D_ANIM_V:
					return "Visibility animation";
				case H3D_MODEL:
					return "";
				case H3D_TEXTURE_PACK:
					return "BCH Texture pack";
				case TILEMAP:
					return "Standard tilemap";
				case MAPMATRIX:
					return "Map matrix";
			}
		}
	}

	public File openFileDialog(String title) {
		return fileDialog(title, false);
	}

	/** A file to WRITE: picked with a Save dialog (F147) - the two save targets were picked with Open. */
	public File saveFileDialog(String title) {
		return fileDialog(title, true);
	}

	private File fileDialog(String title, boolean save) {
		Preferences prefs = Preferences.userRoot().node(getClass().getName());
		JFileChooser jfc = new JFileChooser(prefs.get("LAST_DIR",
				new File(".").getAbsolutePath()));
		jfc.setDialogTitle(title);
		jfc.setFileSelectionMode(JFileChooser.FILES_ONLY);
		jfc.setMultiSelectionEnabled(false);
		if (ctrmap.Ui.choose(this, jfc, save) == null) {
			return null;
		}
		if (jfc.getSelectedFile() != null) {
			prefs.put("LAST_DIR", jfc.getSelectedFile().getParent());
		}
		return jfc.getSelectedFile();
	}

	/**
	 * This method is called from within the constructor to initialize the form.
	 * WARNING: Do NOT modify this code. The content of this method is always
	 * regenerated by the Form Editor.
	 */
	@SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        contLabel = new javax.swing.JLabel();
        contScrollPane = new javax.swing.JScrollPane();
        contFileList = new javax.swing.JList<>();
        fileActionsLabel = new javax.swing.JLabel();
        btnFAImport = new javax.swing.JButton();
        btnFAExport = new javax.swing.JButton();
        btnFADummy = new javax.swing.JButton();
        container = new javax.swing.JLabel();
        contActionsLabel = new javax.swing.JLabel();
        btnImportContainer = new javax.swing.JButton();
        btnContSaveExternal = new javax.swing.JButton();
        cgSep = new javax.swing.JSeparator();
        garcLabel = new javax.swing.JLabel();
        garc = new javax.swing.JComboBox<>();
        garcScrollPane = new javax.swing.JScrollPane();
        garcFileList = new javax.swing.JList<>();
        garcActionsLabel = new javax.swing.JLabel();
        btnNewContainer = new javax.swing.JButton();
        btnClearContainer = new javax.swing.JButton();
        btnAddFileToGARC = new javax.swing.JButton();

        contLabel.setText("Container:");

        contScrollPane.setViewportView(contFileList);

        fileActionsLabel.setText("File actions:");

        btnFAImport.setText("Import");
        btnFAImport.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnFAImportActionPerformed(evt);
            }
        });

        btnFAExport.setText("Export");
        btnFAExport.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnFAExportActionPerformed(evt);
            }
        });

        btnFADummy.setText("Dummy");
        btnFADummy.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnFADummyActionPerformed(evt);
            }
        });

        container.setText("<none>");

        contActionsLabel.setText("Container actions:");

        btnImportContainer.setText("Import from file");
        btnImportContainer.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnImportContainerActionPerformed(evt);
            }
        });

        btnContSaveExternal.setText("Save externally");
        btnContSaveExternal.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnContSaveExternalActionPerformed(evt);
            }
        });

        cgSep.setOrientation(javax.swing.SwingConstants.VERTICAL);

        garcLabel.setText("GARC:");

        garc.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                garcActionPerformed(evt);
            }
        });

        garcScrollPane.setViewportView(garcFileList);

        garcActionsLabel.setText("File actions:");

        btnNewContainer.setText("Create new");
        btnNewContainer.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnNewContainerActionPerformed(evt);
            }
        });

        btnClearContainer.setText("Clear");
        btnClearContainer.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnClearContainerActionPerformed(evt);
            }
        });

        btnAddFileToGARC.setText("Add new file");
        btnAddFileToGARC.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnAddFileToGARCActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
        this.setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(contActionsLabel)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(contScrollPane, javax.swing.GroupLayout.PREFERRED_SIZE, 300, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                            .addComponent(fileActionsLabel)
                            .addComponent(btnFADummy, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(btnFAExport, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(btnFAImport, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(btnClearContainer, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)))
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(contLabel)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(container))
                    .addComponent(btnNewContainer))
                .addGap(18, 18, 18)
                .addComponent(cgSep, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(btnImportContainer)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnAddFileToGARC))
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(garcLabel)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(garc, javax.swing.GroupLayout.PREFERRED_SIZE, 264, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addComponent(garcActionsLabel)
                    .addComponent(garcScrollPane, javax.swing.GroupLayout.PREFERRED_SIZE, 300, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(btnContSaveExternal))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                    .addGroup(layout.createSequentialGroup()
                        .addContainerGap()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.CENTER)
                            .addComponent(contLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 14, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(garcLabel)
                            .addComponent(garc, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(container))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(layout.createSequentialGroup()
                                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addGroup(layout.createSequentialGroup()
                                        .addComponent(fileActionsLabel)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(btnFAImport)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(btnFAExport)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(btnFADummy)
                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                        .addComponent(btnClearContainer)
                                        .addGap(0, 0, Short.MAX_VALUE))
                                    .addComponent(contScrollPane, javax.swing.GroupLayout.DEFAULT_SIZE, 213, Short.MAX_VALUE))
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(contActionsLabel)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(btnNewContainer))
                            .addGroup(layout.createSequentialGroup()
                                .addComponent(garcScrollPane)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(garcActionsLabel)
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                                .addComponent(btnContSaveExternal)))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(btnImportContainer)
                            .addComponent(btnAddFileToGARC)))
                    .addGroup(layout.createSequentialGroup()
                        .addGap(11, 11, 11)
                        .addComponent(cgSep)))
                .addGap(11, 11, 11))
        );
    }// </editor-fold>//GEN-END:initComponents

    private void garcActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_garcActionPerformed
		loadGARC(garc.getSelectedIndex());
    }//GEN-LAST:event_garcActionPerformed

    private void btnFAImportActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnFAImportActionPerformed
		int index = contFileList.getSelectedIndex();
		final AbstractGamefreakContainer persistentContainerReference = currentAGFC;
		if (index != -1) {
			//the OBJ route runs the selected BCH through ESPICA against a donor
			//BCH, which is a Gen 6 H3D question, not an "is this ORAS" one:
			//Feature.H3D_MAPS is the flag that says BCH models were verified for
			//this game, and it names OBJ import in so many words. X/Y carries it
			//(upstream CTRMap loaded XY maps), so the offer now appears there
			//too; a game without it drops to the generic byte-for-byte import
			//exactly as before.
			if (currentFiles.get(index).type == ContentType.H3D_MODEL
					&& Workspace.isValid()
					&& Workspace.profile().supports(GameProfile.Feature.H3D_MAPS)) {
				int rsl = ctrmap.Ui.confirm(this, "The file selected is a model file. Do you want to import it as OBJ?", "Builder alert", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
				if (rsl == JOptionPane.YES_OPTION) {
					File f = openFileDialog("Open a model file");
					if (f != null) {
						File donor = copyToTemp("DummyBCH3DModel.bch");
						if (donor == null) {
							ctrmap.Ui.error(this, "The converter's template model could not be written to the workspace's"
									+ " temp folder, so nothing was converted.", "Builder alert");
							return;
						}
						File output = new File(Workspace.temp() + "/espica_model_" + UUID.randomUUID().toString() + ".bch");
						int textures = ctrmap.Ui.confirm(this, "Do you want to embed the model's textures into the output BCH?", "Converter alert", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
						String[] extra = (textures == JOptionPane.YES_OPTION) ? new String[0] : new String[]{"-notextures"};
						ESPICAControl.ESPICAProcess proc = new ESPICAControl.ESPICAProcess(ESPICAControl.ESPICAFunctionMode.MODEL_CONVERT, f, donor, output, extra);
						runESPICA(proc, () -> {
							try (ctrmap.WorkspaceUnit unit = ctrmap.WorkspaceUnit.begin("Import " + f.getName()
									+ " as a model into subfile " + index, Workspace.session())) {
								persistentContainerReference.storeFile(index, output);
								Workspace.addPersist(persistentContainerReference.getOriginFile());
								kept(unit);
							} catch (RuntimeException notStored) {
								ctrmap.Ui.error(Builder.this, "Storing the converted model into the container failed:\n"
									+ ctrmap.Ui.reason(notStored) + "\n The container was not modified.", "Builder alert");
							}
						});
					}
				} else {
					importGeneric(persistentContainerReference, index);
				}
			} else if (currentFiles.get(index).type == ContentType.H3D_TEXTURE_PACK) {
				int rsl = ctrmap.Ui.confirm(this, "The file selected is a texture pack. Do you want to merge a MTL file with it?", "Builder alert", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
				if (rsl == JOptionPane.YES_OPTION) {
					File f = openFileDialog("Open a material description file");
					if (f != null) {
						File donor;
						try {
							donor = persistentContainerReference.getIOFile(index);
						} catch (IOException unread) {
							ctrmap.Ui.error(this, "The texture pack in subfile " + index + " could not be read out for the"
									+ " converter, so nothing was merged:\n" + ctrmap.Ui.reason(unread), "Builder alert");
							return;
						}
						File output = new File(Workspace.temp() + "/espica_texturepack_" + UUID.randomUUID().toString() + ".bch");
						ESPICAControl.ESPICAProcess proc = new ESPICAControl.ESPICAProcess(ESPICAControl.ESPICAFunctionMode.TEXTURE_MERGE, f, donor, output, new String[0]);
						runESPICA(proc, () -> {
							try (ctrmap.WorkspaceUnit unit = ctrmap.WorkspaceUnit.begin("Merge " + f.getName()
									+ " into the texture pack in subfile " + index, Workspace.session())) {
								persistentContainerReference.storeFile(index, output);
								Workspace.addPersist(persistentContainerReference.getOriginFile());
								kept(unit);
							} catch (RuntimeException notStored) {
								ctrmap.Ui.error(Builder.this, "Storing the merged texture pack into the container failed:\n"
									+ ctrmap.Ui.reason(notStored) + "\n The container was not modified.", "Builder alert");
							}
							reloadContainer();
						});
					}
				} else {
					importGeneric(persistentContainerReference, index);
				}
			} else {
				importGeneric(persistentContainerReference, index);
			}
			reloadContainer();   //only after an import: with nothing selected nothing was, and nothing may be loaded (census M.80)
		}
    }//GEN-LAST:event_btnFAImportActionPerformed

    private void btnImportContainerActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnImportContainerActionPerformed
		if (currentAGFC == null) {
			return;
		}
		File fToReplace = currentAGFC.getOriginFile();
		File fNew = openFileDialog("Select new container file");
		if (fNew != null) {
			//ASKED BEFORE THE COPY (census H.40): identified after it, a file that was no container was kept and packed
			String notReplaceable = ContainerIdentifier.whyNotReplaceable(fToReplace, fNew);
			if (notReplaceable != null) {
				ctrmap.Ui.error(this, "The container was not replaced: " + notReplaceable, "Builder alert");
				return;
			}
			try (ctrmap.WorkspaceUnit unit = ctrmap.WorkspaceUnit.begin("Replace " + fToReplace.getName() + " with "
					+ fNew.getName(), Workspace.session())) {
				ctrmap.WorkspaceUnit.copy(fNew, fToReplace);   //copied first by the change, so Undo can put it back
				currentAGFC = ContainerIdentifier.makeAGFC(fToReplace, Workspace.session());
				Workspace.addPersist(fToReplace);
				kept(unit);
				reloadContainer();
			} catch (IOException | RuntimeException ex) {
				Logger.getLogger(Builder.class.getName()).log(Level.SEVERE, null, ex);
				ctrmap.Ui.error(this, "The container was not replaced:\n" + ctrmap.Ui.reason(ex), "Builder alert");
			}
		}
    }//GEN-LAST:event_btnImportContainerActionPerformed

    private void btnContSaveExternalActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnContSaveExternalActionPerformed
		File fNew = saveFileDialog("Select external location");
		if (fNew != null && currentAGFC != null) {
			try {
				ctrmap.Exports.copy(currentAGFC.getOriginFile(), fNew);
			} catch (IOException ex) {
				Logger.getLogger(Builder.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
    }//GEN-LAST:event_btnContSaveExternalActionPerformed

    private void btnAddFileToGARCActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnAddFileToGARCActionPerformed
		if (currentGARC == null || !currentGARC.canMakeNewEntry()) {
			//SAID (census M.136): every type but two fell out of this method having done nothing
			ctrmap.Ui.error(this, "CTRMap can make a new entry of the MapMatrix and FieldData archives only"
					+ (currentGARC == null ? " - pick one of them first." : ", not of " + currentGARC + "."), "Builder alert");
			return;
		}
		boolean success = false;
		final AbstractGamefreakContainer before = currentAGFC;
		GARC arc = Workspace.getArchive(currentGARC);
		final ArchiveType packing = currentGARC;
		//KEPT (F209c): the new entry, written and listed, is one change of this browser's history. The pack below
		//carries it into the game at once, so Undo then refuses in words: a pack only adds entries
		try (ctrmap.WorkspaceUnit unit = ctrmap.WorkspaceUnit.begin("Add an entry to " + currentGARC, Workspace.session())) {
		if (currentGARC == ArchiveType.MAP_MATRIX) {
			MM mm = new MM(Workspace.getWorkspaceFile(currentGARC, arc.length), 2, MM.MM_MAP_MATRIX, Workspace.session());
			currentAGFC = mm;
			MapMatrix tmp = new MapMatrix(mm, 1, 1, 0);
			tmp.write();
			success = true;
		} else if (currentGARC == ArchiveType.FIELD_DATA) {
			//How many subfiles a fresh region container gets is a MEASURED
			//per-game number (ORAS 7, XY 6), so it comes from the profile, which
			//answers -1 for a game nobody has counted. It used to be
			//"isOA() ? 7 : 6", which built a 6-slot ORAS-shaped region for
			//Sun/Moon and then wrote nothing to say so.
			int subfiles = Workspace.isValid() ? Workspace.profile().fieldDataSubfileCount() : -1;
			if (subfiles < 1) {
				ctrmap.Ui.error(this, "CTRMap has not measured how many subfiles a FieldData region"
						+ " container of " + (Workspace.isValid()
								? Workspace.profile().displayName() : "this game")
						+ " holds, so it cannot build a new one."
						+ "\n\nThat number is deliberately absent rather than guessed: a container"
						+ " built with another game's slot count is a region the game cannot load."
						+ "\n\nMeasure it against a dump and fill in fieldDataSubfileCount() in that"
						+ " game's profile.", "Builder alert");
				return;
			}
			GR gr = new GR(Workspace.getWorkspaceFile(currentGARC, arc.length), subfiles, Workspace.session());
			currentAGFC = gr;
			Tilemap tm = new Tilemap(gr, 40, 40);
			GRCollisionFile coll = GRCollisionFile.blank(gr);
			tm.modified = true;
			gr.storeFile(0, tm.assembleTilemap());
			//The shadow ("KAGE") entry is the SEVENTH slot - the one ORAS added
			//on top of the six every Gen 6 region has. A game whose containers
			//hold six has no such slot, and storing into it used to be decided
			//by isOA() rather than by whether the slot exists.
			if (subfiles > KAGE_SLOT) {
				gr.storeFile(KAGE_SLOT, ResourceAccess.getByteArray("DummyKAGE.bin"));
			}
			coll.write();
			success = true;
		}
		if (success) {
			Workspace.addPersist(currentAGFC.getOriginFile());
			kept(unit);
		}
		} catch (RuntimeException notMade) {
			//SAID (census M.137): the only storeFile caller in this file that let a failed write reach nobody - the
			//button looked dead and a half-built container sat in the workspace. The unit has put back what it wrote
			currentAGFC = before;
			ctrmap.Ui.error(this, "The new " + packing + " entry was not made: " + ctrmap.Ui.reason(notMade)
					+ "\nNothing was changed.", "Builder alert");
			return;
		}
		if (success) {
			LoadingDialog dlg = LoadingDialog.makeDialog("Packing, please wait");
			SwingWorker worker = new SwingWorker() {
				@Override
				protected void done() {
					Throwable failed = null;
					try {
						get(); //without this, a pack that threw closes the dialog and the file looks added
						//THE LISTS ARE WIDGETS, so they are refilled here, on the event thread
						//(F220) - the worker used to rebuild both list models and read the
						//archive dropdown itself
						reloadContainer();
						loadGARC(garc.getSelectedIndex());
					} catch (java.util.concurrent.ExecutionException ex) {
						failed = ex.getCause() != null ? ex.getCause() : ex;
					} catch (InterruptedException | RuntimeException ex) {
						failed = ex;
					} finally {
						dlg.close();
					}
					if (failed != null) {
						Throwable cause = failed;
						Logger.getLogger(Builder.class.getName()).log(Level.SEVERE, "adding a file", cause);
						//through Ui: the workspace file exists either way, so this
						//sentence is the only thing that says the ARCHIVE does
						//not have it - and a bare dialog is not assertable
						ctrmap.Ui.error(Builder.this, "Adding the file did not finish:\n" + cause
								+ "\n\nReopen the archive to see what it holds now.", "Builder alert");
					}
				}

				@Override
				protected Object doInBackground() throws Exception {
					//through the session, which counts the pack (F209b): the entry kept above is in the game now
					Workspace.session().packOne(packing);
					return null;
				}
			};
			worker.execute();
			dlg.showDialog();
		}
    }//GEN-LAST:event_btnAddFileToGARCActionPerformed

    private void btnFAExportActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnFAExportActionPerformed
		int index = contFileList.getSelectedIndex();
		if (index != -1 && currentAGFC != null) {
			File fNew = saveFileDialog("Select export location");
			if (fNew != null) {
				try {
					ctrmap.Exports.copy(currentAGFC.getIOFile(index), fNew);
				} catch (IOException ex) {
					ctrmap.Ui.error(this, "Subfile " + index + " was not exported:\n" + ctrmap.Ui.reason(ex), "Builder alert");
				}
			}
		}
    }//GEN-LAST:event_btnFAExportActionPerformed

    private void btnFADummyActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnFADummyActionPerformed
		int index = contFileList.getSelectedIndex();
		if (index != -1 && currentAGFC != null) {
			int rsl = ctrmap.Ui.confirm(this, "This will replace the selected file with a dummy. Continue?", "Builder alert", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (rsl == JOptionPane.YES_OPTION) {
				try (ctrmap.WorkspaceUnit unit = ctrmap.WorkspaceUnit.begin("Replace subfile " + index + " with a dummy",
						Workspace.session())) {
					currentAGFC.storeFile(index, new byte[0]);
					Workspace.addPersist(currentAGFC.getOriginFile()); //storeFile writes the workspace file but only persisted files survive cleanUnchanged() and get packed
					kept(unit);
				} catch (RuntimeException notStored) {
					ctrmap.Ui.error(this, "Replacing the file with a dummy failed:\n"
						+ ctrmap.Ui.reason(notStored) + "\nThe container was not modified.", "Builder alert");
				}
				reloadContainer();
			}
		}
    }//GEN-LAST:event_btnFADummyActionPerformed

    private void btnNewContainerActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnNewContainerActionPerformed
		if (currentAGFC != null) {
			int rsl = ctrmap.Ui.confirm(this, "This will replace the container with a blank one. Continue?", "Builder alert", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (rsl == JOptionPane.YES_OPTION) {
				File target = currentAGFC.getOriginFile();
				if (!(currentAGFC instanceof GR) && !(currentAGFC instanceof MM)) {
					ctrmap.Ui.error(this, "Creating this container type from scratch is not supported.", "Builder alert");
					return;
				}
				try (ctrmap.WorkspaceUnit unit = ctrmap.WorkspaceUnit.begin("Replace " + target.getName()
						+ " with a blank container", Workspace.session())) {
					currentAGFC = currentAGFC instanceof GR ? new GR(target, currentAGFC.len, Workspace.session())
							: new MM(target, currentAGFC.len, ((MM) currentAGFC).type, Workspace.session());
					Workspace.addPersist(target);
					kept(unit);
				} catch (RuntimeException notMade) {
					ctrmap.Ui.error(this, "The blank container was not made:\n" + ctrmap.Ui.reason(notMade), "Builder alert");
				}
				reloadContainer();
			}
		}
    }//GEN-LAST:event_btnNewContainerActionPerformed

    private void btnClearContainerActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnClearContainerActionPerformed
		if (currentAGFC != null) {
			int rsl = ctrmap.Ui.confirm(this, "This will clear all files in the container. Continue?", "Builder alert", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (rsl == JOptionPane.YES_OPTION) {
				//WHOLE OR NOT AT ALL (F209c): it stopped at the first subfile that would not store and left the ones
				//before it cleared - "it may be only partially cleared". One kept change now: a failure puts back
				//every subfile it cleared, and a clear that finished is taken back by Undo
				try (ctrmap.WorkspaceUnit unit = ctrmap.WorkspaceUnit.begin("Clear " + currentAGFC.getOriginFile().getName(),
						Workspace.session())) {
					for (int i = 0; i < currentAGFC.len; i++) {
						try {
							currentAGFC.storeFile(i, new byte[0]);
						} catch (RuntimeException notStored) {
							throw new IllegalStateException("subfile " + i + ": " + ctrmap.Ui.reason(notStored), notStored);
						}
					}
					Workspace.addPersist(currentAGFC.getOriginFile()); //storeFile writes the workspace file but only persisted files survive cleanUnchanged() and get packed
					kept(unit);
				} catch (RuntimeException stopped) {
					ctrmap.Ui.error(this, "The container was not cleared - it stopped at " + ctrmap.Ui.reason(stopped)
						+ "\nNothing in it was changed.", "Builder alert");
				}
				reloadContainer();
			}
		}
    }//GEN-LAST:event_btnClearContainerActionPerformed

	private void reloadContainer() {
		loadContainer(currentAGFC);
	}

	private void importGeneric(AbstractGamefreakContainer persistentContainerReference, int index) {
		File in = openFileDialog("Select file to import");
		if (in != null) {
			try (ctrmap.WorkspaceUnit unit = ctrmap.WorkspaceUnit.begin("Import " + in.getName() + " into subfile " + index,
					Workspace.session())) {
				persistentContainerReference.storeFile(index, in);
				Workspace.addPersist(persistentContainerReference.getOriginFile()); //storeFile writes the workspace file but only persisted files survive cleanUnchanged() and get packed
				kept(unit);
			} catch (RuntimeException notStored) {
				ctrmap.Ui.error(this, "Importing the file into the container failed:\n"
					+ ctrmap.Ui.reason(notStored) + "\nThe container was not modified.", "Builder alert");
			}
		}
	}

	/**
	 * A bundled resource copied to the workspace's scratch folder, its extension kept, or null when it cannot be
	 * written there - a file of its own through the one scratch writer, not an edit (F209d).
	 */
	private static File copyToTemp(String name) {
		int dot = name.lastIndexOf('.');
		try {
			return Workspace.session().toScratch(name.substring(0, dot) + "_", name.substring(dot),
					ResourceAccess.getByteArray(name));
		} catch (java.io.IOException | RuntimeException ex) {
			return null;
		}
	}

	private void runESPICA(ESPICAControl.ESPICAProcess proc, Runnable onSuccess) {
		if (Workspace.ESPICA_PATH == null) {
			ctrmap.Ui.error(this, "ESPICA path not set or invalid. Please correct it in Workspace settings.", "ESPICA error");
		} else {
			ESPICAControl esc = new ESPICAControl(onSuccess);
			//in the Extras tab beside this browser, not a window of its own (F243)
			showInExtras.accept("ESPICA model converter", esc);
			esc.runProcess(Workspace.ESPICA_PATH, proc);
		}
	}

	public void loadGARC(int index) {
		GARCmodel.clear();
		if (index != -1) {
			currentGARC = ctrmap.util.Bytes.enumOf(ArchiveType.values(), index, null);
			ctrmap.Ui.offer(btnAddFileToGARC, currentGARC.canMakeNewEntry() ? null   //offered only where it can (census M.136)
				: "CTRMap can make a new, empty entry only of the map matrix and region archives");
			if (Workspace.getArchive(currentGARC) != null) {
				for (int i = 0; i < Workspace.getArchive(currentGARC).length; i++) {
					GARCmodel.addElement(String.valueOf(i));
				}
			}
		}
	}

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnAddFileToGARC;
    private javax.swing.JButton btnClearContainer;
    private javax.swing.JButton btnContSaveExternal;
    private javax.swing.JButton btnFADummy;
    private javax.swing.JButton btnFAExport;
    private javax.swing.JButton btnFAImport;
    private javax.swing.JButton btnImportContainer;
    private javax.swing.JButton btnNewContainer;
    private javax.swing.JSeparator cgSep;
    private javax.swing.JLabel contActionsLabel;
    private javax.swing.JList<String> contFileList;
    private javax.swing.JLabel contLabel;
    private javax.swing.JScrollPane contScrollPane;
    private javax.swing.JLabel container;
    private javax.swing.JLabel fileActionsLabel;
    private javax.swing.JComboBox<String> garc;
    private javax.swing.JLabel garcActionsLabel;
    private javax.swing.JList<String> garcFileList;
    private javax.swing.JLabel garcLabel;
    private javax.swing.JScrollPane garcScrollPane;
    // End of variables declaration//GEN-END:variables
}
