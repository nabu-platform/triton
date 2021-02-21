package be.nabu.libs.triton;

import java.io.File;

import be.nabu.libs.resources.file.FileDirectory;

public class Triton {
	
	private TritonLocalConsole console;
	private boolean sandboxed;
	
	public static int DEFAULT_PLAIN_PORT = 5122;
	public static int DEFAULT_SECURE_PORT = 5123;

	public void start() {
		File target = getFolder("scripts");
		System.out.println("Script folder: " + target.getAbsolutePath());
		if (!target.exists()) {
			target.mkdirs();
		}
		FileDirectory scriptDirectory = new FileDirectory(null, target, false);
		
		TritonGlueEngine glue = new TritonGlueEngine(this, scriptDirectory);
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
	
}
