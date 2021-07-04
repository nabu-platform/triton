package be.nabu.libs.triton;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyStoreException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;

import org.jline.builtins.Less;
import org.jline.builtins.Nano;
import org.jline.builtins.Nano.SyntaxHighlighter;
import org.jline.builtins.Source;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.Terminal.Signal;
import org.jline.terminal.Terminal.SignalHandler;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp.Capability;

import be.nabu.glue.impl.StandardInputProvider;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.security.KeyStoreHandler;
import be.nabu.utils.security.SSLContextType;
import be.nabu.utils.security.SecurityUtils;

// the shell is a thin wrapper around the triton console endpoint (that can also be used via telnet)
public class TritonShell {
	
	public static String VERSION = "0.1-beta";
	
	private static String readAnswer(BufferedReader reader, String ending) throws IOException {
		String line;
		StringBuilder builder = new StringBuilder();
		while ((line = reader.readLine()) != null) {
			// the output ending is on a separate line
			if (line.equals(ending)) {
				break;
			}
			else {
				builder.append(line).append("\n");
			}
		}
		if (line == null) {
			System.exit(1);
		}
		return builder.toString();
	}
	
	private static boolean running;
	private static int runningKill;
	
	public static void main(String...args) throws URISyntaxException, IOException {
		Main.systemPropertify(args);
		
		// make sure the user chooses a profile
		chooseProfile();
		
		// and a host
		chooseHost();
		
		// the connection string
		// e.g. ts://localhost:5000, the protocol stands for "triton shell" or "triton socket"
		// secure is for example sts://localhost:5100 -> sts: secure triton shell
		String tritonHost = System.getProperty("triton.host", System.getProperty("host", "ts://localhost"));
		URI url = new URI(tritonHost);
		try {
			Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
				@Override
				public void run() {
					System.out.println("Exiting Triton Shell");
				}
			}));
			
			int plainPort = Integer.parseInt(System.getProperty("triton.local.port", "" + Triton.DEFAULT_PLAIN_PORT));
			int securePort = Integer.parseInt(System.getProperty("triton.secure.port", "" + Triton.DEFAULT_SECURE_PORT));
			
			Socket socket;
			// by default we assume you want secure
			if (url.getScheme() == null || "sts".equals(url.getScheme())) {
				// always generate the ssl context so we have the key
				// we might need the key to install it
				SSLContext context = TritonLocalConsole.getContext();
				// make sure we already trust the target server
				// if you do something like "host=target" without the scheme, the "target" value will be in the path, not in the host
				String host = url.getHost() == null ? url.getPath() : url.getHost();
				int port = url.getPort() < 0 ? securePort : url.getPort();
				X509Certificate[] chain = SecurityUtils.getChain(host, port, SSLContextType.TLS);
				KeyStoreHandler keystore = TritonLocalConsole.getAuthenticationKeystore();
				if (!Triton.isTrusted(chain, keystore)) {
					Map<String, X509Certificate> certificates = keystore.getCertificates();
					StandardInputProvider inputProvider = new StandardInputProvider();
					String result = inputProvider.input("Connecting to unknown server '" + TritonLocalConsole.getAlias(chain[0]) + "' (" + host + "), do you trust this server? [Y/n]: ", false, null);
					if (result != null && result.equalsIgnoreCase("n")) {
						System.exit(0);
					}
					String key = "server-" + host;
					String keyAttempt = key;
					int counter = 1;
					while (certificates.containsKey(keyAttempt)) {
						keyAttempt = key + counter++;
					}
					keystore.set(keyAttempt, chain[0]);
					TritonLocalConsole.saveAuthentication(keystore);
					// renew context with the cert installed
					context = TritonLocalConsole.getContext();
				}
				socket = context.getSocketFactory().createSocket(host, port);
			}
			else if ("ts".equals(url.getScheme())) {
				socket = new Socket(url.getHost() == null ? "localhost" : url.getHost(), url.getPort() < 0 ? plainPort : url.getPort());
			}
			else {
				throw new RuntimeException("Invalid scheme: " + url);
			}
			try {
				// we keep an eye on the socket, if it is remotely closed, we want to know about it
				Thread thread = new Thread(new Runnable() {
					@Override
					public void run() {
						while (socket.isConnected()) {
							try {
								Thread.sleep(1000);
							}
							catch (InterruptedException e) {
								// ignore
							}
						}
						System.exit(1);
					}
				});
				thread.setName("socket-watcher");
				thread.setDaemon(true);
				thread.start();
				
				InputStream inputStream = socket.getInputStream();
				BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
				OutputStream outputStream = socket.getOutputStream();
				OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream, "UTF-8");
				BufferedWriter writer = new BufferedWriter(outputStreamWriter);
				
				Terminal terminal = TerminalBuilder.builder()
						.name("Triton v" + Main.VERSION)
