package be.nabu.libs.triton;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.cert.PKIXCertPathBuilderResult;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import be.nabu.libs.resources.ResourceUtils;
import be.nabu.libs.resources.api.ReadableResource;
import be.nabu.libs.resources.api.Resource;
import be.nabu.libs.resources.api.ResourceContainer;
import be.nabu.libs.resources.file.FileDirectory;
import be.nabu.libs.resources.file.FileItem;
import be.nabu.libs.resources.zip.ZIPArchive;
import be.nabu.libs.triton.TritonLocalConsole.TritonConsoleInstance;
import be.nabu.libs.triton.impl.PackageDescription;
import be.nabu.utils.codec.TranscoderUtils;
import be.nabu.utils.codec.api.Transcoder;
import be.nabu.utils.codec.impl.Base64Decoder;
import be.nabu.utils.codec.impl.Base64Encoder;
import be.nabu.utils.codec.impl.QuotedPrintableEncoder;
import be.nabu.utils.codec.impl.QuotedPrintableEncoding;
import be.nabu.utils.io.ContentTypeMap;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.ReadableContainer;
import be.nabu.utils.security.BCSecurityUtils;
import be.nabu.utils.security.KeyStoreHandler;
import be.nabu.utils.security.SecurityUtils;
import be.nabu.utils.security.SignatureType;

public class Triton {
	
	private TritonLocalConsole console;
	private boolean sandboxed;
	
	public static int DEFAULT_PLAIN_PORT = 5122;
	public static int DEFAULT_SECURE_PORT = 5123;
	
	public static boolean DEBUG = false;

	public Map<PackageDescription, ResourceContainer<?>> packages;
	private TritonGlueEngine glue;
	private Properties properties;
	
	public void start() {
		glue = new TritonGlueEngine(this, getScriptContainers().toArray(new ResourceContainer[0]));
		glue.setSandboxed(sandboxed);
		
		boolean enableAdmin = Boolean.parseBoolean(System.getProperty("triton.local.enabled", "true"));
		int plainPort = Integer.parseInt(System.getProperty("triton.local.port", "" + DEFAULT_PLAIN_PORT));
		int securePort = Integer.parseInt(System.getProperty("triton.secure.port", "" + DEFAULT_SECURE_PORT));
		console = new TritonLocalConsole(enableAdmin ? plainPort : null, securePort, glue, 10);
		console.start();
	}
	
	public void refreshScripts() {
		glue.reloadScriptContainers(getScriptContainers().toArray(new ResourceContainer[0]));
	}

	private List<ResourceContainer<?>> getScriptContainers() {
		// we have a local folder where you can drop scripts, this allows for some rapid prototyping stuff
		File target = getFolder("scripts");
		System.out.println("Script folder: " + target.getAbsolutePath());
		if (!target.exists()) {
			target.mkdirs();
		}
		FileDirectory scriptDirectory = new FileDirectory(null, target, false);
		
		List<ResourceContainer<?>> containers = new ArrayList<ResourceContainer<?>>();
		// the local directory always gets precedence so it is inserted first
		containers.add(scriptDirectory);
		
		for (ResourceContainer<?> archive : getPackages().values()) {
			Resource child = archive.getChild("scripts");
			if (child instanceof ResourceContainer) {
				containers.add((ResourceContainer<?>) child);
			}
		}
		return containers;
	}
	
	public TritonLocalConsole getConsole() {
		return console;
	}

	public boolean isSandboxed() {
		return sandboxed;
	}

	public void setSandboxed(boolean sandboxed) {
		this.sandboxed = sandboxed;
	}
	
	public static File getFolder() {
		return getFolder("config");
	}
	
	public static File getFolder(String name) {
		// the config can be stored right in the folder
		// everything else should be in a subfolder
		String folder = System.getProperty("triton.folder." + name, "~/.triton-" + (Main.SERVER_MODE ? "server" : "client") + ("config".equals(name) ? "" : "/" + name));
		File tritonFolder = folder.startsWith("~")
			? new File(System.getProperty("user.home"), folder.replaceFirst("^~[/]*", ""))
			: new File(folder);

		if (!tritonFolder.exists()) {
			tritonFolder.mkdirs();
		}
		return tritonFolder;
	}
	
	// considering if for scripts we want to force the root folder to be the same as the module name?
	// to prevent people from spamming over one another?
	public Map<PackageDescription, ResourceContainer<?>> getPackages() {
		if (packages == null) {
			synchronized(this) {
				if (packages == null) {
					Map<PackageDescription, ResourceContainer<?>> packages = new HashMap<PackageDescription, ResourceContainer<?>>();
					File folder = getFolder("packages");
					// only if the folder exists do we have packages
					if (folder.exists()) {
						for (File child : folder.listFiles()) {
							// we are interested in zip files only!
							if (child.isFile() && child.getName().endsWith(".zip")) {
								FileItem fileItem = new FileItem(null, child, false);
								ZIPArchive archive = new ZIPArchive();
								archive.setSource(fileItem);
								PackageDescription archiveName = getValidDescription(archive);
								if (archiveName != null) {
									archiveName.setInstallation(child);
									packages.put(archiveName, archive);
								}
								else {
									System.err.println("Invalid archive detected: " + child);
								}
							}
						}
					}
					this.packages = packages;
				}
			}
		}
		return packages;
	}
	
