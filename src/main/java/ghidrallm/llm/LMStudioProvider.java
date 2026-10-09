package ghidrallm.llm;

import java.util.function.Supplier;

import ghidrallm.config.Settings;

/** LM Studio's OpenAI-compatible local server. The default and recommended provider. */
public class LMStudioProvider extends OpenAiCompatibleProvider {

	public LMStudioProvider(Supplier<Settings> settings) {
		super("lmstudio", settings, null);
	}

	@Override
	protected boolean retryTransient() {
		return false;
	}

	@Override
	public boolean requiresExplicitModel() {
		return false;
	}
}
