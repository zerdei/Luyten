package com.modcrafting.luyten;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JProgressBar;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import com.modcrafting.luyten.DebugInfoWriter.WriteMode;
import com.strobel.assembler.metadata.ITypeLoader;
import com.strobel.assembler.metadata.JarTypeLoader;
import com.strobel.assembler.metadata.MetadataSystem;
import com.strobel.assembler.metadata.TypeDefinition;
import com.strobel.assembler.metadata.TypeReference;
import com.strobel.core.StringUtilities;
import com.strobel.decompiler.DecompilationOptions;
import com.strobel.decompiler.DecompilerSettings;
import com.strobel.decompiler.languages.java.JavaLanguage;
import com.strobel.decompiler.languages.java.JavaTypeDecompilationResults;
import com.strobel.decompiler.languages.java.PlainTextOutputWithBytecodeToLineNumberTable;

/**
 * Performs Save All Debugable
 */
public class FileSaverDebugable extends FileSaver {

	private final LuytenPreferences luytenPrefs;

	public FileSaverDebugable(JProgressBar bar, JLabel label) {
		super(bar, label);
		ConfigSaver configSaver = ConfigSaver.getLoadedInstance();
		luytenPrefs = configSaver.getLuytenPreferences();
	}

	public void saveAllDebugable(final File inFile, final File outFile) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					checkFileDoMkdirs(outFile);
					bar.setVisible(true);
					label.setText("Extracting: " + outFile.getName());
					String inFilePath = inFile.getCanonicalPath();
					String outFilePath = outFile.getCanonicalPath();

					if (!outFilePath.toLowerCase().endsWith("jar") &&
							!outFilePath.toLowerCase().endsWith("zip")) {
						throw new RuntimeException("Unsupported output file format: " + outFilePath);
					}

					if (inFilePath.toLowerCase().endsWith(".jar") ||
							inFilePath.toLowerCase().endsWith(".zip")) {
						Map<String, Exception> exceptions = doSaveJarDebugable(inFilePath, outFilePath);
						showDecompilingExceptions(exceptions, outFilePath);
					} else if (inFilePath.toLowerCase().endsWith(".class")) {
						doSaveClassDebugable(inFilePath, outFilePath);
					} else {
						throw new RuntimeException("Unsupported input file format: " + inFilePath);
					}

