package ghidrallm.llm;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;

import ghidrallm.config.Settings;
import ghidrallm.util.CancellationToken;

/**
 * LM Studio's OpenAI-compatible local server ({@code /v1/models}, {@code /v1/chat/completions}).
 * Only loopback endpoints are accepted unless the user explicitly allows otherwise.
 */
public class LMStudioProvider implements LLMProvider {

	private final Supplier<Settings> settings;
	private final HttpClient http;

	public LMStudioProvider(Supplier<Settings> settings) {
		this.settings = settings;
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.proxy(ProxySelector.of(null))          // never route local traffic through a proxy
				.version(HttpClient.Version.HTTP_1_1)
				.build();
	}

	@Override
	public String id() {
		return "lmstudio";
	}

	@Override
	public List<String> listModels() throws LLMException {
		HttpRequest req = base("/models").GET().timeout(Duration.ofSeconds(10)).build();
		String body = send(req, new CancellationToken(), 10).body();
		return OpenAiJson.parseModels(body);
	}

	@Override
	public ChatResponse chat(ChatRequest request, CancellationToken cancel) throws LLMException {
		Settings s = settings.get();
		String json = OpenAiJson.toRequestJson(request);
		HttpRequest req = base("/chat/completions")
				.header("Content-Type", "application/json")
				.timeout(Duration.ofSeconds(s.requestTimeoutSeconds))
				.POST(HttpRequest.BodyPublishers.ofString(json))
				.build();
		long t0 = System.nanoTime();
		HttpResponse<String> resp = send(req, cancel, s.requestTimeoutSeconds);
		long ms = (System.nanoTime() - t0) / 1_000_000;
		if (resp.statusCode() / 100 != 2) {
			throw statusError(resp);
		}
		return OpenAiJson.parseResponse(resp.body(), ms);
	}

	@Override
	public ConnectionReport testConnection(String model) {
		ConnectionReport r = new ConnectionReport();
		List<String> models;
		try {
			models = listModels();
			r.add("LM Studio reachable", true, settings.get().endpoint);
			r.setModels(models);
		}
		catch (LLMException e) {
			r.add("LM Studio reachable", false, e.getMessage());
			return r;
		}
		String use = model;
		if (models.isEmpty()) {
			r.add("Model available", false, LLMException.Kind.NO_MODEL.help);
			return r;
		}
		if (use == null || use.isBlank()) {
			use = models.get(0);
		}
		boolean listed = models.contains(use);
		r.add("Model available", listed,
			listed ? use : "'" + use + "' is not loaded; available: " + String.join(", ", models));
		if (!listed) {
			return r;
		}
		try {
			ChatResponse resp = chat(new ChatRequest(use,
				List.of(ChatMessage.user("Reply with the single word: ok")), null, 0.0, 16),
				new CancellationToken());
			r.add("Chat completion works", true,
				resp.elapsedMillis() + " ms, reply: " + ghidrallm.util.Text.oneLine(resp.content(), 40));
		}
		catch (LLMException e) {
			r.add("Chat completion works", false, e.getMessage());
		}
		return r;
	}

	private HttpRequest.Builder base(String path) throws LLMException {
		Settings s = settings.get();
		URI uri;
		try {
			uri = URI.create(s.endpoint + path);
		}
		catch (IllegalArgumentException e) {
			throw new LLMException(LLMException.Kind.BAD_REQUEST, "invalid endpoint URL", 0, e);
		}
		if (!s.allowNonLoopbackEndpoint && !isLoopback(uri.getHost())) {
			throw new LLMException(LLMException.Kind.ENDPOINT_NOT_LOCAL, uri.getHost());
		}
		return HttpRequest.newBuilder(uri);
	}

	static boolean isLoopback(String host) {
		if (host == null) {
			return false;
		}
		if (host.equalsIgnoreCase("localhost")) {
			return true;
		}
		try {
			// Only resolve literal IPs; do not trigger DNS lookups for arbitrary names.
			if (!host.matches("[0-9.]+|\\[?[0-9a-fA-F:]+\\]?")) {
				return false;
			}
			return InetAddress.getByName(host).isLoopbackAddress();
		}
		catch (UnknownHostException e) {
			return false;
		}
	}

	private HttpResponse<String> send(HttpRequest req, CancellationToken cancel, int timeoutSec)
			throws LLMException {
		CompletableFuture<HttpResponse<String>> f =
			http.sendAsync(req, HttpResponse.BodyHandlers.ofString());
		Runnable onCancel = () -> f.cancel(true);
		cancel.onCancel(onCancel);
		try {
			HttpResponse<String> r = f.get(timeoutSec + 5L, TimeUnit.SECONDS);
			if (r.statusCode() / 100 != 2) {
				throw statusError(r);
			}
			return r;
		}
		catch (CancellationException e) {
			throw new LLMException(LLMException.Kind.CANCELLED, null);
		}
		catch (TimeoutException e) {
			f.cancel(true);
			throw new LLMException(LLMException.Kind.TIMEOUT, timeoutSec + "s", 0, e);
		}
		catch (InterruptedException e) {
			f.cancel(true);
			Thread.currentThread().interrupt();
			throw new LLMException(LLMException.Kind.CANCELLED, null);
		}
		catch (ExecutionException e) {
			Throwable c = e.getCause();
			if (cancel.isCancelled()) {
				throw new LLMException(LLMException.Kind.CANCELLED, null);
			}
			if (c instanceof HttpTimeoutException) {
				throw new LLMException(LLMException.Kind.TIMEOUT, timeoutSec + "s", 0, c);
			}
			if (c instanceof ConnectException || c instanceof HttpConnectTimeoutException ||
				c instanceof IOException) {
				throw new LLMException(LLMException.Kind.UNREACHABLE, String.valueOf(c.getMessage()), 0,
					c);
			}
			throw new LLMException(LLMException.Kind.UNREACHABLE, String.valueOf(c), 0, c);
		}
		finally {
			cancel.removeListener(onCancel);
		}
	}

	private static LLMException statusError(HttpResponse<String> r) {
		String body = r.body() == null ? "" : r.body();
		try {
			var el = com.google.gson.JsonParser.parseString(body);
			if (el.isJsonObject() && el.getAsJsonObject().has("error")) {
				return OpenAiJson.errorFrom(el.getAsJsonObject().get("error"), r.statusCode());
			}
		}
		catch (RuntimeException e) {
			// fall through to generic
		}
		return OpenAiJson.classify("HTTP " + r.statusCode() + ": " + ghidrallm.util.Text.truncate(body, 300),
			r.statusCode());
	}
}
