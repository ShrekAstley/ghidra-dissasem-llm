package ghidrallm.llm;

import java.util.List;

import ghidrallm.util.CancellationToken;
import ghidrallm.util.Text;

/** The shared three-step "Test Connection": reachable → model available → chat completion works. */
final class ConnectionTest {
	private ConnectionTest() {}

	static ConnectionReport run(LLMProvider p, String endpoint, String model) {
		ConnectionReport r = new ConnectionReport();
		List<String> models;
		try {
			models = p.listModels();
			r.add("Provider reachable", true, endpoint);
			r.setModels(models);
		}
		catch (LLMException e) {
			r.add("Provider reachable", false, e.getMessage());
			return r;
		}
		String use = model;
		if (use == null || use.isBlank()) {
			if (p.requiresExplicitModel()) {
				r.add("Model available", false, "Choose a model in Settings (" + models.size() + " available)");
				return r;
			}
			if (models.isEmpty()) {
				r.add("Model available", false, LLMException.Kind.NO_MODEL.help);
				return r;
			}
			use = models.get(0);
		}
		// Some hosted APIs list a subset of usable ids; trust the list when it is non-empty and the id is absent only for local servers.
		boolean listed = models.contains(use) || (p.requiresExplicitModel() && models.isEmpty());
		if (!listed && p.requiresExplicitModel()) {
			// Hosted providers may accept aliases not in the list; verify with the real call below.
			r.add("Model available", true, use + " (not in the provider's list; will be verified by the chat test)");
		}
		else {
			r.add("Model available", listed, listed ? use : "'" + use + "' is not loaded; available: " + String.join(", ", models));
			if (!listed) {
				return r;
			}
		}
		try {
			ChatResponse resp = p.chat(new ChatRequest(use, List.of(ChatMessage.user("Reply with the single word: ok")), null, 0.0, 64),
				new CancellationToken());
			r.add("Chat completion works", true, resp.elapsedMillis() + " ms, reply: " + Text.oneLine(resp.content(), 40));
		}
		catch (LLMException e) {
			r.add("Chat completion works", false, e.getMessage());
		}
		return r;
	}
}
