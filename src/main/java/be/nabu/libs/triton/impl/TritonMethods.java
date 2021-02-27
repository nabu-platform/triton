package be.nabu.libs.triton.impl;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import be.nabu.glue.annotations.GlueParam;
import be.nabu.glue.core.impl.methods.FileMethods;
import be.nabu.libs.evaluator.annotations.MethodProviderClass;
import be.nabu.libs.resources.ResourceUtils;
import be.nabu.libs.resources.api.ReadableResource;
import be.nabu.libs.resources.api.Resource;
import be.nabu.libs.resources.api.ResourceContainer;
import be.nabu.libs.resources.memory.MemoryDirectory;
import be.nabu.libs.resources.memory.MemoryItem;
import be.nabu.libs.triton.Triton;
import be.nabu.libs.triton.TritonLocalConsole;
import be.nabu.libs.triton.TritonLocalConsole.TritonConsoleInstance;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.ReadableContainer;
import be.nabu.utils.io.api.WritableContainer;
import be.nabu.utils.security.KeyStoreHandler;
import be.nabu.utils.security.SecurityUtils;
import be.nabu.utils.security.SignatureType;

// a "package" can be anything?
// for example it could be scripts (most likely) but also like...nabu repository entries?
// we don't want triton to have to know everything so two options:
// we do actual file layout, perhaps relative to the triton installation, you can't say "/home/ubuntu/nabu" but suppose triton is in "/home/ubuntu/triton", you could take the parent folder
// alternatively (possibly better), you register "aliases", which we can do immediately with the VFS alias
// the alias can then point to something, for example "nabu-repository:/nabu/cms/core.nar" and "nabu-software:/integrator/..."
// would be nice if we can just "install" the nabu server much like we would install new scripts
// could have a default location for a given alias? e.g. "nabu-software" (if not found) defaults to "~/software" or something like that.
// maybe the alias _is_ the default?
// ideally the defaults are backwards compatible with the current setup

// can a module be a combination of other modules? perhaps through dependencies?
// e.g. it would be nice to be able to do: triton.install("nabu") and later on triton.update("nabu-repository") or even triton.update("nabu-cms-core")
// that would mean the repository is the combination of the packages and the nabu generic is the combination of the repository and the integrator software

// when serving up a dynamic resource like the nabu server, make a reusable snapshot? meaning you can install that exact version somewhere else?
// it is important for auto-scaling to get the exact same setup, rather than more or less the same (e.g. cutting edge nabu)

// every package is a zip, it can contain (optionally) a "manifest" which for example references other modules (?)
// perhaps in the manifest (like deb) you can run pre- and post-installation scripts?

// TODO: need input() command that works over telnet
// 1) password-masked input probably won't work as this requires cooperation from the terminal side
// 2) the terminal wrapper does not read anything from the server unless it just wrote something
// 		additionally it uses the $ thingy which would get in the way...
// 		at the server end, it might also get tricky? the input from the client is currently always routed towards the glue parser
//		in the case of the input() command however, it has to be captured into a variable?

@MethodProviderClass(namespace = "triton")
public class TritonMethods {

	private Triton triton;

	public TritonMethods(Triton triton) {
		this.triton = triton;
	}
	
	public List<TritonConsoleInstance> connected() {
		return triton.getConsole() == null ? null : triton.getConsole().getInstances();
	}
	
	public void disconnect(long id) throws Exception {
		if (triton.getConsole() != null) {
			for (TritonConsoleInstance instance : triton.getConsole().getInstances()) {
				if (instance.getId() == id) {
					instance.close();
				}
			}
		}
	}
	
	// List installed packages
	public List<PackageDescription> installed() {
		return triton.getInstalled();
	}
	
	// List available packages (can add a query parameter)
	public void available(String query) {
		
	}
	