	private static Properties getManifest(ResourceContainer<?> archive) {
		Resource child = archive.getChild("manifest.tr");
		if (!(child instanceof ReadableResource)) {
			return null;
		}
		try {
			ReadableContainer<ByteBuffer> readable = ((ReadableResource) child).getReadable();
			try {
				Properties properties = new Properties();
				properties.load(IOUtils.toInputStream(readable));
				return properties;
			}
			finally {
				readable.close();
			}
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	private static X509Certificate getAuthor(ResourceContainer<?> archive) {
		Resource child = archive.getChild("author.crt");
		if (!(child instanceof ReadableResource)) {
			return null;
		}
		try {
			ReadableContainer<ByteBuffer> readable = ((ReadableResource) child).getReadable();
			try {
				return SecurityUtils.parseCertificate(IOUtils.toInputStream(readable));
			}
			finally {
				readable.close();
			}
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	// this validates the archive and returns the proper name for this archive
	// this is a two-fer
	private static PackageDescription getValidDescription(ResourceContainer<?> archive) {
		// TODO: validate the manifest etc
		Properties manifest = getManifest(archive);
		if (manifest == null) {
			System.err.println("No manifest in archive");
			return null;
		}
		String module = manifest.getProperty("module");
		if (module == null || !module.matches("^[a-z-]+$")) {
			System.err.println("Invalid module name: " + module);
			return null;
		}
		X509Certificate author = getAuthor(archive);
		if (author == null) {
			System.err.println("Archive has no author");
			return null;
		}
		KeyStoreHandler packagingKeystore = TritonLocalConsole.getPackagingKeystore();
		if (!isTrusted(new X509Certificate[] { author }, packagingKeystore)) {
			System.err.println("Untrusted author detected: " + TritonLocalConsole.getAlias(author));
			return null;
		}
		// trusting the author is a good first step, but anyone can slap in the author cert into an otherwise custom made zip
		if (!isValid(archive, null, manifest, author, true)) {
			return null;
		}
		PackageDescription description = new PackageDescription();
		description.setAuthor(TritonLocalConsole.getAlias(author));
		description.setModule(module);
		description.setVersion(manifest.getProperty("version"));
		description.setCertificate(author);
		return description;
	}
	
	public static byte [] decode(String content) {
		try {
			Base64Decoder transcoder = new Base64Decoder();
			// we don't want trailing "=" and special characters
			transcoder.setUseBase64Url(true);
			return IOUtils.toBytes(TranscoderUtils.transcodeBytes(IOUtils.wrap(content.getBytes("ASCII"), true), transcoder));
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	public static String encode(byte [] content) {
		try {
			Base64Encoder transcoder = new Base64Encoder();
			// we don't want trailing "=" and special characters
			transcoder.setUseBase64Url(true);
			// we want a oneliner
			transcoder.setBytesPerLine(0);
			return new String(IOUtils.toBytes(TranscoderUtils.transcodeBytes(IOUtils.wrap(content, true), transcoder)), "ASCII");
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	private static boolean isValid(ResourceContainer<?> root, String path, Properties manifest, X509Certificate author, boolean isRoot) {
		for (Resource resource : root) {
			String childPath = path == null ? resource.getName() : path + "/" + resource.getName();
			// only files have to be signed, not directories
			// we don't sign files on the very root of the package
			if (resource instanceof ReadableResource && !isRoot) {
				if (!isValidFile((ReadableResource) resource, childPath, manifest, author)) {
					return false;
				}
			}
			// recurse
			if (resource instanceof ResourceContainer) {
				if (!isValid((ResourceContainer<?>) resource, childPath, manifest, author, false)) {
					return false;
				}
			}
		}
		return true;
	}
	
	private static boolean isValidFile(ReadableResource file, String path, Properties manifest, X509Certificate author) {
		try {
			String signature = manifest.getProperty("signature-" + path);
			if (signature == null) {
				System.err.println("Could not find signature for: " + path);
				return false;
			}
			byte[] decoded = decode(signature);
			ReadableContainer<ByteBuffer> readable = file.getReadable();
			try {
				if (!SecurityUtils.verify(IOUtils.toInputStream(readable), decoded, author.getPublicKey(), SignatureType.SHA512WITHRSA)) {
					System.err.println("Invalid signature for file: " + path);
					return false;
				}
				return true;
			}
			finally {
				readable.close();
			}
		}
		catch (Exception e) {
			if (DEBUG) {
				e.printStackTrace();
			}
			return false;
		}
	}
	
	// TODO: validate that this works with correct chains etc, currently it has only been tested with self signed single ones
	public static boolean isTrusted(X509Certificate [] chain, KeyStoreHandler keystore) {
		try {
			// if we trust the certificate itself, we good
			// otherwise we check the chain
			if (keystore.getCertificates().values().contains(chain[0])) {
				return true;
			}
			// check if it matches a private key that we have
			for (X509Certificate [] potential : keystore.getPrivateKeys().values()) {
				if (potential[0].equals(chain[0])) {
					return true;
				}
			}
			PKIXCertPathBuilderResult validateCertificateChain = BCSecurityUtils.validateCertificateChain(chain, keystore.getCertificates().values().toArray(new X509Certificate[0]));
			if (validateCertificateChain == null) {
				return false;
			}
			return true;
		}
		catch (GeneralSecurityException e) {
			if (DEBUG) {
				e.printStackTrace();
			}
			return false;
		}
	}
	
	// this assumes it has already been validated
	public void install(Resource item, boolean force) {
		ZIPArchive archive = toArchive(item);

		// we check the author separately, in most cases a failed installation is due to an unknown author
		// if we have an interaction channel to the user, we can ask him if he wants to allow the author
		TritonConsoleInstance console = TritonLocalConsole.getConsole();
		if (console != null && console.getInputProvider() != null) {
			X509Certificate author = getAuthor(archive);
			if (author != null) {
				KeyStoreHandler packagingKeystore = TritonLocalConsole.getPackagingKeystore();
				if (!isTrusted(new X509Certificate[] { author }, packagingKeystore)) {
					try {
						// adding an author is a big ask, you have to be sure so we take N by default
						String result = console.getInputProvider().input("The author '" + TritonLocalConsole.getAlias(author) + "' is not trusted, do you want to add the author to your list of trusted authors? [y/N]: ", false, "y");
						if (result != null && "y".equalsIgnoreCase(result.trim())) {
							if (!packagingKeystore.getCertificates().values().contains(author)) {
								packagingKeystore.set("user-" + TritonLocalConsole.getAlias(author), author);
								TritonLocalConsole.savePackaging(packagingKeystore);
							}
							else {
								System.out.println("Already trusted");
							}
						}
						// we actively chose not to install, no point in continuing
						else {
							return;
						}
					}
					catch (Exception e) {
						throw new RuntimeException(e);
					}
				}
			}
		}
		
		PackageDescription description = getValidDescription(archive);
		if (description == null) {
			throw new IllegalArgumentException("Archive is not trusted");
		}
		
		// we need to check if there is already another version of this module by that author
		// we want to force people to think about versions so if the version is the same, we do nothing
		for (PackageDescription existing : getPackages().keySet()) {
			if (existing.getCertificate().equals(description.getCertificate()) && existing.getModule().equals(description.getModule())) {
				// if we don't force it, we ask or not do it
				if (!force) {
					// if it's interactive, we can ask the user
					if (console != null && console.getInputProvider() != null) {
						try {
							String input = console.getInputProvider().input("You already have version '" + existing.getVersion() + "' of module '" + description.getModule() + "' installed. Do you want to install version '" + description.getVersion() + "' [y/N]: ", false, "y");
							if (!input.trim().isEmpty() && "y".equalsIgnoreCase(input.trim())) {
								// do nothing, the default is uninstall
							}
							// we explicitly choose not to do this
							else {
								return;
							}
						}
						catch (Exception e) {
							throw new RuntimeException(e);
						}
					}
					else if (existing.getVersion().equals(description.getVersion())) {
						System.out.println("This module is already installed");
						return;
					}
					// otherwise we don't install the update
					else {
						throw new IllegalStateException("There is already a version of this module present, please remove that before installing the new one");
					}
				}
				// if we get here, we want to uninstall it
				uninstall(existing);
				break;
			}
		}
		
		boolean requireReload = false;
		for (Resource child : archive) {
			// we only install full directories
			if (child instanceof ResourceContainer) {
				// script folders are read from the zip itself
				if ("scripts".equals(child.getName())) {
					requireReload = true;
					continue;
				}
				// the target folder
				File target = getFolder(child.getName());
				if (!target.exists()) {
					target.mkdirs();
				}
				try {
					ResourceUtils.copy(child, new FileDirectory(null, target, false), null, true, true);
				}
				catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		}
		Map<PackageDescription, ResourceContainer<?>> packages = getPackages();
		// once installed, we add it to the packages folder
		File folder = getFolder("packages");
		if (!folder.exists()) {
			folder.mkdirs();
		}
		File file = new File(folder, generateUniqueName(description.getModule() + "-" + description.getVersion(), "application/zip"));
		try (OutputStream output = new BufferedOutputStream(new FileOutputStream(file)); ReadableContainer<ByteBuffer> readable = ((ReadableResource) item).getReadable()) {
			IOUtils.copyBytes(readable, IOUtils.wrap(output));
			description.setInstallation(file);
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
		// add to the installed packages
		synchronized(packages) {
			packages.put(description, archive);
		}
		// TODO: run any post-installation scripts available in the zip
		
		// if new scripts were added, reload
		if (requireReload) {
			refreshScripts();
		}
	}

	public static ZIPArchive toArchive(Resource item) {
		// TODO: run pre-installation scripts?
		if (!(item instanceof ReadableResource)) {
			throw new IllegalArgumentException("Can only use readable files");
		}
		ZIPArchive archive = new ZIPArchive();
		archive.setSource(item);
		return archive;
	}
	
	public void uninstall(PackageDescription description) {
		Map<PackageDescription, ResourceContainer<?>> packages = getPackages();
		File installation = description.getInstallation();
		System.out.println("Uninstalling: " + installation);
		if (installation != null && installation.exists()) {
			boolean requireReload = uninstall(new FileItem(null, installation, false));
			installation.delete();
			synchronized(packages) {
				packages.remove(description);
			}
			if (requireReload) {
				refreshScripts();
			}
		}
	}
	
	public List<PackageDescription> getInstalled() {
		return new ArrayList<PackageDescription>(getPackages().keySet());
	}
	
	// uninstall a resource
	private boolean uninstall(Resource item) {
		boolean requireReload = false;
		ZIPArchive archive = toArchive(item);
		Properties manifest = getManifest(archive);
		if (manifest == null) {
			throw new IllegalArgumentException("Can not find manifest");
		}
		X509Certificate author = getAuthor(archive);
		if (author == null) {
			throw new IllegalArgumentException("Can not find author");
		}
		// TODO: run pre-uninstall hooks
		for (Resource child : archive) {
			// we only install full directories
			if (child instanceof ResourceContainer) {
				// script folders are read from the zip itself
				if ("scripts".equals(child.getName())) {
					requireReload = true;
					continue;
				}
				File folder = getFolder(child.getName());
				if (folder.exists()) {
					removeIfValid(manifest, author, folder, null, (ResourceContainer<?>) child);
				}
			}
		}
		// TODO: run post-uninstall hooks
		
		return requireReload;
	}
	
	private static void removeIfValid(Properties manifest, X509Certificate author, File file, String path, ResourceContainer<?> current) {
		for (Resource child : current) {
			File childFile = new File(file, child.getName());
			String childPath = path == null ? child.getName() : path + "/" + child.getName();
			// only relevant if it exists
			if (childFile.exists()) {
				if (child instanceof ResourceContainer) {
					removeIfValid(manifest, author, childFile, childPath, (ResourceContainer<?>) child);
					if (childFile.listFiles().length == 0) {
						childFile.delete();
					}
				}
				// we check that the signature still matches, only remove files that are from this archive
				else if (child instanceof ReadableResource) {
					if (isValidFile((ReadableResource) child, childPath, manifest, author)) {
						childFile.delete();
					}
				}
			}
		}
	}
	
	/**
	 * The name in a resource container must be unique
	 * However the user must be able to store multiple resources with the same name which may end up in the same folder
	 */
	public static String generateUniqueName(String name, String contentType) {
		return transcode(name, new QuotedPrintableEncoder(QuotedPrintableEncoding.ALL)) + "." + UUID.randomUUID().toString() + "." + ContentTypeMap.getInstance().getExtensionFor(contentType);
	}

	public static String transcode(String name, Transcoder<ByteBuffer> transcoder) {
		try {
			byte[] bytes = IOUtils.toBytes(TranscoderUtils.transcodeBytes(IOUtils.wrap(name.getBytes(Charset.forName("UTF-8")), true), transcoder));
			return new String(bytes, "UTF-8");
		}
		catch (IOException e) {
			throw new RuntimeException("Could not encode name: " + name, e);
		}
	}
	
	public Properties getConfiguration() {
		if (properties == null) {
			synchronized(this) {
				if (properties == null) {
					Properties properties = new Properties();
					File file = new File(getFolder(), "triton.properties");
					if (file.exists()) {
						try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
							properties.load(input);
						}
						catch (Exception e) {
							throw new RuntimeException(e);
						}
					}
					this.properties = properties;
				}
			}
		}
		return properties;
	}
	
	public void setConfiguration(Properties properties) {
		File file = new File(getFolder(), "triton.properties");
		if (file.exists()) {
			try (OutputStream output = new BufferedOutputStream(new FileOutputStream(file))) {
				properties.store(output, null);
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}
}