//						.signalHandler(Terminal.SignalHandler.SIG_IGN)
						.signalHandler(new SignalHandler() {
							@Override
							public void handle(Signal arg0) {
								if (running) {
									// don't want to trigger on like resize signals etc
									if (arg0 == Signal.INT) {
										try {
											writer.write("^SIGINT\n");
											writer.flush();
										}
										catch (IOException e) {
											e.printStackTrace();
										}
										runningKill++;
										if (runningKill >= 3) {
											try {
												socket.close();
											}
											catch (IOException e) {
												// ignore
											}
											System.exit(1);
										}
									}
								}
								else {
									Terminal.SignalHandler.SIG_DFL.handle(arg0);
								}
							}
						})
						.nativeSignals(true)
						//			.attributes(termAttribs)
						.build();
				
				// once successfully connected, make sure we save the host for future use in the profile
//				Properties configuration = Triton.getConfiguration();
//				String chosenHost = System.getProperty("triton.host", System.getProperty("host"));
//				String hostKey = "hosts." + TritonLocalConsole.getProfile();
//				String hosts = configuration.getProperty(hostKey);
//				if (hosts == null) {
//					hosts = chosenHost;
//				}
//				// not yet stored
//				else if (!Arrays.asList(hosts.split("[\\s]*,[\\s]*")).contains(chosenHost)) {
//					hosts += "," + chosenHost;
//				}
//				if (!hosts.equals(configuration.getProperty(hostKey))) {
//					configuration.setProperty(hostKey, hosts);
//					Triton.setConfiguration(configuration);
//				}
				
				// regular ending used to denote the end of a stream by the server
				String ending = "//the--end//";
				// used when the server wants to ask the user for typed input
				String inputEnding = "//request--input//";
				// same as above but it should be considered to be sensitive information like a password and likely shielded from view
				String passwordEnding = "//request--password//";
				// we want to edit a particular file
				String fileEditEnding = "//request--file--edit//";
				// we set a requested ending to each response so we can target that in our parsing
				// we do this as the first step so we can parse the next steps correctly
				writer.write("Negotiate-Response-End: " + ending + "\n");
				writer.flush();
				// an answer will always come
				readAnswer(reader, ending);
				writer.write("version\n");
				writer.flush();
				String version = readAnswer(reader, ending);
				
				writer.write("Negotiate-Input-End: " + inputEnding + "\n");
				writer.flush();
				// an answer will always come
				readAnswer(reader, ending);
				
				writer.write("Negotiate-Password-End: " + passwordEnding + "\n");
				writer.flush();
				// an answer will always come
				readAnswer(reader, ending);
				
				writer.write("Negotiate-File-Edit-End: " + fileEditEnding + "\n");
				writer.flush();
				// an answer will always come
				readAnswer(reader, ending);
				
				writer.write("Fetch-Meta: name\n");
				writer.flush();
				// an answer will always come
				String serverName = readAnswer(reader, ending).trim();
				
				terminal.puts(Capability.clear_screen);
                terminal.flush();
                terminal.writer().println("_______________________________________________________________\n");
				terminal.writer().println("* Triton Shell v" + VERSION + " connected to Triton Agent v" + version.trim());
				terminal.writer().println("_______________________________________________________________\n");
				terminal.writer().println("- exit			Disconnect from the triton agent (ctrl+c)");
				terminal.writer().println("- clear			Clear the state");
				// you can also end a regular line with a backslash for the same effect!
				terminal.writer().println("- alt+enter		Create a multiline script");
				terminal.writer().println("- show			Show the script so far");
				terminal.writer().println("- state			Print the current variable state");
				terminal.writer().println("- allow			Add client cert to server to connect securely");
				terminal.writer().println("- self(ie)		Print client cert for installation with or without the addUser wrapper");
				terminal.writer().println("- (un)supervised	Toggle between supervised and unsupervised mode");
				terminal.writer().println("_______________________________________________________________\n");
				
				// we unset the escape characters so we can send them to the backend, otherwise they get stripped
				DefaultParser parser = new DefaultParser();
				parser.setEscapeChars(null);
				
				File history = new File(System.getProperty("user.home"), ".triton_shell_history");
				LineReader consoleReader = LineReaderBuilder.builder()
					.terminal(terminal)
					.parser(parser)
					.completer(new Completer() {
						private boolean waiting;
						@Override
						public void complete(LineReader arg0, ParsedLine arg1, List<Candidate> arg2) {
							if (!waiting && !arg1.word().isEmpty()) {
								synchronized(this) {
									if (!waiting) {
										waiting = true;
										// check amount of string openers
										int count = arg1.line().length() - arg1.line().replace("\"", "").length();
										// if it is an even count, we are not in a string, we want to suggest a method
										// we presumably already typed a bit of the method, so start with that
										try {
											// to fully autocomplete, we need to match the full line
											// so for example if you are typing cat("bu
											// and autocomplete, we can't just send build.xml
											// we need cat("build.xml
											// otherwise it won't complete
											String starter = null;
											String soFar;
											if (count % 2 == 0) {
												soFar = arg1.line().replaceAll("^.*?([\\w.]+$)", "$1");
												writer.write("Suggest-Method: " + soFar + "\n");
												writer.flush();
											}
											// we are in a string, we probably want to suggest a filename
											// presumably we already typed a bit of the filename, so let's start it off with that!
											else {
												int lastIndexOf = arg1.line().lastIndexOf('"');
												soFar = arg1.line().substring(lastIndexOf + 1);
												writer.write("Suggest-File: " + soFar + "\n");
												writer.flush();
											}
											starter = arg1.word().substring(0, arg1.word().length() - soFar.length());
											String readLine = reader.readLine();
											if (!readLine.endsWith(ending)) {
												return;
											}
											for (String single : readLine.substring(0, readLine.length() - ending.length()).split(";")) {
												// allow for a description to be passed along
												String[] split = single.split("::");
												arg2.add(new Candidate(starter + split[0], split[0], null, split.length == 2 ? split[1] : null, null, null, false));
											}
										}
										catch (Exception e) {
											// ignore
										}
										finally {
											waiting = false;
										}
									}
								}
							}
						}
					})
					.highlighter(new Highlighter() {
						@Override
						public AttributedString highlight(LineReader arg0, String arg1) {
							AttributedStringBuilder builder = new AttributedStringBuilder();
							return builder.append(arg1)
								.styleMatches(Pattern.compile("[\\w]+"), AttributedStyle.DEFAULT.bold().foreground(AttributedStyle.WHITE))
								// highlight some operators (not all)
								.styleMatches(Pattern.compile("&|\\|"), AttributedStyle.DEFAULT.bold().foreground(AttributedStyle.YELLOW))
								.styleMatches(Pattern.compile("\\(|\\)"), AttributedStyle.DEFAULT.bold().foreground(AttributedStyle.GREEN).faint())
								.styleMatches(Pattern.compile("[0-9]+|[0-9]+\\.[0-9]+"), AttributedStyle.DEFAULT.bold().foreground(AttributedStyle.GREEN))
								.styleMatches(Pattern.compile("[\\w]+(?=\\()"), AttributedStyle.DEFAULT.bold().foreground(AttributedStyle.MAGENTA).faint())
								// we do string last so everything inside a string is correctly styled
								.styleMatches(Pattern.compile("\"[^\"]*(\"|$)"), AttributedStyle.DEFAULT.bold().foreground(AttributedStyle.BLUE).faint())
								.toAttributedString();
						}
						@Override
						public void setErrorIndex(int arg0) {
							
						}
						@Override
						public void setErrorPattern(Pattern arg0) {
							
						}
					})
					.variable(LineReader.HISTORY_FILE, history.toPath())
					.variable(LineReader.SECONDARY_PROMPT_PATTERN, colored("%P -> "))
					.variable(LineReader.BLINK_MATCHING_PAREN, 0)