	// Install a package (update the manifest to reflect this)
	// The version is optional, if left empty, the latest will be used, the manifest will contain a fixed version for stable reproduction
	public void install(Object zipContent, Boolean force) throws IOException {
		InputStream input;
		if (zipContent instanceof String) {
			input = FileMethods.read((String) zipContent);
			if (input == null) {
				throw new FileNotFoundException("Can not resolve the content of: " + zipContent);
			}
		}
		else if (zipContent instanceof byte[]) {
			input = new ByteArrayInputStream((byte []) zipContent);
		}
		else if (zipContent instanceof InputStream) {
			input = (InputStream) zipContent;
		}
		else {
			throw new IllegalArgumentException("Can not figure out the type of content");
		}
		try {
			MemoryItem memoryItem = new MemoryItem(null);
			WritableContainer<ByteBuffer> writable = memoryItem.getWritable();
			writable.write(IOUtils.wrap(IOUtils.toBytes(IOUtils.wrap(input)), true));
			writable.close();
			triton.install(memoryItem, force != null && force);
		}
		finally {
			input.close();
		}
	}
	
	// sign a particular package with a particular profile (default profile if left empty)
	// you can create the package using classic "zip" methods
	public byte[] sign(Object zipContent, String module, String version, String profile, String profilePassword) throws IOException, UnrecoverableKeyException, KeyStoreException, NoSuchAlgorithmException, CertificateEncodingException, InvalidKeyException, SignatureException {
		if (module == null) {
			throw new IllegalArgumentException("Must provide a module name");
		}
		InputStream input;
		if (zipContent instanceof String) {
			input = be.nabu.glue.core.impl.methods.FileMethods.read((String) zipContent);
			if (input == null) {
				throw new FileNotFoundException("Can not resolve the content of: " + zipContent);
			}
		}
		else if (zipContent instanceof byte[]) {
			input = new ByteArrayInputStream((byte []) zipContent);
		}
		else if (zipContent instanceof InputStream) {
			input = (InputStream) zipContent;
		}
		else {
			throw new IllegalArgumentException("Can not figure out the type of content");
		}
		try {
			MemoryDirectory memoryDirectory = new MemoryDirectory();
			ResourceUtils.unzip(new ZipInputStream(input), memoryDirectory);
			Properties manifest = new Properties();
			manifest.setProperty("module", module);
			manifest.setProperty("version", version == null ? "1.0.0" : version);
			if (profile == null) {
				profile = TritonLocalConsole.getProfile();
			}
			String keyPassword = profilePassword == null ? "triton-password" : profilePassword;
			// force generation of key if relevant
			TritonLocalConsole.getContext(profile, keyPassword, false);
			KeyStoreHandler packagingKeystore = TritonLocalConsole.getPackagingKeystore();
			Certificate[] certificateChain = packagingKeystore.getKeyStore().getCertificateChain(profile);
			PrivateKey privateKey = packagingKeystore.getPrivateKey(profile, keyPassword);
			sign(manifest, privateKey, null, memoryDirectory);
			
			// we write the manifest
			MemoryItem create = (MemoryItem) memoryDirectory.create("manifest.tr", "text/plain");
			WritableContainer<ByteBuffer> writable = create.getWritable();
			try {
				manifest.store(IOUtils.toOutputStream(writable), null);
			}
			finally {
				writable.close();
			}
			
			// and the author
			StringWriter writer = new StringWriter();
			SecurityUtils.encodeCertificate((X509Certificate) certificateChain[0], writer);
			create = (MemoryItem) memoryDirectory.create("author.crt", "text/plain");
			writable = create.getWritable();
			try {
				IOUtils.toOutputStream(writable).write(writer.toString().getBytes("ASCII"));
			}
			finally {
				writable.close();
			}
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ZipOutputStream zip = new ZipOutputStream(output);
			ResourceUtils.zip(memoryDirectory, zip, false);
			zip.close();
			return output.toByteArray();
		}
		finally {
			input.close();
		}
	}
	
