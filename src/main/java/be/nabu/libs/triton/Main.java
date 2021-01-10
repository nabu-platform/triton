package be.nabu.libs.triton;

import java.io.File;

import be.nabu.libs.resources.file.FileDirectory;

public class Main {
	public static final String VERSION = "0.1-beta";
	
	public static void main(String...args) {
		File target = new File(System.getProperty("user.home"), "scripts");
		System.out.println("Script folder: " + target.getAbsolutePath());
		if (!target.exists()) {
			target.mkdirs();
		}
		FileDirectory scriptDirectory = new FileDirectory(null, target, false);
		
		TritonGlueEngine glue = new TritonGlueEngine(scriptDirectory);
		TritonLocalConsole console = new TritonLocalConsole(5000, glue);
		
		console.start();
	}
}
