package ghidrallm.llm;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import ghidrallm.config.Settings;
import ghidrallm.util.CancellationToken;
import ghidrallm.util.Text;

/**
 * Any server speaking the OpenAI chat-completions protocol: LM Studio, Ollama, llama.cpp, vLLM — and, when the
 * user opts in, hosted services such as OpenAI or OpenRouter. Adds bearer authentication, remote-consent
 * enforcement, retries for transient provider errors, and automatic adaptation to models that want
 * {@code max_completion_tokens} or reject {@code temperature}.
 */
public class OpenAiCompatibleProvider implements LLMProvider {

	private final String id;
	private final Supplier<Settings> settings;
	private final Function<Settings, String> keySource;
	private final HttpTransport http = new HttpTransport();
	private volatile String maxTokensField;
	private volatile boolean sendTemperature = true;

	public OpenAiCompatibleProvider(String id, Supplier<Settings> settings, Function<Settings, String> keySource) {
		this.id = id;
		this.settings = settings;
		this.keySource = keySource;
	}

	@Override
	public String id() {
		return id;
	}

	private static boolean isOpenAiHost(String host) {
		return host != null && (host.equals("api.openai.com") || host.endsWith(".openai.com"));
	}

	private static boolean needsKey(String host) {
		return isOpenAiHost(host) || "openrouter.ai".equals(host);
	}

	@Override
	public boolean canFallBackToPrompted() {
		return !isOpenAiHost(EndpointPolicy.hostOf(settings.get().endpoint));
	}

	@Override
	public boolean requiresExplicitModel() {
		return !EndpointPolicy.isLoopbackUrl(settings.get().endpoint);
	}

	protected boolean retryTransient() {
		return !EndpointPolicy.isLoopbackUrl(settings.get().endpoint);
	}

	@Override
	public List<String> listModels() throws LLMException {
		HttpRequest req = request("/models").GET().timeout(Duration.ofSeconds(15)).build();
		String body = http.send(req, new CancellationToken(), 15, retryTransient(), OpenAiCompatibleProvider::statusError).body();
		List<String> all = OpenAiJson.parseModels(body);
		if (isOpenAiHost(EndpointPolicy.hostOf(settings.get().endpoint))) {
			all = all.stream().filter(m -> !m.matches(".*(embed|whisper|tts|dall-e|moderation|davinci|babbage|transcribe|realtime|image|audio).*"))
					.sorted().toList();
		}
		return all;
	}

	@Override
	public ChatResponse chat(ChatRequest request, CancellationToken cancel) throws LLMException {
		Settings s = settings.get();
		String field = maxTokensField != null ? maxTokensField
				: isOpenAiHost(EndpointPolicy.hostOf(s.endpoint)) ? "max_completion_tokens" : "max_tokens";
		boolean temp = sendTemperature;
		for (int adjust = 0;; adjust++) {
			String json = OpenAiJson.toRequestJson(request, field, temp);
			HttpRequest req = request("/chat/completions").header("Content-Type", "application/json")
					.timeout(Duration.ofSeconds(s.requestTimeoutSeconds)).POST(HttpRequest.BodyPublishers.ofString(json)).build();
			long t0 = System.nanoTime();
			try {
				HttpResponse<String> resp = http.send(req, cancel, s.requestTimeoutSeconds, retryTransient(), OpenAiCompatibleProvider::statusError);
				long ms = (System.nanoTime() - t0) / 1_000_000;
				maxTokensField = field;
				sendTemperature = temp;
				return OpenAiJson.parseResponse(resp.body(), ms);
			}
			catch (LLMException e) {
				String m = String.valueOf(e.getMessage()).toLowerCase();
				if (e.getKind() == LLMException.Kind.BAD_REQUEST && adjust < 2) {
					if (m.contains("max_completion_tokens") && field.equals("max_tokens")) {
						field = "max_completion_tokens";
						continue;
					}
					if (m.contains("max_tokens") && field.equals("max_completion_tokens") && !m.contains("max_completion_tokens' is")) {
						field = "max_tokens";
						continue;
					}
					if (m.contains("temperature") && temp) {
						temp = false;
						continue;
					}
				}
				throw e;
			}
		}
	}

	@Override
	public ConnectionReport testConnection(String model) {
		return ConnectionTest.run(this, settings.get().endpoint, model);
	}

	private HttpRequest.Builder request(String path) throws LLMException {
		Settings s = settings.get();
		URI uri;
		try {
			uri = URI.create(s.endpoint + path);
		}
		catch (IllegalArgumentException e) {
			throw new LLMException(LLMException.Kind.BAD_REQUEST, "invalid endpoint URL", 0, e);
		}
		EndpointPolicy.check(s, uri);
		HttpRequest.Builder b = HttpRequest.newBuilder(uri);
		String key = keySource == null ? null : keySource.apply(s);
		if (key != null && !key.isBlank()) {
			b.header("Authorization", "Bearer " + key);
		}
		else if (needsKey(uri.getHost())) {
			throw new LLMException(LLMException.Kind.NO_API_KEY, uri.getHost());
		}
		return b;
	}

	static LLMException statusError(HttpResponse<String> r) {
		String body = r.body() == null ? "" : r.body();
		int st = r.statusCode();
		try {
			JsonElement el = JsonParser.parseString(body);
			if (el.isJsonObject() && el.getAsJsonObject().has("error")) {
				LLMException e = OpenAiJson.errorFrom(el.getAsJsonObject().get("error"), st);
				return refine(e, st);
			}
		}
		catch (RuntimeException e) {
			// fall through
		}
		return refine(OpenAiJson.classify("HTTP " + st + ": " + Text.truncate(body, 300), st), st);
	}

	/** Maps auth and rate-limit statuses regardless of body shape. */
	static LLMException refine(LLMException e, int status) {
		if (status == 401 || status == 403) {
			return new LLMException(LLMException.Kind.AUTH_FAILED, "HTTP " + status, status, e);
		}
		if (status == 429 || status == 529 || status == 503) {
			return new LLMException(LLMException.Kind.RATE_LIMITED, e.getMessage(), status, e);
		}
		return e;
	}
}
