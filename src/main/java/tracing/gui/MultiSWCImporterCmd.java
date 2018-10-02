/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2018 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package tracing.gui;

import java.io.File;
import java.io.FileFilter;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;

import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import net.imagej.ImageJ;
import tracing.PathAndFillManager;
import tracing.SNT;
import tracing.SNTService;
import tracing.SNTUI;
import tracing.SimpleNeuriteTracer;

/**
 * Command for importing a folder of SWC files.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label = "Import Directory of SWC files")
public class MultiSWCImporterCmd extends ContextCommand {

	@Parameter
	private SNTService sntService;

	@Parameter(style = "directory", label = "Directory")
	private File dir;

	@Parameter(label = "Filenames containing", //
			description = "<html>Only files containing this string will be considered."
					+ "<br>Leave blank to consider all SWC files in the directory.")
	private String pattern;

	@Parameter(required = false, label = "Replace existing paths")
	private boolean clearExisting;

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {

		final SimpleNeuriteTracer snt = sntService.getPlugin();
		final SNTUI ui = sntService.getUI();
		final PathAndFillManager pafm = sntService.getPathAndFillManager();

		final Map<String, String> importMap = getImportMap();
		if (importMap == null || importMap.isEmpty()) {
			cancel("No matching files fould in directory.");
			return;
		}

		ui.showStatus("Importing directory. Please wait...", false);
		SNT.log("Importing directory " + dir);

		final int lastExistingPathIdx = pafm.size() - 1;
		final Map<String, Boolean> result = pafm.importSWCs(importMap);
		if (!result.containsValue(true)) {
			snt.error("No reconstructions could be retrieved");
			ui.showStatus("Error... No reconstructions imported", true);
			return;
		}
		if (clearExisting) {
			final int[] indices = IntStream.rangeClosed(0, lastExistingPathIdx).toArray();
			pafm.deletePaths(indices);
		}
		SNT.log("Rebuilding canvases...");
		snt.rebuildDisplayCanvases();
		final long failures = result.values().stream().filter(p -> p == false).count();
		if (failures > 0) {
			snt.error(String.format("%d/%d reconstructions could not be retrieved.", failures, result.size()));
			ui.showStatus("Partially successful import...", true);
			SNT.log("Import failed for the following queried morphologies:");
			result.forEach((key, value) -> {
				if (!value)
					SNT.log(key);
			});
		} else {
			ui.showStatus("Successful imported " + result.size() + " reconstruction(s)...", true);
		}
	}

	private Map<String, String> getImportMap() {
		if (dir == null || !dir.isDirectory() || !dir.exists())
			return null;
		final File[] files = dir.listFiles((FileFilter) file -> {
			if (file.isHidden())
				return false;
			final String fName = file.getName().toLowerCase();
			return fName.endsWith("swc") && fName.contains(pattern);
		});
		if (files == null || files.length == 0)
			return null;
		final Map<String, String> map = new HashMap<String, String>();
		for (final File file : files) {
			map.put(SNT.stripExtension(file.getName()), file.getAbsolutePath());
		}
		return map;
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setSystemLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(MultiSWCImporterCmd.class, true);
	}

}