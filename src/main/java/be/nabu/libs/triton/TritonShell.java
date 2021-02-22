package be.nabu.libs.triton;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.cert.X509Certificate;
import java.util.Map;

import javax.net.ssl.SSLContext;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp.Capability;

import be.nabu.glue.impl.StandardInputProvider;
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
	
	public static void main(String...args) throws URISyntaxException {
		Main.systemPropertify(args);
		// the connection string
		// e.g. ts://localhost:5000, the protocol stands for "triton shell" or "triton socket"
		// secure is for example sts://localhost:5100 -> sts: secure triton shell
		URI url = new URI(System.getProperty("triton.host", System.getProperty("host", "ts://localhost")));
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
				
				Terminal terminal = TerminalBuilder.builder()
						.name("Triton v" + Main.VERSION)
						.signalHandler(Terminal.SignalHandler.SIG_IGN)
						.nativeSignals(true)
						//			.attributes(termAttribs)
						.build();
				
				BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
				BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
				
				String ending = "//the--end//";
				String inputEnding = "//request--input//";
				String passwordEnding = "//request--password//";
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
				terminal.writer().println("- self			Print client cert for installation");
				terminal.writer().println("- (un)supervised	Toggle between supervised and unsupervised mode");
				terminal.writer().println("_______________________________________________________________\n");
				
				// we unset the escape characters so we can send them to the backend, otherwise they get stripped
				DefaultParser parser = new DefaultParser();
				parser.setEscapeChars(null);
				
				File history = new File(System.getProperty("user.home"), ".triton_shell_history");
				LineReader consoleReader = LineReaderBuilder.builder()
					.terminal(terminal)
					.parser(parser)
					.variable(LineReader.HISTORY_FILE, history.toPath())
					.variable(LineReader.SECONDARY_PROMPT_PATTERN, colored("%P -> "))
					.variable(LineReader.BLINK_MATCHING_PAREN, 0)
					.variable(LineReader.DISABLE_COMPLETION, true)
					.build();
				
				// we want to be able to insert bleedin' tabs
				consoleReader.setOpt(LineReader.Option.INSERT_TAB);
//				consoleReader.unsetOpt(LineReader.Option.AUTO_MENU);
//				consoleReader.unsetOpt(LineReader.Option.AUTO_MENU_LIST);
				
				String line;
				// the string we pass along is the "prompt" string
				// the prompt is actually controlled by the remote server
				// by using a space as a prompt, it seems to work with the prompt provided by the server
				// it doesn't seem to work (unfortunately) with an empty string
				// the space does tend to remain in unwanted places if content comes back from the server though...
				// we could get rid of the space but then we need a proper prompt here, which will interfere with the prompt via telnet so we would have to cripple the straight-to-telnet shizzle
				while ((line = consoleReader.readLine("$ ")) != null) {
					if (line.equals("self")) {
						X509Certificate certificate = TritonLocalConsole.getAuthenticationKeystore().getCertificate(TritonLocalConsole.getProfile());
						StringWriter certWriter = new StringWriter();
						SecurityUtils.encodeCertificate(certificate, certWriter);
						certWriter.flush();
						terminal.writer().println(certWriter.toString());
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
							else {
								terminal.writer().write(responseLine + "\n");
								terminal.writer().flush();
							}
						}
					}
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
	
	private static String colored(String value) {
		return new AttributedString(value, AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT)).toAnsi();
	}
}
