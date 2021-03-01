package be.nabu.libs.triton;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.glue.api.InputProvider;
import be.nabu.glue.api.MethodDescription;
import be.nabu.glue.api.ParameterDescription;
import be.nabu.glue.api.StreamProvider;
import be.nabu.glue.core.api.MethodProvider;
import be.nabu.glue.core.impl.executors.EvaluateExecutor;
import be.nabu.glue.core.impl.parsers.GlueParserProvider;
import be.nabu.glue.core.impl.providers.SystemMethodProvider;
import be.nabu.glue.impl.SimpleExecutionEnvironment;
import be.nabu.glue.impl.StandardInputProvider;
import be.nabu.glue.impl.formatters.SimpleOutputFormatter;
import be.nabu.glue.utils.DynamicScript;
import be.nabu.glue.utils.ScriptRuntime;
import be.nabu.glue.utils.VirtualScript;
import be.nabu.libs.authentication.api.Token;
import be.nabu.libs.authentication.impl.BasicPrincipalImpl;
import be.nabu.libs.triton.api.ConsoleSource;
import be.nabu.libs.triton.impl.ConsoleSocketSource;
import be.nabu.utils.io.blocking.DeblockingInputStream;
import be.nabu.utils.io.blocking.LoggingInputStream;
import be.nabu.utils.security.AliasKeyManager;
import be.nabu.utils.security.BCSecurityUtils;
import be.nabu.utils.security.KeyPairType;
import be.nabu.utils.security.KeyStoreHandler;
import be.nabu.utils.security.RememberingTrustManager;
import be.nabu.utils.security.SSLContextType;
import be.nabu.utils.security.SecurityUtils;
import be.nabu.utils.security.StoreType;
import be.nabu.utils.security.api.UntrustedHandler;

public class TritonLocalConsole {

	private static Logger logger = LoggerFactory.getLogger(TritonLocalConsole.class);
	private TritonGlueEngine engine;
	private Integer unsecurePort, securePort;
	private Charset charset = Charset.forName("UTF-8");
	private boolean running;
	private int maxConcurrentConsoles;
	private ExecutorService threadPool;
	private long consoleInstanceId;
	private boolean clientAuth = true;
	
	// defaults to 1 hour
	private static long timeout = Long.parseLong(System.getProperty("triton.timeout", "3600000"));
	
	private SSLServerSocket sslSocket;
	
	private List<TritonConsoleInstance> instances = new ArrayList<TritonConsoleInstance>();
	private Thread secureThread;
	
	public class TritonConsoleInstance implements AutoCloseable {
		private Token token;
		private ConsoleSource source;
		private ScriptRuntime rootRuntime;
		private long id;
		private Date connected = new Date();
		private InputProvider inputProvider;
		private String responseEnd, inputEnd, passwordEnd, fileEditEnd;
		
		TritonConsoleInstance(ConsoleSource source, ScriptRuntime rootRuntime) {
			this.source = source;
			this.rootRuntime = rootRuntime;
			this.id = consoleInstanceId++;
		}
		@Override
		public void close() throws Exception {
			rootRuntime.abort();
			source.close();
		}
		@Override
		public String toString() {
			return "#" + id + "-" + (token == null ? "anonymous" : token.getName()) + "-" + connected;
		}
		public long getId() {
			return id;
		}
		public Token getToken() {
			return token;
		}
		public void setToken(Token token) {
			this.token = token;
		}
		public ConsoleSource getSource() {
			return source;
		}
		public InputProvider getInputProvider() {
			return inputProvider;
		}
		public void setInputProvider(InputProvider inputProvider) {
			this.inputProvider = inputProvider;
		}
		public boolean isSupportsFileEditing() {
			return fileEditEnd != null;
		}
		public String getResponseEnd() {
			return responseEnd;
		}
		public void setResponseEnd(String responseEnd) {
			this.responseEnd = responseEnd;
		}
		public String getInputEnd() {
			return inputEnd;
		}
		public void setInputEnd(String inputEnd) {
			this.inputEnd = inputEnd;
		}
		public String getPasswordEnd() {
			return passwordEnd;
		}
		public void setPasswordEnd(String passwordEnd) {
			this.passwordEnd = passwordEnd;
		}
		public String getFileEditEnd() {
			return fileEditEnd;
		}
		public void setFileEditEnd(String fileEditEnd) {
			this.fileEditEnd = fileEditEnd;
		}
	}
	
