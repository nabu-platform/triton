package be.nabu.libs.triton;

import java.io.File;

import be.nabu.libs.resources.file.FileDirectory;

public class Triton {
	
	private TritonLocalConsole console;
	private boolean sandboxed;

	public void start() {
		File target = new File(System.getProperty("user.home"), "scripts");
		System.out.println("Script folder: " + target.getAbsolutePath());
		if (!target.exists()) {
			target.mkdirs();
		}
		FileDirectory scriptDirectory = new FileDirectory(null, target, false);
		
		TritonGlueEngine glue = new TritonGlueEngine(this, scriptDirectory);
		glue.setSandboxed(sandboxed);
		console = new TritonLocalConsole(5000, glue, 10);
		
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
	
}
