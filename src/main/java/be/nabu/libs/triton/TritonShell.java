package be.nabu.libs.triton;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Attributes;
import org.jline.terminal.Attributes.ControlChar;
import org.jline.terminal.Attributes.LocalFlag;
import org.jline.terminal.Attributes.OutputFlag;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp.Capability;

// the shell is a thin wrapper around the triton console endpoint (that can also be used via telnet)
public class TritonShell {
	
	public static String VERSION = "0.1-beta";
	
	public static void main(String...args) {
		try {
			Socket socket = new Socket("localhost", 5000);
			try {
				System.out.println("Welcome to Triton Shell v" + VERSION);
				System.out.print("Connected to ");
				
				Attributes termAttribs = new Attributes();
				
				// enable output processing (required for all output flags)
				termAttribs.setOutputFlag(OutputFlag.OPOST, true);
				// map newline to carriage return + newline
				termAttribs.setOutputFlag(OutputFlag.ONLCR, true);
				
				// enable signals (for CTRL+C usage)
				termAttribs.setLocalFlag(LocalFlag.ISIG, true);
				// print control chars as '^X' (e.g. ^C for CTRL+C)
				termAttribs.setLocalFlag(LocalFlag.ECHOCTL, true);
				
				// enable CTRL+D shortcut
				termAttribs.setControlChar(ControlChar.VEOF, 4);
				// enable CTRL+C shortcut
				termAttribs.setControlChar(ControlChar.VINTR, 3);
				Terminal terminal = TerminalBuilder.builder()
						.name("Triton v" + Main.VERSION)
						.signalHandler(Terminal.SignalHandler.SIG_IGN)
						.nativeSignals(true)
						//			.attributes(termAttribs)
						.build();
				
				InputStreamReader reader = new InputStreamReader(socket.getInputStream(), "UTF-8");
				BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
				
				Thread thread = new Thread(new Runnable() {
					@Override
					public void run() {
						try {
							int result;
							byte [] array = new byte[1];
							while ((result = reader.read()) >= 0) {
								array[0] = (byte) result;
//								System.out.print((char)result);
//								terminal.writer().print((char) result);
								terminal.writer().print(new String(array, "UTF-8"));
								terminal.writer().flush();
							}
							socket.close();
						}
						catch (Exception e) {
							// do nothing...
						}
						finally {
							System.exit(1);
						}
					}
				});
				thread.setDaemon(true);
				thread.start();
				
				File history = new File(System.getProperty("user.home"), ".triton_shell_history");
				LineReader consoleReader = LineReaderBuilder.builder()
					.terminal(terminal)
					.variable(LineReader.HISTORY_FILE, history.toPath())
					.variable(LineReader.SECONDARY_PROMPT_PATTERN, colored("%P -> "))
					.variable(LineReader.BLINK_MATCHING_PAREN, 0)
					.build();
				
				String line;
				// the string we pass along is the "prompt" string
				// the prompt is actually controlled by the remote server
				// by using a space as a prompt, it seems to work with the prompt provided by the server
				// it doesn't seem to work (unfortunately) with an empty string
				// the space does tend to remain in unwanted places if content comes back from the server though...
				// we could get rid of the space but then we need a proper prompt here, which will interfere with the prompt via telnet so we would have to crippled the straight-to-telnet shizzle
				while ((line = consoleReader.readLine(" ")) != null) {
					if (line.equals("clear")) {
                        terminal.puts(Capability.clear_screen);
                        terminal.flush();
					}
					writer.write(line + "\n");
					writer.flush();
				}
			}
			catch (Exception e) {
				System.out.println("Exiting Triton Shell");
				// exiting...
				try {
					if (!socket.isClosed()) {
						socket.close();
					}
				}
				catch (IOException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
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
