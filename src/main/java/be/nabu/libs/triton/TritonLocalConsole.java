package be.nabu.libs.triton;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.glue.api.InputProvider;
import be.nabu.glue.core.impl.executors.EvaluateExecutor;
import be.nabu.glue.impl.SimpleExecutionEnvironment;
import be.nabu.glue.impl.formatters.SimpleOutputFormatter;
import be.nabu.glue.utils.DynamicScript;
import be.nabu.glue.utils.ScriptRuntime;
import be.nabu.glue.utils.VirtualScript;
import be.nabu.libs.triton.api.ConsoleSource;
import be.nabu.libs.triton.impl.ConsoleSocketSource;

public class TritonLocalConsole {

	private Logger logger = LoggerFactory.getLogger(getClass());
	private TritonGlueEngine engine;
	private int port;
	private Charset charset = Charset.forName("UTF-8");
	private boolean running;
	private int maxConcurrentConsoles;
	private ExecutorService threadPool;
	private long consoleInstanceId;
	
	private List<TritonConsoleInstance> instances = new ArrayList<TritonConsoleInstance>();
	
	public class TritonConsoleInstance implements AutoCloseable {
		private ConsoleSource source;
		private ScriptRuntime rootRuntime;
		private long id;
		private Date connected = new Date();
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
			return "#" + id + "-" + source.toString() + "-" + connected;
		}
		public long getId() {
			return id;
		}
	}
	
	public TritonLocalConsole(int port, TritonGlueEngine engine, int maxConcurrentConsoles) {
		this.port = port;
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
		Thread thread = new Thread(new Runnable() {
			@Override
			public void run() {
				running = true;
				try {
					try (ServerSocket socket = new ServerSocket(port)) {
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
		thread.setName("triton-cli");
		thread.start();
	}
	
	private void start(ConsoleSource source) {
		threadPool.submit(new Runnable() {
			private String responseEnd, inputEnd, passwordEnd;

			@Override
			public void run() {
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
					logger.info("Triton console #" + instance.getId() + " connected");
					instances.add(instance);
					runtime.registerInThread();
					// TODO: token?
					StringBuilder buffered = new StringBuilder();
					StringBuilder script = new StringBuilder();
					BufferedReader reader = new BufferedReader(source.getReader());
					BufferedWriter writer = new BufferedWriter(source.getWriter());
					
					responseEnd = "";
					inputEnd = "";
					passwordEnd = "";
					String line;
					while ((line = reader.readLine()) != null) {
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
								continue;
							}
							// if without trimming, you still typed quit (so no whitespace etc), we stop
							else if (line.equals("quit")) {
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
							}
							else if (line.startsWith("Negotiate-Input-End:")) {
								inputEnd = line.substring("Negotiate-Input-End:".length()).trim();
							}
							else if (line.startsWith("Negotiate-Password-End:")) {
								passwordEnd = line.substring("Negotiate-Password-End:".length()).trim();
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
								// because this is run synchronously, it shouldn't interfere with the above one
								// if you ever request input asynchronously, this will...not work well :|
								scriptRuntime.setInputProvider(new InputProvider() {
									@Override
									public String input(String message, boolean secret) throws IOException {
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
										return reader.readLine();
									}
								});
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
				catch (Exception e) {
					logger.info("Triton console #" + instance.getId() + " disconnected");
				}
				finally {
					if (instance != null) {
						instances.remove(instance);
					}
					if (runtime != null) {
						runtime.unregisterInThread();
					}
				}
			}
		});
	}

	public List<TritonConsoleInstance> getInstances() {
		return instances;
	}

	public int getMaxConcurrentConsoles() {
		return maxConcurrentConsoles;
	}
	
}
