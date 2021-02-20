package be.nabu.libs.triton;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp.Capability;

// the shell is a thin wrapper around the triton console endpoint (that can also be used via telnet)
public class TritonShell {
	
	public static String VERSION = "0.1-beta";
	
	private static String readAnswer(BufferedReader reader, String ending, String inputEnding) throws IOException {
		String line;
		StringBuilder builder = new StringBuilder();
		while ((line = reader.readLine()) != null) {
			// the output ending is on a separate line
			if (line.equals(ending)) {
				break;
			}
			// the inputending is NOT on a separate line
			else if (inputEnding != null && line.endsWith(inputEnding)) {
				builder.append(line.substring(0, line.length() - inputEnding.length()));
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
	
	public static void main(String...args) {
		try {
			Socket socket = new Socket("localhost", 5000);
			try {
				Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
					@Override
					public void run() {
						System.out.println("Exiting Triton Shell");
					}
				}));
				
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
				// we set a requested ending to each response so we can target that in our parsing
				// we do this as the first step so we can parse the next steps correctly
				writer.write("Negotiate-Response-End: " + ending + "\n");
				writer.flush();
				// an answer will always come
				readAnswer(reader, ending, inputEnding);
				writer.write("version\n");
				writer.flush();
				String version = readAnswer(reader, ending, inputEnding);
				
				writer.write("Negotiate-Input-End: " + inputEnding + "\n");
				writer.flush();
				// an answer will always come
				readAnswer(reader, ending, inputEnding);
				
				terminal.puts(Capability.clear_screen);
                terminal.flush();
                terminal.writer().println("_______________________________________________________________\n");
				terminal.writer().println("* Triton Shell v" + VERSION + " connected to Triton Agent v" + version.trim());
				terminal.writer().println("_______________________________________________________________\n");
				terminal.writer().println("- quit			Disconnect from the triton agent (ctrl+c)");
				terminal.writer().println("- clear			Clear the state");
				// you can also end a regular line with a backslash for the same effect!
				terminal.writer().println("- alt+enter		Create a multiline script");
				terminal.writer().println("- show			Show the script so far");
				terminal.writer().println("- state			Print the current variable state");
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
					if (line.equals("clear")) {
                        terminal.puts(Capability.clear_screen);
                        terminal.flush();
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
							else {
								terminal.writer().write(responseLine + "\n");
								terminal.writer().flush();
							}
						}
					}
				}
			}
			catch (Exception e) {
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
