package be.nabu.libs.triton;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStoreException;
import java.security.cert.PKIXCertPathBuilderResult;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import be.nabu.libs.resources.ResourceReadableContainer;
import be.nabu.libs.resources.api.ReadableResource;
import be.nabu.libs.resources.api.Resource;
import be.nabu.libs.resources.api.ResourceContainer;
import be.nabu.libs.resources.file.FileDirectory;
import be.nabu.libs.resources.file.FileItem;
import be.nabu.libs.resources.zip.ZIPArchive;
import be.nabu.utils.codec.TranscoderUtils;
import be.nabu.utils.codec.impl.Base64Decoder;
import be.nabu.utils.codec.impl.Base64Encoder;
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

	public Map<String, ResourceContainer<?>> packages;
	
	public void start() {
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
		
		TritonGlueEngine glue = new TritonGlueEngine(this, containers.toArray(new ResourceContainer[0]));
		glue.setSandboxed(sandboxed);
		
		boolean enableAdmin = Boolean.parseBoolean(System.getProperty("triton.local.enabled", "true"));
		int plainPort = Integer.parseInt(System.getProperty("triton.local.port", "" + DEFAULT_PLAIN_PORT));
		int securePort = Integer.parseInt(System.getProperty("triton.secure.port", "" + DEFAULT_SECURE_PORT));
		console = new TritonLocalConsole(enableAdmin ? plainPort : null, securePort, glue, 10);
		console.start();
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
	public Map<String, ResourceContainer<?>> getPackages() {
		if (packages == null) {
			synchronized(this) {
				if (packages == null) {
					Map<String, ResourceContainer<?>> packages = new HashMap<String, ResourceContainer<?>>();
					File folder = getFolder("packages");
					// only if the folder exists do we have packages
					if (folder.exists()) {
						for (File child : folder.listFiles()) {
							// we are interested in zip files only!
							if (child.isFile() && child.getName().endsWith(".zip")) {
								FileItem fileItem = new FileItem(null, child, false);
								ZIPArchive archive = new ZIPArchive();
								archive.setSource(fileItem);
								String archiveName = getValidArchiveName(archive);
								if (archiveName != null) {
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
	
	private Properties getManifest(ResourceContainer<?> archive) {
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
	
	private X509Certificate getAuthor(ResourceContainer<?> archive) {
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
	private String getValidArchiveName(ResourceContainer<?> archive) {
		// TODO: validate the manifest etc
		Properties manifest = getManifest(archive);
		if (manifest == null) {
			return null;
		}
		String module = manifest.getProperty("module");
		if (module == null || !module.matches("^[a-z-]+$")) {
			System.err.println("Invalid module name: " + module);
			return null;
		}
		X509Certificate author = getAuthor(archive);
		if (author == null) {
			return null;
		}
		KeyStoreHandler packagingKeystore = TritonLocalConsole.getPackagingKeystore();
		if (!isTrusted(new X509Certificate[] { author }, packagingKeystore)) {
			System.err.println("Untrusted author detected: " + TritonLocalConsole.getAlias(author));
			return null;
		}
		// trusting the author is a good first step, but anyone can slap in the author cert into an otherwise custom made zip
		if (!isValid(archive, null, manifest, author)) {
			return null;
		}
		// the name is the combination of the author and the module
		return TritonLocalConsole.getAlias(author) + "::" + module;
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
	
	private boolean isValid(ResourceContainer<?> root, String path, Properties manifest, X509Certificate author) {
		for (Resource resource : root) {
			String childPath = path == null ? resource.getName() : path + "/" + resource.getName();
			// only files have to be signed, not directories
			if (resource instanceof ReadableResource) {
				try {
					String signature = manifest.getProperty("signature-" + childPath);
					if (signature == null) {
						return false;
					}
					byte[] decoded = decode(signature);
					ReadableContainer<ByteBuffer> readable = ((ReadableResource) resource).getReadable();
					try {
						if (!SecurityUtils.verify(IOUtils.toInputStream(readable), decoded, author.getPublicKey(), SignatureType.SHA512WITHRSA)) {
							System.err.println("Invalid signature for file: " + path);
							return false;
						}
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
			// recurse
			if (resource instanceof ResourceContainer) {
				if (!isValid((ResourceContainer<?>) resource, childPath, manifest, author)) {
					return false;
				}
			}
		}
		return true;
	}
	
	// TODO: validate that this works with correct chains etc, currently it has only been tested with self signed single ones
	public static boolean isTrusted(X509Certificate [] chain, KeyStoreHandler keystore) {
		try {
			// if we trust the certificate itself, we good
			// otherwise we check the chain
			if (keystore.getCertificates().values().contains(chain[0])) {
				return true;
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
}