	private void sign(Properties manifest, PrivateKey key, String path, ResourceContainer<?> root) throws InvalidKeyException, NoSuchAlgorithmException, SignatureException, IOException {
		for (Resource child : root) {
			String childPath = path == null ? child.getName() : path + "/" + child.getName();
			if (child instanceof ResourceContainer) {
				sign(manifest, key, childPath, (ResourceContainer<?>) child);
			}
			if (child instanceof ReadableResource) {
				ReadableContainer<ByteBuffer> readable = ((ReadableResource) child).getReadable();
				try {
					Signature sign = SecurityUtils.sign(IOUtils.toInputStream(readable), key, SignatureType.SHA512WITHRSA);
					byte[] signature = sign.sign();
					String encoded = Triton.encode(signature);
					manifest.setProperty("signature-" + childPath, encoded);
				}
				finally {
					readable.close();
				}
			}
		}
	}
	
	// Uninstall a package (update the manifest)
	public void uninstall(PackageDescription...description) {
		if (description != null) {
			for (PackageDescription single : description) {
				triton.uninstall(single);
			}
		}
	}
	
	// Check for available updates to the installed artifacts
	public void updates() {
		
	}
	
	// Update a specific list of artifacts or (if empty) every artifact
	public void update(String...artifactId) {
		
	}
	
	// get allowed users (by specific cert)
	public List<String> users() throws KeyStoreException {
		KeyStoreHandler keystore = TritonLocalConsole.getAuthenticationKeystore();
		Map<String, X509Certificate> certificates = keystore.getCertificates();
		List<String> aliases = new ArrayList<String>();
		for (String key : certificates.keySet()) {
			if (key.startsWith("user-")) {
				aliases.add(key.substring("user-".length()));
			}
		}
		return aliases;
	}
	
	// get allowed authors (by specific cert)
	public List<String> authors() throws KeyStoreException {
		KeyStoreHandler keystore = TritonLocalConsole.getPackagingKeystore();
		Map<String, X509Certificate> certificates = keystore.getCertificates();
		List<String> aliases = new ArrayList<String>();
		for (String key : certificates.keySet()) {
			if (key.startsWith("user-")) {
				aliases.add(key.substring("user-".length()));
			}
		}
		return aliases;
	}
	