	public TritonLocalConsole(Integer unsecurePort, Integer securePort, TritonGlueEngine engine, int maxConcurrentConsoles) {
		this.unsecurePort = unsecurePort;
		this.securePort = securePort;
		this.engine = engine;
		threadPool = Executors.newFixedThreadPool(maxConcurrentConsoles, new ThreadFactory() {
			@Override
			public Thread newThread(Runnable r) {
				Thread newThread = Executors.defaultThreadFactory().newThread(r);
				newThread.setName("triton-console-instance");
				return newThread;
			}
		});
	}
	
	public static boolean isLocal(InetAddress address) {
		if (address.isAnyLocalAddress() || address.isLoopbackAddress()) {
			return true;
		}
		// check if the address is defined on any interface
		try {
			return NetworkInterface.getByInetAddress(address) != null;
		} 
		catch (SocketException e) {
			return false;
	    }
	}
	
	public void start() {
		runWarmup();
		
		running = true;
		Thread unsecureThread = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					try (ServerSocket socket = new ServerSocket(unsecurePort)) {
						while (running) {
							Socket accept = socket.accept();
							// because we don't require authentication, it _must_ come from a local address to ensure you have access
							// in the future we can expand upon this with some authentication scheme
							if (!isLocal(accept.getInetAddress())) {
								accept.close();
							}
							else {
								start(new ConsoleSocketSource(accept, charset));
							}
						}
					}
				}
				catch (Exception e) {
					logger.error("Triton console server stopped", e);
				}
			}
		});
