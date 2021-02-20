package be.nabu.libs.triton.impl;

import java.io.ByteArrayInputStream;
import java.io.UnsupportedEncodingException;
import java.security.KeyStoreException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import be.nabu.libs.evaluator.annotations.MethodProviderClass;
import be.nabu.libs.triton.Triton;
import be.nabu.libs.triton.TritonLocalConsole;
import be.nabu.libs.triton.TritonLocalConsole.TritonConsoleInstance;
import be.nabu.utils.security.KeyStoreHandler;
import be.nabu.utils.security.SecurityUtils;

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
	public void installed() {
		
	}
	
	// List available packages (can add a query parameter)
	public void available(String query) {
		
	}
	
	// Install a package (update the manifest to reflect this)
	// The version is optional, if left empty, the latest will be used, the manifest will contain a fixed version for stable reproduction
	public void install(String artifactId, String version) {
		
	}
	
	// Uninstall a package (update the manifest)
	public void uninstall(String artifactId) {
		
	}
	
	// Check for available updates to the installed artifacts
	public void updates() {
		
	}
	
	// Update a specific list of artifacts or (if empty) every artifact
	public void update(String...artifactId) {
		
	}
	
	public List<String> allowed() throws KeyStoreException {
		KeyStoreHandler keystore = TritonLocalConsole.getKeystore();
		Map<String, X509Certificate> certificates = keystore.getCertificates();
		List<String> aliases = new ArrayList<String>();
		for (String key : certificates.keySet()) {
			if (key.startsWith("user-")) {
				aliases.add(key.substring("user-".length()));
			}
		}
		return aliases;
	}
	
	public void allow(String cert) throws KeyStoreException, CertificateException, UnsupportedEncodingException {
		KeyStoreHandler keystore = TritonLocalConsole.getKeystore();
		X509Certificate parseCertificate = SecurityUtils.parseCertificate(new ByteArrayInputStream(cert.getBytes("ASCII")));
		if (!keystore.getCertificates().values().contains(parseCertificate)) {
			keystore.set("user-" + TritonLocalConsole.getAlias(parseCertificate), parseCertificate);
			TritonLocalConsole.save(keystore);
			// restart thread so the new cert is valid
			triton.getConsole().restartSecureThread();
		}
		else {
			System.out.println("Already trusted");
		}
	}
	
	public void disallow(String alias) throws KeyStoreException {
		KeyStoreHandler keystore = TritonLocalConsole.getKeystore();
		keystore.delete("user-" + alias);
		TritonLocalConsole.save(keystore);
		// restart thread so the new cert is valid
		triton.getConsole().restartSecureThread();
	}

}
