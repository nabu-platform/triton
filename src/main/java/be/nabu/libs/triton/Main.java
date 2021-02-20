package be.nabu.libs.triton;

public class Main {
	public static final String VERSION = "0.1-beta";
	
	// horribly workaround for static methods requiring minimal state to figure out which setting they operate in
	// needs urgent refactor!
	public static boolean SERVER_MODE = false;
	
	public static void main(String...args) {
		SERVER_MODE = true;
		Triton triton = new Triton();
		boolean sandboxed = Boolean.parseBoolean(getArgument("sandboxed", "false", args));
		triton.setSandboxed(sandboxed);
		triton.start();
	}
	
	public static String getArgument(String name, String defaultValue, String...arguments) {
		for (String argument : arguments) {
			if (argument.trim().startsWith(name + "=")) {
				String value = argument.substring(name.length() + 1);
				if (value.isEmpty()) {
					throw new IllegalArgumentException("The parameter " + name + " is empty");
				}
				return value;
			}
			else if (argument.trim().equals(name)) {
				return "true";
			}
		}
		return System.getProperty(name, defaultValue);
	}
}
