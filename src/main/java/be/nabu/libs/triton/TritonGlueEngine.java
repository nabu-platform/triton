package be.nabu.libs.triton;

import java.io.IOException;
import java.nio.charset.Charset;

import be.nabu.glue.api.ScriptRepository;
import be.nabu.glue.core.api.MethodProvider;
import be.nabu.glue.core.impl.parsers.GlueParserProvider;
import be.nabu.glue.core.impl.providers.StaticJavaMethodProvider;
import be.nabu.glue.core.impl.providers.SystemMethodProvider;
import be.nabu.glue.core.repositories.ScannableScriptRepository;
import be.nabu.libs.resources.api.ResourceContainer;
import be.nabu.libs.triton.impl.TritonMethods;

public class TritonGlueEngine {
	private Charset charset = Charset.forName("UTF-8");
	private ScannableScriptRepository repository;
	private boolean sandboxed;
	
	public TritonGlueEngine(Triton triton, ResourceContainer<?> scripts) {
		try {
			GlueParserProvider parserProvider = new GlueParserProvider(new StaticJavaMethodProvider(new TritonMethods(triton))) {
				@Override
				public MethodProvider[] getMethodProviders(ScriptRepository repository) {
					MethodProvider[] methodProviders = super.getMethodProviders(repository);
					MethodProvider [] providers = new MethodProvider[methodProviders.length + 1];
					for (int i = 0; i < methodProviders.length; i++) {
						providers[i] = methodProviders[i];
					}
					// anything that is not recognized, should be seen as a system command (for easy system integration)
					// otherwise you keep having to define system. in front of everything, which is...annoying
					SystemMethodProvider systemMethodProvider = new SystemMethodProvider();
					systemMethodProvider.setAllowCatchAll(true);
					providers[providers.length - 1] = systemMethodProvider;
					return providers;
				}
			};
			if (sandboxed) {
				parserProvider.setSandboxed(sandboxed);
			}
			repository = new ScannableScriptRepository(null, scripts, parserProvider, charset, true);
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	public void refresh() {
		try {
			repository.refresh();
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public ScriptRepository getRepository() {
		return repository;
	}

	public boolean isSandboxed() {
		return sandboxed;
	}

	public void setSandboxed(boolean sandboxed) {
		this.sandboxed = sandboxed;
	}
	
}