	public void addUser(String cert) throws KeyStoreException, CertificateException, IOException {
		KeyStoreHandler keystore = TritonLocalConsole.getAuthenticationKeystore();
		X509Certificate parseCertificate;
		List<String> pendingAuthentication = pendingAuthentication();
		File file = null;
		// if it's a valid untrusted filename, we use that
		if (pendingAuthentication.contains(cert) && cert.matches("^[\\w]+\\.crt$")) {
			file = new File(new File(Triton.getFolder(), "untrusted"), cert);
			if (file.exists()) {
				try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
					parseCertificate = SecurityUtils.parseCertificate(input);
				}
			}
			else {
				throw new FileNotFoundException("Could not find: " + cert);
			}
		}
		else {
			parseCertificate = SecurityUtils.parseCertificate(new ByteArrayInputStream(cert.getBytes("ASCII")));
		}
		if (!keystore.getCertificates().values().contains(parseCertificate)) {
			keystore.set("user-" + TritonLocalConsole.getAlias(parseCertificate), parseCertificate);
			TritonLocalConsole.saveAuthentication(keystore);
			// restart thread so the new cert is valid
			triton.getConsole().restartSecureThread();
		}
		else {
			System.out.println("Already trusted");
		}
		if (file != null) {
			file.delete();
		}
	}
	
	public void removeUser(String alias) throws KeyStoreException {
		List<String> pendingAuthentication = pendingAuthentication();
		if (pendingAuthentication.contains(alias) && alias.matches("^[\\w]+\\.crt$")) {
			File file = new File(new File(Triton.getFolder(), "untrusted"), alias);
			if (file.exists()) {
				file.delete();
			}
		}
		else {
			KeyStoreHandler keystore = TritonLocalConsole.getAuthenticationKeystore();
			keystore.delete("user-" + alias);
			TritonLocalConsole.saveAuthentication(keystore);
			// restart thread so the new cert is valid
			triton.getConsole().restartSecureThread();
		}
	}

	public void addAuthor(String cert) throws KeyStoreException, CertificateException, UnsupportedEncodingException {
		KeyStoreHandler keystore = TritonLocalConsole.getPackagingKeystore();
		X509Certificate parseCertificate = SecurityUtils.parseCertificate(new ByteArrayInputStream(cert.getBytes("ASCII")));
		if (!keystore.getCertificates().values().contains(parseCertificate)) {
			keystore.set("user-" + TritonLocalConsole.getAlias(parseCertificate), parseCertificate);
			TritonLocalConsole.savePackaging(keystore);
		}
		else {
			System.out.println("Already trusted");
		}
	}
	
	// list all the packages by a specific author
	public List<PackageDescription> authored(String alias) throws KeyStoreException {
		KeyStoreHandler keystore = TritonLocalConsole.getPackagingKeystore();
		List<PackageDescription> result = new ArrayList<PackageDescription>();
		X509Certificate certificate = keystore.getCertificate("user-" + alias);
		if (certificate != null) {
			for (PackageDescription description : triton.getPackages().keySet()) {
				if (certificate.equals(description.getCertificate())) {
					result.add(description);
				}
			}
		}
		return result;
	}
	
	public void removeAuthor(String alias, Boolean force) throws KeyStoreException, IOException {
		KeyStoreHandler keystore = TritonLocalConsole.getPackagingKeystore();
		X509Certificate certificate = keystore.getCertificate("user-" + alias);
		// you can only remove an author if you have uninstalled all his packages
		if (certificate != null) {
			List<PackageDescription> authored = authored(alias);
			if (!authored.isEmpty()) {
				TritonConsoleInstance console = TritonLocalConsole.getConsole();
				if (console != null && console.getInputProvider() != null) {
					String result = console.getInputProvider().input("Do you want to remove all " + authored.size() + " packages attributed to this author? [y/N]: ", false, "y");
					if (result != null && result.equalsIgnoreCase("y")) {
						uninstall(authored.toArray(new PackageDescription[0]));
					}
					// you specifically chose not to proceed
					else {
						return;
					}
				}
				// if we force it (for unsupervised), remove it
				else if (force != null && force) {
					uninstall(authored.toArray(new PackageDescription[0]));
				}
				else {
					throw new IllegalStateException("Please uninstall all the packages authored by '" + alias + "' before removing the author");
				}
			}
			keystore.delete("user-" + alias);
			TritonLocalConsole.savePackaging(keystore);
		}
	}
	
	// this is basically an extension of input
	// it asks the user for input, once, then stores it as configuration and the next time it will not prompt the user but instead feed the configured value
	// if we have a default value, the user must accept it, but it will be stored from that point on
	// we don't want to bother the user with an interaction at every turn
	public Object environment(@GlueParam(name = "key") Object object, @GlueParam(name = "default") String defaultValue, @GlueParam(name = "force") Boolean force, @GlueParam(name = "secret") Boolean secret) throws IOException {
		Properties configuration = Triton.getEnvironment();
		if (object == null) {
			return configuration;
		}
		else if (object instanceof Properties) {
			Triton.setEnvironment((Properties) object);
			return object;
		}
		else {
			String key = object.toString();
			if (configuration.getProperty(key) == null || (force != null && force)) {
				TritonConsoleInstance console = TritonLocalConsole.getConsole();
				if (console != null && console.getInputProvider() != null) {
					String result = console.getInputProvider().input("Initialize environment configuration '" + key + "'" + (defaultValue == null ? "" : " [" + defaultValue + "]") + ": ", secret != null && secret, defaultValue);
					if (result == null || result.trim().isEmpty()) {
						result = defaultValue;
					}
					if (result != null && !result.trim().isEmpty()) {
						configuration.setProperty(key, result.trim());
						Triton.setEnvironment(configuration);
						return result.trim();
					}
				}
			}
			return configuration.getProperty(key);
		}
	}
	
	public List<String> pendingAuthentication() {
		List<String> result = new ArrayList<String>();
		File file = new File(Triton.getFolder(), "untrusted");
		if (file.exists()) {
			result.addAll(Arrays.asList(file.list()));
		}
		return result;
	}
}
