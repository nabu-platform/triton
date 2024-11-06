/*
* Copyright (C) 2021 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package be.nabu.libs.triton;

public class Main {
	public static final String VERSION = "0.1-beta";
	
	// horribly workaround for static methods requiring minimal state to figure out which setting they operate in
	// needs urgent refactor!
	public static boolean SERVER_MODE = false;
	
	public static void main(String...args) {
		systemPropertify(args);
		SERVER_MODE = true;
		Triton triton = new Triton();
		boolean sandboxed = Boolean.parseBoolean(getArgument("sandboxed", "false", args));
		triton.setSandboxed(sandboxed);
		triton.start();
	}

	public static void systemPropertify(String... args) {
		// also put them in the system properties
		for (String argument : args) {
			int index = argument.indexOf('=');
			if (index > 0) {
				System.setProperty(argument.substring(0, index), argument.substring(index + 1));
			}
		}
		Triton.DEBUG = Boolean.parseBoolean(System.getProperty("debug", "false"));
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