					label.setText("Complete");
				} catch (Exception e1) {
					e1.printStackTrace();
					label.setText("Cannot save file: " + outFile.getName());
					JOptionPane.showMessageDialog(null, e1.toString(), "Error!", JOptionPane.ERROR_MESSAGE);
				} finally {
					bar.setVisible(false);
				}
			}
		}).start();
	}

	private void showDecompilingExceptions(Map<String, Exception> decompilingExceptions,
			String outFilePath) throws Exception {

		if (decompilingExceptions == null || decompilingExceptions.size() < 1)
			return;

		String errorFilePath = outFilePath.replaceAll("\\.[^\\.]*$", "") + "-error.txt";
		try (FileOutputStream fos = new FileOutputStream(errorFilePath);
				PrintWriter err = new PrintWriter(fos);) {

			for (String nextEntry : decompilingExceptions.keySet()) {
				Exception nextException = decompilingExceptions.get(nextEntry);
				err.println(nextEntry);
				nextException.printStackTrace(err);
				err.println();
			}
		}

		JOptionPane.showMessageDialog(null, "Completed with error: Could not generate source for " +
				decompilingExceptions.size() + " class" + (decompilingExceptions.size() > 1 ? "es." : ".") +
				" Find details in: " + errorFilePath, "Error!", JOptionPane.ERROR_MESSAGE);
	}

	private Map<String, Exception> doSaveJarDebugable(String inFilePath, String outFilePath) throws Exception {
		Map<String, Exception> decompilingExceptions = new LinkedHashMap<>();
		try (JarFile jfile = new JarFile(inFilePath);
				FileOutputStream dest = new FileOutputStream(outFilePath);
				BufferedOutputStream buffDest = new BufferedOutputStream(dest);
				ZipOutputStream out = new ZipOutputStream(buffDest);) {

			DecompilerSettings settings = cloneSettings();
			LuytenTypeLoader typeLoader = new LuytenTypeLoader();
			MetadataSystem metadataSystem = new MetadataSystem(typeLoader);
			ITypeLoader jarLoader = new JarTypeLoader(jfile);
			typeLoader.getTypeLoaders().add(jarLoader);

			DecompilationOptions decompilationOptions = new DecompilationOptions();
			decompilationOptions.setSettings(settings);
			decompilationOptions.setFullDecompilation(true);

			JarEntryFilter jarEntryFilter = new JarEntryFilter(jfile);
			List<String> mass = jarEntryFilter.getEntriesWithoutInnerClasses();

			Set<String> jarDirectories = new TreeSet<>();
			Enumeration<JarEntry> ent = jfile.entries();
			while (ent.hasMoreElements()) {
				JarEntry entry = ent.nextElement();
				if (!entry.isDirectory()) {
					jarDirectories.add(entry.getName().replaceAll("[^/]+$", ""));
				}
			}

			for (String jarDirectory : jarDirectories) {
				decompilingExceptions.putAll(saveJarPackageDebugable(metadataSystem,
						decompilationOptions, jfile, mass, jarDirectory, out));
			}
		}
		return decompilingExceptions;
	}

	private Map<String, Exception> saveJarPackageDebugable(MetadataSystem metadataSystem,
			DecompilationOptions decompilationOptions, JarFile jfile, List<String> mass,
			String jarDirectory, ZipOutputStream out) throws Exception {

		Map<String, Exception> decompilingExceptions = new LinkedHashMap<>();
		DebugInfoWriter debugInfoWriter = createDebugInfoWriter();
		Enumeration<JarEntry> ent = jfile.entries();
		while (ent.hasMoreElements()) {
			JarEntry entry = ent.nextElement();
			if (entry.isDirectory())
				continue;
			String entryName = entry.getName();
			if (entryName == null || !entryName.startsWith(jarDirectory))
				continue;
			String entryFileName = entryName.substring(jarDirectory.length());
			if (entryFileName.contains("/"))
				continue;
			if (!mass.contains(entryName) || entryName.endsWith(".java"))
				continue;

			label.setText("Extracting: " + entryName);
			bar.setVisible(true);
			if (entryName.endsWith(".class")) {
				JarEntry etn = new JarEntry(entryName.replace(".class", ".java"));
				label.setText("Extracting: " + etn.getName());
				out.putNextEntry(etn);
				try {
					String internalName = StringUtilities.removeRight(entryName, ".class");
					TypeReference type = metadataSystem.lookupType(internalName);
					TypeDefinition resolvedType = null;
					if ((type == null) || ((resolvedType = type.resolve()) == null)) {
						throw new Exception("Unable to resolve type.");
					}

					StringWriter stringwriter = new StringWriter();
					PlainTextOutputWithBytecodeToLineNumberTable output =
							new PlainTextOutputWithBytecodeToLineNumberTable(stringwriter);
					JavaTypeDecompilationResults results = null;
					try {
						results = new JavaLanguage().decompileType(resolvedType, output, decompilationOptions);
					} catch (Exception e) {
						decompilingExceptions.put(entryName, e);
					}

					String decompiledSource = "";
					if (!decompilingExceptions.containsKey(entryName)) {
						decompiledSource = stringwriter.toString();

						String typeSignature = resolvedType.getErasedSignature();
						String classSimpleName = entryFileName.replaceAll("\\.class$", "");
						String javaFileName = classSimpleName + ".java";

						debugInfoWriter.addSourceFileName(typeSignature, javaFileName);
						debugInfoWriter.addLineNumbers(output.getGeneratedLineNumbers());
						debugInfoWriter.addVariableTables(results.generateVariableTables());
						addSourceFileNameToDeclaredTypes(debugInfoWriter, resolvedType, javaFileName);
					}

					IOUtils.write(decompiledSource, out);
				} finally {
					out.closeEntry();
				}
			} else {
				try {
					JarEntry etn = new JarEntry(entryName);
					out.putNextEntry(etn);
					try {
						try (InputStream in = jfile.getInputStream(entry);) {
							if (in != null) {
								IOUtils.copy(in, out);
							}
						}
					} finally {
						out.closeEntry();
					}
				} catch (ZipException ze) {
					// some jar-s contain duplicate pom.xml entries: ignore it
					if (!ze.getMessage().contains("duplicate")) {
						throw ze;
					}
				}
			}
		}
		addUpdatedClassesToOutPackage(debugInfoWriter, metadataSystem,
				jfile, jarDirectory, out);
		return decompilingExceptions;
	}

	private void addSourceFileNameToDeclaredTypes(DebugInfoWriter debugInfoWriter,
			TypeDefinition type, String javaFileName) {

		if (debugInfoWriter == null || type == null || javaFileName == null)
			return;
		if (javaFileName.trim().length() < 1)
			return;

		for (TypeDefinition nextType : type.getDeclaredTypes()) {
			addSourceFileNameToDeclaredTypes(debugInfoWriter, nextType, javaFileName);
			String nextTypeSignature = nextType.getErasedSignature();
			if (nextTypeSignature == null || nextTypeSignature.trim().length() < 1)
				continue;
			debugInfoWriter.addSourceFileName(nextTypeSignature, javaFileName);
		}
	}

	private void addUpdatedClassesToOutPackage(DebugInfoWriter debugInfoWriter, MetadataSystem metadataSystem,
			JarFile jfile, String jarDirectory, ZipOutputStream out) throws Exception {

		Enumeration<JarEntry> ent = jfile.entries();
		while (ent.hasMoreElements()) {
			JarEntry entry = ent.nextElement();
			if (entry.isDirectory())
				continue;
			String entryName = entry.getName();
			if (entryName == null || !entryName.startsWith(jarDirectory))
				continue;
			String entryFileName = entryName.substring(jarDirectory.length());
			if (entryFileName.contains("/"))
				continue;
			if (!entryName.endsWith(".class"))
				continue;

			label.setText("Updating Debug Info: " + entryName);
			bar.setVisible(true);

			String internalName = StringUtilities.removeRight(entryName, ".class");
			TypeReference nextType = metadataSystem.lookupType(internalName);
			if (nextType == null)
				continue;
			TypeDefinition nextTypeDef = nextType.resolve();
			if (nextTypeDef == null)
				continue;
			String nextTypeSignature = nextTypeDef.getErasedSignature();
			if (nextTypeSignature == null || nextTypeSignature.trim().length() < 1)
				continue;

			byte[] inputClassBytes = null;
			try (InputStream in = jfile.getInputStream(entry);) {
				inputClassBytes = IOUtils.toByteArray(in);
			}
			if (inputClassBytes == null || inputClassBytes.length < 1)
				continue;
			byte[] outputClassBytes = debugInfoWriter.writeDebugInfo(inputClassBytes, nextTypeSignature);
			if (outputClassBytes == null || outputClassBytes.length < 1)
				continue;

			ZipEntry classFileEntry = new ZipEntry(entryName);
			out.putNextEntry(classFileEntry);
			try {
				IOUtils.write(outputClassBytes, out);
			} finally {
				out.closeEntry();
			}
		}
	}

	private void doSaveClassDebugable(String inFilePath, String outFilePath) throws Exception {
		DecompilerSettings settings = cloneSettings();
		LuytenTypeLoader typeLoader = new LuytenTypeLoader();
		MetadataSystem metadataSystem = new MetadataSystem(typeLoader);
		TypeReference type = metadataSystem.lookupType(inFilePath);

		DecompilationOptions decompilationOptions = new DecompilationOptions();
		decompilationOptions.setSettings(settings);
		decompilationOptions.setFullDecompilation(true);

		TypeDefinition resolvedType = null;
		if (type == null || ((resolvedType = type.resolve()) == null)) {
			throw new Exception("Unable to resolve type.");
		}

		StringWriter stringwriter = new StringWriter();
		PlainTextOutputWithBytecodeToLineNumberTable output =
				new PlainTextOutputWithBytecodeToLineNumberTable(stringwriter);
		JavaTypeDecompilationResults results = new JavaLanguage()
				.decompileType(resolvedType, output, decompilationOptions);
		String decompiledSource = stringwriter.toString();

		String typeSignature = resolvedType.getErasedSignature();
		String classFileName = new File(inFilePath).getName();
		String classSimpleName = classFileName.replaceAll("\\.class$", "");
		String javaFileName = classSimpleName + ".java";

		DebugInfoWriter debugInfoWriter = createDebugInfoWriter();
		debugInfoWriter.addSourceFileName(typeSignature, javaFileName);
		debugInfoWriter.addLineNumbers(output.getGeneratedLineNumbers());
		debugInfoWriter.addVariableTables(results.generateVariableTables());
		addSourceFileNameToDeclaredTypes(debugInfoWriter, resolvedType, javaFileName);

		try (FileOutputStream dest = new FileOutputStream(outFilePath);
				BufferedOutputStream buffDest = new BufferedOutputStream(dest);
				ZipOutputStream out = new ZipOutputStream(buffDest);) {

			String pathInJar = resolvedType.getPackageName().replace(".", "/");
			ZipEntry javaFileEntry = new ZipEntry(pathInJar + "/" + javaFileName);
			out.putNextEntry(javaFileEntry);
			try {
				IOUtils.write(decompiledSource, out);
			} finally {
				out.closeEntry();
			}

			Path inDirectory = Paths.get(inFilePath).getParent();
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(inDirectory, "*.class");) {
				for (Iterator<Path> it = stream.iterator(); it.hasNext();) {
					Path next = it.next();
					String nextFileName = next.getFileName().toString();
					String nextClassSimpleName = nextFileName.replaceAll("\\.class$", "");
					if (nextClassSimpleName.equals(classSimpleName) ||
							nextClassSimpleName.startsWith(classSimpleName + "$")) {

						TypeReference nextType = metadataSystem.lookupType(next.toFile().getCanonicalPath());
						if (nextType == null)
							continue;
						TypeDefinition nextTypeDef = nextType.resolve();
						if (nextTypeDef == null)
							continue;
						String nextTypeSignature = nextTypeDef.getErasedSignature();
						if (nextTypeSignature == null || nextTypeSignature.trim().length() < 1)
							continue;

						byte[] inputClassBytes = FileUtils.readFileToByteArray(next.toFile());
						if (inputClassBytes == null || inputClassBytes.length < 1)
							continue;
						byte[] outputClassBytes = debugInfoWriter.writeDebugInfo(inputClassBytes, nextTypeSignature);
						if (outputClassBytes == null || outputClassBytes.length < 1)
							continue;

						String classFilePathInJar = pathInJar + "/" + nextClassSimpleName + ".class";
						ZipEntry classFileEntry = new ZipEntry(classFilePathInJar);
						out.putNextEntry(classFileEntry);
						try {
							IOUtils.write(outputClassBytes, out);
						} finally {
							out.closeEntry();
						}

					}
				}
			}
		}
	}

	private DebugInfoWriter createDebugInfoWriter() {
		DebugInfoWriter debugInfoWriter = new DebugInfoWriter();
		debugInfoWriter.setSorceFileInfoWriteMode(WriteMode.OVERWRITE_ALL);
		debugInfoWriter.setLineNumberTableWriteMode(WriteMode.OVERWRITE_ALL);
		if (luytenPrefs.isKeepExistingVariableTablesWhenSaveDebugable()) {
			debugInfoWriter.setVariableTableWriteMode(WriteMode.KEEP_ORIGINAL_WRITE_IF_NOT_EXISTS);
		} else {
			debugInfoWriter.setVariableTableWriteMode(WriteMode.OVERWRITE_ALL);
		}
		return debugInfoWriter;
	}
}
