package ghidrallm.llm;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import ghidrallm.config.Settings;
import ghidrallm.util.CancellationToken;

/** Delegates to the provider selected in settings at call time, so changing providers needs no restart. */
public class RoutingProvider implements LLMProvider {

	private final Supplier<Settings> settings;
	private final Map<String, LLMProvider> providers;

	public RoutingProvider(Supplier<Settings> settings, Map<String, LLMProvider> providers) {
		this.settings = settings;
		this.providers = providers;
	}

	public LLMProvider current() {
		LLMProvider p = providers.get(settings.get().providerType);
		return p != null ? p : providers.values().iterator().next();
	}

	@Override
	public String id() {
		return current().id();
	}

	@Override
	public List<String> listModels() throws LLMException {
		return current().listModels();
	}

	@Override
	public ChatResponse chat(ChatRequest request, CancellationToken cancel) throws LLMException {
		return current().chat(request, cancel);
	}

	@Override
	public boolean canFallBackToPrompted() {
		return current().canFallBackToPrompted();
	}

	@Override
	public boolean requiresExplicitModel() {
		return current().requiresExplicitModel();
	}

	@Override
	public ConnectionReport testConnection(String model) {
		return current().testConnection(model);
	}
}