//					.variable(LineReader.DISABLE_COMPLETION, true)
					.build();
				
				// we want to be able to insert bleedin' tabs
				consoleReader.setOpt(LineReader.Option.INSERT_TAB);
//				consoleReader.unsetOpt(LineReader.Option.AUTO_MENU);
//				consoleReader.unsetOpt(LineReader.Option.AUTO_MENU_LIST);
				
				// all the aliases
				Map<String, String> aliases = loadAliases();
				
				String line;
				// the string we pass along is the "prompt" string
				// the prompt is actually controlled by the remote server
				// by using a space as a prompt, it seems to work with the prompt provided by the server
				// it doesn't seem to work (unfortunately) with an empty string
				// the space does tend to remain in unwanted places if content comes back from the server though...
				// we could get rid of the space but then we need a proper prompt here, which will interfere with the prompt via telnet so we would have to cripple the straight-to-telnet shizzle
				while ((line = consoleReader.readLine(serverName + "$ ")) != null) {
					if (line.equals("self")) {
						String certWriter = encodeCert();
						terminal.writer().println(certWriter);
						terminal.writer().flush();
						continue;
					}
					else if (line.equals("selfie")) {
						String certWriter = encodeCertWithWrapper();
						terminal.writer().println(certWriter);
						terminal.writer().flush();
						continue;
					}
					if (line.equals("unsupervised")) {
						writer.write("Negotiate-Interactive: false\n");
						writer.flush();
						readAnswer(reader, ending);
						continue;
					}
					if (line.equals("supervised")) {
						writer.write("Negotiate-Interactive: true\n");
						writer.flush();
						readAnswer(reader, ending);
						continue;
					}
					// you want to define an alias
					// we don't do input parameters, as it would be hard to pass those along (?) (its not a script at the other end, it is just a bunch of lines we send for execution)
					// but you can do input prompts of course, with default values etc
					if (line.startsWith("alias:")) {
						String alias = line.substring("alias:".length()).trim();
						File folder = Triton.getFolder("alias");
						File file = new File(folder, alias + ".glue");
						if (!file.exists()) {
							file.createNewFile();
						}
						Nano nano = new Nano(terminal, folder);
						nano.open(file.getName());
						nano.run();
						String loaded = loadFile(file);
						// once done, we check if the file is empty and delete it in that case
						if (loaded == null || loaded.trim().isEmpty()) {
							file.delete();
							aliases.remove(alias);
						}
						else {
							aliases.put(alias, loaded);
						}
						continue;
					}
					// experimental tests
					if (line.equals("less")) {
						try {
							Less less = new Less(terminal, new File(".").toPath());
							// does not work (yet!)
							less.run(new Source.PathSource(new File("test.txt"), "test.txt"));
							continue;
						}
						catch (Exception e) {
							e.printStackTrace();
							continue;
						}
					}
					
					// install the cert
					if (line.equals("allow")) {
						// force generation of the key (not clean!)
						TritonLocalConsole.getContext();
						X509Certificate certificate = TritonLocalConsole.getAuthenticationKeystore().getCertificate(TritonLocalConsole.getProfile());
						StringWriter certWriter = new StringWriter();
						SecurityUtils.encodeCertificate(certificate, certWriter);
						certWriter.flush();
						line = "addUser(\"" + certWriter.toString().replaceAll("[\r\n]+", "\n\t") + "\")";
					}
					if (line.equals("clear")) {
                        terminal.puts(Capability.clear_screen);
                        terminal.flush();
					}
					if (line.equals("exit")) {
						break;
					}
					
					// if its an alias, we run it
					if (aliases.keySet().contains(line.trim())) {
						line = aliases.get(line.trim());
					}
					
					running = true;
					// we need to send line by line so we can read the response
					String[] split = line.split("\n");
					for (int i = 0; i < split.length; i++) {
						// if we are not at the end yet, add the \ to signal that more is coming
						writer.write(split[i] + (i == split.length - 1 ? "" : "\\") + "\n");
						writer.flush();
						
						String responseLine;
						while ((responseLine = reader.readLine()) != null) {
							// we done here!
							if (responseLine.equals(ending)) {
								break;
							}
							else if (responseLine.endsWith(inputEnding)) {
								String content = consoleReader.readLine(responseLine.substring(0, responseLine.length() - inputEnding.length()));
								writer.write(content + "\n");
								writer.flush();
							}
							else if (responseLine.endsWith(passwordEnding)) {
								String content = consoleReader.readLine(responseLine.substring(0, responseLine.length() - passwordEnding.length()), '*');
								writer.write(content + "\n");
								writer.flush();
							}
							// we want to edit a file
							else if (responseLine.endsWith(fileEditEnding)) {
								// the line is structured as follows:
								// size_in_bytes;filename
								String[] parts = responseLine.replace(fileEditEnding, "").split(";", 2);
								long size = Long.parseLong(parts[0]);
								String fileName = parts[1];
								File tmpFolder = Triton.getNewTmpFolder();
								writer.write("ok\n");
								writer.flush();
								File file;
								if (size > 0) {
									file = copyInFile(tmpFolder, size, fileName, inputStream);
								}
								else {
									file = new File(tmpFolder, fileName);
									file.createNewFile();
								}
								Nano nano = new Nano(terminal, tmpFolder);
								nano.open(file.getName());
								nano.run();
								// once we are done, we will reupload the file
								// we could do some MD5 hashing to double check that you actually changed something, but then we still have to read through the file
								// given that we are likely to only edit small files, we will skip it for now and simply stream it back
								// we first write the same line as the server sent us
								writer.write(file.length() + ";" + file.getName() + fileEditEnding + "\n");
								// flush it cause we are directly using the outputstream next!
								writer.flush();
								// we wait for the go ahead of the server
								String readLine = reader.readLine();
								if (readLine.equalsIgnoreCase("ok")) {
									if (file.length() > 0) {
										// then we stream back the file
										copyOutFile(file, outputStream);
									}
									// delete temporary files
									file.delete();
									tmpFolder.delete();
								}
								// we should handle this better in the future
								else {
									throw new IllegalStateException("Unexpected response: '" + readLine + "'");
								}
							}
							else {
								terminal.writer().write(responseLine + "\n");
								terminal.writer().flush();
							}
						}
					}
					running = false;
					runningKill = 0;
				}
			}
			catch (Exception e) {
				if (Triton.DEBUG) {
					e.printStackTrace();
				}
				// exiting...
				try {
					if (!socket.isClosed()) {
						socket.close();
					}
				}
				catch (IOException e1) {
					// TODO Auto-generated catch block
				}
			}
		}
		catch (Exception e) {
			System.out.println("Could not connect to Triton agent, are you sure it is running?");
		}
	}
	
	private static Map<String, String> loadAliases() {
		Map<String, String> aliases = new HashMap<String, String>();
		File folder = Triton.getFolder("alias");
		for (File child : folder.listFiles()) {
			if (child.getName().endsWith(".glue")) {
				aliases.put(child.getName().replaceAll("\\.glue$", ""), loadFile(child));
			}
		}
		return aliases;
	}
	
	private static String loadFile(File file) {
		try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
			byte[] bytes = IOUtils.toBytes(IOUtils.wrap(input));
			return new String(bytes);
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static void copyOutFile(File file, OutputStream outputStream) {
		try (InputStream inputStream = new BufferedInputStream(new FileInputStream(file))) {
			byte [] buffer = new byte[8096];
			int read = 0;
			while ((read = inputStream.read(buffer)) >= 0) {
				outputStream.write(buffer, 0, read);
				outputStream.flush();
			}
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	public static File copyInFile(File targetFolder, long size, String fileName, InputStream inputStream) {
		File file = new File(targetFolder, fileName);
		// it is then followed by that exact amount in bytes
		try (OutputStream output = new BufferedOutputStream(new FileOutputStream(file))) {
			byte [] buffer = new byte[8096];
			int read = 0;
			while ((read = inputStream.read(buffer, 0, (int) Math.min(size, buffer.length))) >= 0) {
				output.write(buffer, 0, read);
				size -= read;
				if (size == 0) {
					break;
				}
			}
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
		return file;
	}
	

	private static String encodeCertWithWrapper() throws CertificateEncodingException, KeyStoreException, IOException {
		String certWriter = encodeCert();
		certWriter = certWriter.replaceAll("(?m)^", "\t");
		certWriter = "addUser('" + certWriter.trim() + "')";
		return certWriter;
	}
	
	private static String encodeCert() throws KeyStoreException, CertificateEncodingException, IOException {
		X509Certificate certificate = TritonLocalConsole.getAuthenticationKeystore().getCertificate(TritonLocalConsole.getProfile());
		return encodeCert(certificate);
	}

	private static String encodeCert(X509Certificate certificate) throws CertificateEncodingException, IOException {
		StringWriter certWriter = new StringWriter();
		SecurityUtils.encodeCertificate(certificate, certWriter);
		certWriter.flush();
		return certWriter.toString();
	}

	private static void createProfile() throws IOException {
		StandardInputProvider standardInputProvider = new StandardInputProvider();
		String name = TritonLocalConsole.getName();
		String input = standardInputProvider.input("New profile name [" + name + "]: ", false, name);
		if (input == null || input.trim().isEmpty()) {
			System.out.println("Please provide a profile name");
			createProfile();
		}
		else {
			String profileName = standardInputProvider.input("Username for this profile [" + name + "]: ", false, name);
			input = input.replaceAll("[^\\w.@-]+", "-");
			System.setProperty("name", profileName);
			System.setProperty("triton.profile", input);
		}
	}
	
	private static void persistHostChoice(String key, String chosen, String...hosts) {
		ArrayList<String> arrayList = new ArrayList<String>(Arrays.asList(hosts));
		arrayList.remove(chosen);
		arrayList.add(0, chosen);
		String result = "";
		for (String single : arrayList) {
			if (!result.isEmpty()) {
				result += ", ";
			}
			result += single;
		}
		Properties configuration = Triton.getEnvironment();
		configuration.setProperty(key, result);
		Triton.setEnvironment(configuration);
	}
	
	private static void chooseHost() throws IOException {
		chooseHost(null);
	}
	
	private static void chooseHost(String filter) throws IOException {
		String host = System.getProperty("triton.host", System.getProperty("host"));
		String profile = TritonLocalConsole.getProfile();
		if (profile == null) {
			chooseProfile();
			profile = TritonLocalConsole.getProfile();
		}
		if (host == null) {
			StandardInputProvider standardInputProvider = new StandardInputProvider();
			
			Properties configuration = Triton.getEnvironment();
			String hosts = configuration.getProperty("hosts." + profile);
			if (hosts != null) {
				System.out.println();
				System.out.println("Available hosts:");
				System.out.println();
				int i = 1;
				String[] split = hosts.split("[\\s]*,[\\s]*");
				Integer first = null;
				for (String single : split) {
					if (filter != null && !filter.trim().isEmpty() && !single.toLowerCase().contains(filter.toLowerCase())) {
						// we use it as an index, so it must stay accurate
						i++;
						continue;
					}
					else {
						if (first == null) {
							first = i;
						}
						System.out.println(i++ + ") " + single);
					}
				}
				System.out.println(i++ + ") Add new host");
				System.out.println(i++ + ") Remove host");
//				System.out.println(i++ + ") Exit");
				System.out.println();
				String result = standardInputProvider.input("Choose host [" + (first == null ? "none" : split[first - 1]) + "]: ", false, first == null ? "" + (split.length + 4) : first.toString());
				if (result.matches("^[0-9]+$")) {
					int choice = Integer.parseInt(result);
					if (choice - 1 < split.length && choice >= 1) {
						System.setProperty("host", split[choice - 1]);
						persistHostChoice("hosts." + profile, split[choice - 1], split);
						return;
					}
					else if (choice == split.length + 1) {
						result = standardInputProvider.input("New Host: ", false, null);
						System.setProperty("host", result);
						persistHostChoice("hosts." + profile, result, split);
						return;
					}
					else if (choice == split.length + 2) {
						result = standardInputProvider.input("Host to remove: ", false, null);
						if (result.matches("^[0-9]+$")) {
							choice = Integer.parseInt(result);
							if (choice - 1 < split.length) {
								List<String> list = new ArrayList<String>(Arrays.asList(split));
								list.remove(choice - 1);
								if (list.isEmpty()) {
									configuration.remove("hosts." + profile);
								}
								else {
									String builder = "";
									for (String single : list) {
										if (!builder.isEmpty()) {
											builder += ",";
										}
										builder += single;
									}
									configuration.setProperty("hosts." + profile, builder);
								}
								Triton.setEnvironment(configuration);
							}
						}
						chooseHost();
						return;
					}
					else if (choice == split.length + 3) {
						System.exit(1);
					}
					else {
						chooseHost();
						return;
					}
				}
				else {
					// we assume you typed a new one
//					System.setProperty("host", result);
//					persistHostChoice("hosts." + profile, result, split);
					// we assume you typed a filter to reduce the amount of host options
					chooseHost(result);
					return;
				}
			}
			else {
				System.out.println();
				String input = standardInputProvider.input("Enter the host url [ts://localhost]: ", false, "ts://localhost");
				if (input != null && !input.trim().isEmpty()) {
					System.setProperty("host", input);
					persistHostChoice("hosts." + profile, input);
					return;
				}
				else {
					System.out.println("Please enter a valid host url");
					chooseHost();
					return;
				}
			}
		}
	}
	
	private static void chooseProfile() {
		chooseProfile(null);
	}
	
	private static void chooseProfile(String filter) {
		String chosenProfile = System.getProperty("triton.profile", System.getProperty("profile"));
		// choose a profile if not chosen
		if (chosenProfile == null) {
			StandardInputProvider standardInputProvider = new StandardInputProvider();
			KeyStoreHandler authenticationKeystore = TritonLocalConsole.getAuthenticationKeystore();
			int i = 1;
			try {
				List<String> profiles = new ArrayList<String>();
				Map<String, X509Certificate[]> privateKeys = authenticationKeystore.getPrivateKeys();
				for (Map.Entry<String, X509Certificate[]> entry : privateKeys.entrySet()) {
					profiles.add(entry.getKey());
				}
				// no profile yet, invite to create one
				if (profiles.isEmpty()) {
					System.out.println("You do not have a profile yet, let's create one");
					createProfile();
				}
				else {
					System.out.println("Available profiles: \n");
					Properties configuration = Triton.getEnvironment();
					String usedProfiles = configuration.getProperty("profiles");
					if (usedProfiles != null && !usedProfiles.trim().isEmpty()) {
						List<String> profileList = new ArrayList<String>(Arrays.asList(usedProfiles.split("[\\s]*,[\\s]*")));
						Iterator<String> iterator = profileList.iterator();
						// remove profiles that are no longer valid
						while (iterator.hasNext()) {
							if (!profiles.contains(iterator.next())) {
								iterator.remove();
							}
						}
						// add new ones at the back
						for (String single : profiles) {
							if (!profileList.contains(single)) {
								profileList.add(single);
							}
						}
						profiles = profileList;
					}
					
					Integer first = null;
					
					for (String profile : profiles) {
						// if you have a filter, only show the relevant ones
						if (filter != null && !filter.trim().isEmpty() && !profile.toLowerCase().contains(filter.toLowerCase())) {
							i++;
							continue;
						}
						if (first == null) {
							first = i;
						}
						System.out.println(i++ + ") " + profile + " [" + TritonLocalConsole.getAlias(privateKeys.get(profile)[0]) + "]");
					}
					System.out.println(i++ + ") Create new profile");
					System.out.println(i++ + ") Remove existing profile");
					System.out.println(i++ + ") Print certificate");
					System.out.println(i++ + ") Print certificate with addUser");
//					System.out.println(i++ + ") Exit");
					System.out.println();
					String input = standardInputProvider.input("Choose profile [" + (first == null ? "none" : profiles.get(first - 1)) + "]: ", false, first == null ? "" + (profiles.size() + 5) : first.toString());
					// chosen by number
					if (input.matches("^[0-9]+$")) {
						int profileIndex = Integer.parseInt(input);
						if (profileIndex == profiles.size() + 1) {
							createProfile();
							return;
						}
						else if (profileIndex == profiles.size() + 2) {
							input = standardInputProvider.input("Choose profile to remove: ", false, null);
							if (input == null || input.trim().isEmpty()) {
								chooseProfile();
								return;
							}
							else {
								String profileToRemove;
								if (input.matches("^[0-9]+$")) {
									profileIndex = Integer.parseInt(input);
									if (profileIndex - 1 >= profiles.size() || profileIndex < 1) {
										System.out.println("Invalid profile choice");
										chooseProfile();
										return;	
									}
									else {
										profileToRemove = profiles.get(profileIndex - 1);										
									}
								}
								else if (profiles.contains(input)) {
									profileToRemove = input;
								}
								else {
									System.out.println("Invalid profile choice");
									chooseProfile();
									return;
								}
								authenticationKeystore.delete(profileToRemove);
								TritonLocalConsole.saveAuthentication(authenticationKeystore);
								System.out.println("Profile successfully removed");
								chooseProfile();
								return;
							}
						}
						// print the cert
						else if (profileIndex == profiles.size() + 3 || profileIndex == profiles.size() + 4) {
							boolean wrap = profileIndex == profiles.size() + 4;
							System.out.println();
							input = standardInputProvider.input("Choose profile to print: ", false, null);
							if (input == null || input.trim().isEmpty()) {
								chooseProfile();
								return;
							}
							else {
								String profileToPrint;
								if (input.matches("^[0-9]+$")) {
									profileIndex = Integer.parseInt(input);
									if (profileIndex - 1 >= profiles.size() || profileIndex < 1) {
										System.out.println("Invalid profile choice");
										chooseProfile();
										return;	
									}
									else {
										profileToPrint = profiles.get(profileIndex - 1);										
									}
								}
								else if (profiles.contains(input)) {
									profileToPrint = input;
								}
								else {
									System.out.println("Invalid profile choice");
									chooseProfile();
									return;
								}
								X509Certificate[] x509Certificates = authenticationKeystore.getPrivateKeys().get(profileToPrint);
								String encodedCert = encodeCert(x509Certificates[0]);
								if (wrap) {
									encodedCert = "addUser('" + encodedCert.replaceAll("(?m)^", "\t").trim().replaceAll("([\r\n]+)", "\\\\$1") + "')\n";
								}
								System.out.println();
								System.out.println(encodedCert);
								chooseProfile();
								return;
							}
						}
						else if (profileIndex == profiles.size() + 4) {
							System.exit(0);
						}
						else if (profileIndex - 1 >= profiles.size() || profileIndex < 1) {
							chooseProfile();
							return;
						}
						System.setProperty("triton.profile", profiles.get(profileIndex - 1));
						// move to the front of the line
						profiles.remove(System.getProperty("triton.profile"));
						profiles.add(0, System.getProperty("triton.profile"));
						String profileStringified = "";
						for (String single : profiles) {
							if (!profileStringified.isEmpty()) {
								profileStringified += ", ";
							}
							profileStringified += single;
						}
						configuration.setProperty("profiles", profileStringified);
						Triton.setEnvironment(configuration);
					}
					else {
						// we search an exact match first
						if (profiles.contains(input)) {
							System.setProperty("triton.profile", input);	
						}
						// otherwise we look for the first partial match
						else {
							boolean found = false;
							// don't allow for partial matches, instead filter!
							// otherwise it is very confusing behavior that a filter only kicks in if no one contains the part
//							for (String potential : profiles) {
//								if (potential.toLowerCase().contains(input.toLowerCase())) {
//									System.setProperty("triton.profile", potential);
//									found = true;
//									break;
//								}
//							}
							if (!found) {
								// we assume you meant to filter!
								chooseProfile(input);
								return;
							}
						}
					}
				}
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}
	
	private static String colored(String value) {
		return new AttributedString(value, AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT)).toAnsi();
	}
}
