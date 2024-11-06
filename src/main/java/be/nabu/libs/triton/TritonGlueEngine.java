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

import java.io.IOException;
import java.nio.charset.Charset;

import be.nabu.glue.api.ScriptRepository;
import be.nabu.glue.core.api.MethodProvider;
import be.nabu.glue.core.impl.parsers.GlueParserProvider;
import be.nabu.glue.core.impl.providers.StaticJavaMethodProvider;
import be.nabu.glue.core.impl.providers.SystemMethodProvider;
import be.nabu.glue.core.repositories.ScannableScriptRepository;
import be.nabu.glue.utils.MultipleRepository;
import be.nabu.libs.resources.api.ResourceContainer;
import be.nabu.libs.triton.impl.TritonMethods;

public class TritonGlueEngine {
	private Charset charset = Charset.forName("UTF-8");
	private MultipleRepository repository;
	private boolean sandboxed;
	private GlueParserProvider parserProvider;
	
	public TritonGlueEngine(Triton triton, ResourceContainer<?>...scripts) {
		try {
			parserProvider = new GlueParserProvider(new StaticJavaMethodProvider(new TritonMethods(triton))) {
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
			if (scripts == null || scripts.length == 0) {
				throw new RuntimeException("No script folder found");
			}
			else {
				MultipleRepository result = new MultipleRepository(null);
				for (ResourceContainer<?> script : scripts) {
					result.add(new ScannableScriptRepository(null, script, parserProvider, charset, true));
				}
				repository = result;
			}
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	public void reloadScriptContainers(ResourceContainer<?>...scripts) {
		repository.removeAll();
		try {
			for (ResourceContainer<?> script : scripts) {
				repository.add(new ScannableScriptRepository(null, script, parserProvider, charset, true));
			}
		}
		catch (Exception e) {
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

	public GlueParserProvider getParserProvider() {
		return parserProvider;
	}
	
	public MethodProvider[] getMethodProviders() {
		return parserProvider.getMethodProviders(repository);
	}
}
