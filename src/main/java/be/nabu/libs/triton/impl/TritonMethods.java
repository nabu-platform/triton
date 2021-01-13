package be.nabu.libs.triton.impl;

import java.util.List;

import be.nabu.libs.evaluator.annotations.MethodProviderClass;
import be.nabu.libs.triton.Triton;
import be.nabu.libs.triton.TritonLocalConsole.TritonConsoleInstance;

@MethodProviderClass(namespace = "triton")
public class TritonMethods {

	private Triton triton;

	public TritonMethods(Triton triton) {
		this.triton = triton;
	}
	
	public List<TritonConsoleInstance> connected() {
		return triton.getConsole() == null ? null : triton.getConsole().getInstances();
	}
	
	public void disconnect(long id) throws Exception {
		if (triton.getConsole() != null) {
			for (TritonConsoleInstance instance : triton.getConsole().getInstances()) {
				if (instance.getId() == id) {
					instance.close();
				}
			}
		}
	}
}