//		thread.setDaemon(true);
		unsecureThread.setName("triton-cli-unsecure");
		if (unsecurePort != null) {
			unsecureThread.start();
		}
		
		startSecureThread();
		
		startTimeoutChecker();
	}
	
	private void startTimeoutChecker() {
		Thread thread = new Thread(new Runnable() {
			@Override
			public void run() {
				List<TritonConsoleInstance> connections = getInstances();
				if (!connections.isEmpty()) {
					Date date = new Date();
					for (TritonConsoleInstance source : connections) {
						if (source.getSource().getLastRead().getTime() < date.getTime() - timeout) {
							logger.info("Disconnecting inactive host #" + source.getId());
							try {
								source.close();
							}
							catch (Exception e) {
								e.printStackTrace();
							}
						}
					}
				}
			}
		});
		thread.setDaemon(true);
		thread.setName("triton-timeout-checker");
		thread.start();
	}

	public void restartSecureThread() {
		startSecureThread();
	}
	
	private void startSecureThread() {
		if (sslSocket != null) {
			try {
				sslSocket.close();
			}
			catch (IOException e) {
				// ignore
			}
		}
		if (secureThread != null) {
			try {
				secureThread.join();
			}
			catch (Exception e) {
				e.printStackTrace();
				// ignore
			}
			secureThread = null;
		}
		if (securePort != null) {
			secureThread = new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						sslSocket = (SSLServerSocket) getContext().getServerSocketFactory().createServerSocket(securePort);
						sslSocket.setNeedClientAuth(clientAuth);
						while (running && !sslSocket.isClosed()) {
							SSLSocket accept = (SSLSocket) sslSocket.accept();
							// because we don't require authentication, it _must_ come from a local address to ensure you have access
							// in the future we can expand upon this with some authentication scheme
							if (!clientAuth && !isLocal(accept.getInetAddress())) {
								accept.close();
							}
							else {
								ConsoleSocketSource source = new ConsoleSocketSource(accept, charset);
								// if we want client auth and don't get one, we don't even start this up
								if (!clientAuth || source.getCertificate() != null) {
									start(source);
								}
								else {
									accept.close();
								}
							}
						}
					}
					catch (Exception e) {
						logger.error("Triton console server stopped: " + e.getMessage());
					}
					finally {
						try {
							sslSocket.close();
						}
						catch (Exception e) {
							// ignore
						}
					}
				}
			});
	//		thread.setDaemon(true);
			secureThread.setName("triton-cli-secure");
			secureThread.start();
		}
	}
	
	public static String getProfile() {
		return System.getProperty("triton.profile", System.getProperty("profile", "triton-" + (Main.SERVER_MODE ? "server" : "client")));
	}
	
	public static String getName() {
		try {
			return Triton.getSetting("name", InetAddress.getLocalHost().getHostName());
		}
		catch (UnknownHostException e) {
			return "anonymous";
		}
	}
	
	public static String getGroup() {
		return Triton.getSetting("group", getName());
	}
	
	public static String getOrganisation() {
		return Triton.getSetting("organisation", "Celerium");
	}
	
	public static String getOrganisationalUnit() {
		return Triton.getSetting("organisationalUnit", "Nabu");
	}
	
	public static String getLocality() {
		return Triton.getSetting("locality", "Antwerp");
	}
	
	public static String getState() {
		return Triton.getSetting("state", "Antwerp");
	}
	
	public static String getCountry() {
		return Triton.getSetting("country", "Belgium");
	}
	
	private static String rememberedKeyPassword;
	
	public static String getKeyPassword(String defaultPassword) {
		// don't want to keep this in system properties, it could be "stolen" then
		String key = rememberedKeyPassword;
		if (key == null) {
			key = System.getProperty("triton.keyPassword", defaultPassword);
		}
		if (key == null) {
			try {
				key = new StandardInputProvider().input("Enter key password for profile '" + getProfile() + "' [use default]: ", true, "triton-password");
				rememberedKeyPassword = key;
			}
			catch (IOException e) {
				throw new RuntimeException("Can not recover key password", e);
			}
		}
		return key;
	}
	
	public static String getKeystorePassword() {
		return System.getProperty("triton.storePassword", "triton-keystore");
	}
	
	public static SSLContext getContext() {
		// in server mode, we don't force a password
		String defaultKeyPassword = Main.SERVER_MODE ? "triton-password" : null;
		// it's too annoying to force clients to use a password every time?
		String keyPassword = getKeyPassword(defaultKeyPassword);
		return getContext(getProfile(), keyPassword, true);
	}
	
	public static SSLContext getContext(String profile, String keyPassword, boolean authentication) {
		try {
			KeyStoreHandler keystore = authentication ? getAuthenticationKeystore() : getPackagingKeystore();
			PrivateKey privateKey = keystore.getPrivateKey(profile, keyPassword);
			// if we don't have a key yet, generate a self signed set
			if (privateKey == null) {
				KeyPair pair = SecurityUtils.generateKeyPair(KeyPairType.RSA, 4096);
				X500Principal principal = SecurityUtils.createX500Principal(getName(), getOrganisation(), getOrganisationalUnit(), getLocality(), getState(), getCountry());
				X509Certificate certificate = BCSecurityUtils.generateSelfSignedCertificate(pair, new Date(new Date().getTime() + (1000l * 60 * 60 * 24 * 365 * 100)), principal, principal);
				keystore.set(profile, pair.getPrivate(), new X509Certificate[] { certificate }, keyPassword);
				if (authentication) {
					saveAuthentication(keystore);
				}
				else {
					savePackaging(keystore);
				}
				privateKey = pair.getPrivate();
			}
			
			// because passwords are (likely) different cross keys and it does not seem to be possible to indicate the correct key when creating the key managers...
			// we just create a new store that only has one key
			KeyStoreHandler singleKey = KeyStoreHandler.create("test", StoreType.JKS);
			singleKey.set(profile, privateKey, keystore.getPrivateKeys().get(profile), keyPassword);
			
			KeyManager[] keyManagers = singleKey.getKeyManagers(keyPassword);
			for (int i = 0; i < keyManagers.length; i++) {
				if (keyManagers[i] instanceof X509KeyManager) {
					keyManagers[i] = new AliasKeyManager((X509KeyManager) keyManagers[i], profile);
				}
			}
			SSLContext context = SSLContext.getInstance(SSLContextType.TLS.toString());
			TrustManager[] trustManagers = keystore.getTrustManagers();
			for (int i = 0; i < trustManagers.length; i++) {
				if (trustManagers[i] instanceof X509TrustManager) {
					trustManagers[i] = new RememberingTrustManager((X509TrustManager) trustManagers[i], new UntrustedHandler() {
						@Override
						public void handle(X509Certificate[] chain, boolean client, String authType) {
							if (client) {
								boolean store = Boolean.parseBoolean(Triton.getSetting("store.untrusted", "true"));
								if (store) {
									File folder = new File(Triton.getFolder(), "untrusted");
									String fileName = chain[0].getSubjectX500Principal().toString().replaceAll("[^\\w]+", "_");
									if (!folder.exists()) {
										folder.mkdirs();
									}
									File file = new File(folder, fileName + ".crt");
									try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
										OutputStreamWriter outputStreamWriter = new OutputStreamWriter(out, "ASCII");
										SecurityUtils.encodeCertificate(chain[0], outputStreamWriter);
										outputStreamWriter.flush();
									}
									catch (Exception e) {
										e.printStackTrace();
									}
								}
							}
						}
					});
				}
			}
			context.init(keyManagers, trustManagers, new SecureRandom());
			return context;
		}
		catch (UnrecoverableKeyException e) {
			System.out.println("Could not unlock the key, did you provide the correct password?");
			System.exit(0);
			throw new RuntimeException(e);
		}
		catch (Exception e) {
			logger.error("Could not get ssl context", e);
			throw new RuntimeException(e);
		}
	}
	
	// doubtful that this is useful, this assumes the cert itself is installed, rather than for example a parent cert
	public static String getValidatedAlias(X509Certificate certificate) {
		if (certificate != null) {
			try {
				for (Map.Entry<String, X509Certificate> entry : TritonLocalConsole.getAuthenticationKeystore().getCertificates().entrySet()) {
					if (entry.getValue().equals(certificate)) {
						return getAlias(entry.getValue());
					}
				}
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
		return null;
	}

	public static String getAlias(X509Certificate certificate) {
		Map<String, String> parts = SecurityUtils.getParts(certificate.getSubjectX500Principal());
		return parts.get("CN");
	}
	
	public static KeyStoreHandler getAuthenticationKeystore() {
		return getKeystore("authentication");
	}
	
	public static KeyStoreHandler getPackagingKeystore() {
		return getKeystore("packaging");
	}
	
	public static KeyStoreHandler getKeystore(String type) {
		try {
			File store = new File(Triton.getFolder(), type + ".jks");
			String password = getKeystorePassword();
			KeyStoreHandler handler;
			if (!store.exists()) {
				handler = KeyStoreHandler.create(password, StoreType.JKS);
			}
			else {
				try (InputStream input = new BufferedInputStream(new FileInputStream(store))) {
					handler = KeyStoreHandler.load(input, password, StoreType.JKS);
				}
			}
			return handler;
		}
		catch (Exception e) {
			logger.error("Could not get keystore", e);
			throw new RuntimeException(e);
		}
	}
	
	public static void saveAuthentication(KeyStoreHandler keystore) {
		save("authentication", keystore);
	}
	
	public static void savePackaging(KeyStoreHandler keystore) {
		save("packaging", keystore);
	}
	
	public static void save(String name, KeyStoreHandler keystore) {
		try {
			File store = new File(Triton.getFolder(), name + ".jks");
			try (OutputStream output = new BufferedOutputStream(new FileOutputStream(store))) {
				keystore.save(output, getKeystorePassword());
			}
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	private static ThreadLocal<TritonConsoleInstance> console = new ThreadLocal<TritonConsoleInstance>();
	
	public static TritonConsoleInstance getConsole() {
		return console.get();
	}
	
	// some method providers might need some warmup (e.g. integrator)
	private void runWarmup() {
		try {
			System.out.println("Warming up method providers...");
			SimpleExecutionEnvironment environment = new SimpleExecutionEnvironment("default");
			environment.getParameters().put(EvaluateExecutor.DEFAULT_VARIABLE_NAME_PARAMETER, "$tmp");
			
			DynamicScript dynamicScript = new DynamicScript(
				engine.getRepository(), 
				engine.getRepository().getParserProvider().newParser(engine.getRepository(), "dynamic.glue"),
				"console('Warm-up complete')");
			
			ScriptRuntime runtime = new ScriptRuntime(dynamicScript, 
				environment, 
				false, 
				null
			);
			runtime.run();
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	// TODO: we probably need a way to negotiate non-interaction mode
	// you want to be able to run unsupervised management scripts
	public void start(ConsoleSource source) {
		threadPool.submit(new Runnable() {
			// these are deprecated and should be replaced with the values in the instance
			private String responseEnd, inputEnd, passwordEnd, fileEditEnd;
			
			// whether or not this is an interactive session
			private boolean interactive = true;

			@Override
			public void run() {
				console.set(null);
				// if we write the "input", our response does not stop with a linefeed
				// anyone listening to end of line won't pick it up
				ScriptRuntime runtime = null;
				TritonConsoleInstance instance = null;
				try {
					SimpleExecutionEnvironment environment = new SimpleExecutionEnvironment("default");
					environment.getParameters().put(EvaluateExecutor.DEFAULT_VARIABLE_NAME_PARAMETER, "$tmp");
					DynamicScript dynamicScript = new DynamicScript(
						engine.getRepository(), 
						engine.getRepository().getParserProvider().newParser(engine.getRepository(), "dynamic.glue"));

					runtime = new ScriptRuntime(dynamicScript, 
						environment, 
						false, 
						null
					);
					instance = new TritonConsoleInstance(source, runtime);
					console.set(instance);
					logger.info("Triton console #" + instance.getId() + " connected");
					instances.add(instance);
					
					// if you used a client certificate to gain access, we already established your identity
					if (source instanceof ConsoleSocketSource) {
						X509Certificate certificate = ((ConsoleSocketSource) source).getCertificate();
						if (certificate != null) {
							String alias = getAlias(certificate);
							if (alias != null) {
								instance.setToken(new BasicPrincipalImpl(alias, null));
							}
						}
						// if you are local, you get admin powers 
						else if (isLocal(((ConsoleSocketSource) source).getSocket().getInetAddress())) {
							instance.setToken(new BasicPrincipalImpl("admin", null));
						}
					}
					
					runtime.registerInThread();
					
					InputStream main = source.getInputStream();
					
					// TODO: token?
					StringBuilder buffered = new StringBuilder();
					StringBuilder script = new StringBuilder();
					BufferedReader reader = new BufferedReader(new InputStreamReader(main));
					BufferedWriter writer = new BufferedWriter(source.getWriter());
					
					// because this is run synchronously, it shouldn't interfere with regular interaction
					// if you ever request input asynchronously, this will...not work well :|
					InputProvider inputProvider = new InputProvider() {
						@Override
						public String input(String message, boolean secret, String defaultValue) throws IOException {
							if (!interactive) {
								return defaultValue;
							}
							if (message != null) {
								writer.write(message);
								// if we have a specific marker for password input, use that
								if (secret && !passwordEnd.isEmpty()) {
									writer.write(passwordEnd + "\n");
								}
								// if we don't have a password marker but a generic input marker, use that
								else if (!inputEnd.isEmpty()) {
									writer.write(inputEnd + "\n");
								}
								// if we don't have a specific marker for input end, we use the response end marker
								// note that all structured communication uses linefeeds and the response-end specifically is expected to be on a separate line
								// if you use telnet, you set neither input nor response end and you will get inline prompt which is what you would expect
								else if (!responseEnd.isEmpty()) {
									writer.write("\n");
									writer.write(responseEnd + "\n");
								}
								writer.flush();
							}
							String result = reader.readLine();
							if (result == null || result.trim().isEmpty()) {
								result = defaultValue;
							}
							return result;
						}
					};
					instance.setInputProvider(inputProvider);
					
					StreamProvider streamProvider = new StreamProvider() {
						@Override
						public OutputStream getErrorStream() {
							return source.getOutputStream();
						}
						@Override
						public OutputStream getOutputStream() {
							return source.getOutputStream();
						}
						@Override
						public InputStream getInputStream() {
							return ((ConsoleSocketSource) source).getDeblockingInput().newInputStream();
						}
						@Override
						public boolean isBlocking() {
							return true;
						}
					};
					
					responseEnd = "";
					inputEnd = "";
					passwordEnd = "";
					String line;
					while ((line = reader.readLine()) != null) {
						source.setLastRead(new Date());
						if (runtime.isAborted()) {
							break;
						}
						SimpleOutputFormatter simpleOutputFormatter = new SimpleOutputFormatter(writer, true);
						try {
							String trimmed = line.trim();
							if (trimmed.isEmpty()) {
								continue;
							}
							else if (trimmed.startsWith("#")) {
								// want comments to appear in resulting script
								script.append(line + "\n");
								continue;
							}
							// if without trimming, you still typed exit (so no whitespace etc), we stop
							else if (line.equals("exit")) {
								source.close();
								break;
							}
							else if (line.equals("show")) {
								writer.write(script.toString() + "\n");
							}
							else if (line.equals("version")) {
								writer.write(Main.VERSION + "\n");
							}
							else if (line.equals("clear")) {
								buffered.delete(0, buffered.toString().length());
								script.delete(0, script.toString().length());
								runtime.getExecutionContext().getPipeline().clear();
							}
							else if (line.equals("state")) {
								writer.write(runtime.getExecutionContext().getPipeline().toString() + "\n");
							}
							else if (line.startsWith("Negotiate-Response-End:")) {
								responseEnd = line.substring("Negotiate-Response-End:".length()).trim();
								instance.setResponseEnd(responseEnd);
							}
							else if (line.startsWith("Negotiate-File-Edit-End:")) {
								fileEditEnd = line.substring("Negotiate-File-Edit-End:".length()).trim();
								instance.setFileEditEnd(fileEditEnd);
							}
							else if (line.startsWith("Interact-Ping:")) {
								String content = line.substring("Interact-Ping: ".length()).trim();
								writer.write("Interact-Pong: " + content);
							}
							// turn on or off interactive mode
							else if (line.startsWith("Negotiate-Interactive:")) {
								interactive = "true".equals(line.substring("Negotiate-Interactive:".length()).trim());
							}
							else if (line.startsWith("Negotiate-Input-End:")) {
								inputEnd = line.substring("Negotiate-Input-End:".length()).trim();
								instance.setInputEnd(inputEnd);
							}
							else if (line.startsWith("Negotiate-Password-End:")) {
								passwordEnd = line.substring("Negotiate-Password-End:".length()).trim();
								instance.setPasswordEnd(passwordEnd);
							}
							else if (line.startsWith("Fetch-Meta:")) {
								String meta = line.substring("Fetch-Meta:".length()).trim();
								if ("name".equalsIgnoreCase(meta)) {
									writer.write(getName() + "\n");
								}
							}
							else if (line.startsWith("Suggest-Method:")) {
								String soFar = line.substring("Suggest-Method:".length()).trim().toLowerCase();
								StringBuilder builder = new StringBuilder();
								boolean first = true;
								boolean namespaced = soFar.contains(".");
								for (MethodProvider provider : engine.getMethodProviders()) {
									for (MethodDescription description : provider.getAvailableMethods()) {
										boolean matches = false;
										if (namespaced && description.getNamespace() != null && (description.getNamespace() + "." + description.getName()).startsWith(soFar)) {
											matches = true;
										}
										else if (!namespaced && description.getName().startsWith(soFar)) {
											matches = true;
										}
										if (matches) {
											if (first) {
												first = false;
											}
											else {
												builder.append(";");
											}
											String desc = "";
											List<ParameterDescription> parameters = description.getParameters();
											if (parameters != null && !parameters.isEmpty()) {
												for (ParameterDescription parameter : parameters) {
													if (!desc.isEmpty()) {
														desc += ", ";
													}
													desc += parameter.getName();
												}
											}
											if (namespaced) {
												builder.append(description.getNamespace() + "." + description.getName());
											}
											else {
												builder.append(description.getName());
											}
											if (!desc.isEmpty()) {
												builder.append("::" + desc);
											}
										}
									}
								}
								writer.write(builder.toString());
							}
							else if (line.startsWith("Suggest-File:")) {
								String soFar = line.substring("Suggest-File:".length()).trim().toLowerCase();
								StringBuilder builder = new StringBuilder();
								boolean first = true;
								File folder = new File(SystemMethodProvider.getDirectory());
								int lastIndexOf = soFar.lastIndexOf('/');
								String prefix = "";
								if (lastIndexOf > 0) {
									// include trailing /
									prefix = soFar.substring(0, lastIndexOf + 1);
									folder = new File(folder, prefix);
									soFar = soFar.substring(lastIndexOf + 1);
								}
								if (folder.exists()) {
									for (File child : folder.listFiles()) {
										if (soFar.isEmpty() || child.getName().toLowerCase().startsWith(soFar)) {
											if (first) {
												first = false;
											}
											else {
												builder.append(";");
											}
											String description = "";
											if (child.isFile()) {
												if (child.length() > 1024l * 1024 * 1024) {
													description = Math.round((1.0 * child.length()) / (1024l*1024*1024)) + "gb";
												}
												else if (child.length() > 1024l * 1024) {
													description = Math.round((1.0 * child.length()) / (1024l*1024)) + "mb";
												}
												else if (child.length() > 1024) {
													description = Math.round((1.0 * child.length()) / (1024l)) + "kb";
												}
												else {
													description = child.length() + "b";
												}
											}
											builder.append(prefix + child.getName() + (description.isEmpty() ? "" : "::" + description));
										}
									}
								}
								writer.write(builder.toString());
							}
							else if (line.equals("refresh")) {
								engine.refresh();
							}
							// signal for multiline...
							else if (trimmed.endsWith("\\")) {
								buffered.append(line.replaceAll("[\\\\s]+$", "")).append("\n");
							}
							else {
								buffered.append(line);
								VirtualScript virtualScript = new VirtualScript(dynamicScript, buffered.toString());
								ScriptRuntime scriptRuntime = new ScriptRuntime(virtualScript, runtime.getExecutionContext(), null);
								scriptRuntime.setFormatter(simpleOutputFormatter);
								scriptRuntime.setInputProvider(inputProvider);
								scriptRuntime.setStreamProvider(streamProvider);
								scriptRuntime.run();
								script.append(buffered).append("\n");
								buffered.delete(0, buffered.toString().length());
							}
						}
						catch (Exception e) {
							e.printStackTrace(new PrintWriter(writer));
							writer.write("\n");
							writer.flush();
							// always delete buffered, even in case of failure, you can't fix it...
							buffered.delete(0, buffered.toString().length());
						}
						finally {
							if (!source.isClosed()) {
								Map<String, Object> pipeline = runtime.getExecutionContext().getPipeline();
								Object remove = pipeline.remove("$tmp");
								// if we don't have an echo, use the $tmp one
								// calling glue scripts will always return the full pipeline, so combining that with echo is not good :(
								if (!simpleOutputFormatter.isOutputted() && remove != null) {
									writer.write(remove.toString().trim());
									// after the echo we want a line feed
									writer.write("\n");
								}
								if (!responseEnd.isEmpty()) {
									writer.write(responseEnd + "\n");
								}
								// invite more typing
//								writer.write(input);
								writer.flush();
							}
						}
					}
					logger.info("Triton console #" + instance.getId() + " disconnected");
				}
				catch (Throwable e) {
					logger.info("Triton console #" + instance.getId() + " disconnected: " + e.getMessage());
					e.printStackTrace();
				}
				finally {
					console.set(null);
					if (instance != null) {
						instances.remove(instance);
						try {
							instance.close();
						}
						catch (Exception e) {
							e.printStackTrace();
						}
					}
					if (runtime != null) {
						runtime.unregisterInThread();
					}
				}
			}
		});
	}

	public List<TritonConsoleInstance> getInstances() {
		return new ArrayList<TritonConsoleInstance>(instances);
	}

	public int getMaxConcurrentConsoles() {
		return maxConcurrentConsoles;
	}
	
}
