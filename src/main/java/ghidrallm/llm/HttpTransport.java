package ghidrallm.llm;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Function;

import ghidrallm.util.CancellationToken;

/**
 * Cancellable HTTP with sane timeouts. Local endpoints bypass any system proxy; remote endpoints use the
 * JVM's default proxy selection (corporate proxies). Transient provider errors (429/5xx/529) are retried a
 * few times with back-off when {@code retry} is set (remote providers only).
 */
final class HttpTransport {
	private static final Set<Integer> RETRYABLE = Set.of(429, 500, 502, 503, 504, 529);
	private static final int MAX_RETRIES = 2;

	private final HttpClient local = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
			.proxy(ProxySelector.of(null)).version(HttpClient.Version.HTTP_1_1).build();
	private final HttpClient remote = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
			.version(HttpClient.Version.HTTP_1_1).build();

	HttpResponse<String> send(HttpRequest req, CancellationToken cancel, int timeoutSec, boolean retry,
			Function<HttpResponse<String>, LLMException> errorMapper) throws LLMException {
		HttpClient client = EndpointPolicy.isLoopbackHost(req.uri().getHost()) ? local : remote;
		for (int attempt = 0;; attempt++) {
			HttpResponse<String> r = once(client, req, cancel, timeoutSec);
			if (r.statusCode() / 100 == 2) {
				return r;
			}
			if (retry && attempt < MAX_RETRIES && RETRYABLE.contains(r.statusCode())) {
				sleep(delayMillis(r, attempt), cancel);
				continue;
			}
			throw errorMapper.apply(r);
		}
	}

	private static long delayMillis(HttpResponse<String> r, int attempt) {
		long ms = 1500L << attempt;
		String ra = r.headers().firstValue("retry-after").orElse(null);
		if (ra != null) {
			try {
				ms = Math.max(ms, Long.parseLong(ra.trim()) * 1000L);
			}
			catch (NumberFormatException e) {
				// ignore HTTP-date form
			}
		}
		return Math.min(ms, 20_000L);
	}

	private static void sleep(long ms, CancellationToken cancel) throws LLMException {
		long end = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < end) {
			if (cancel.isCancelled()) {
				throw new LLMException(LLMException.Kind.CANCELLED, null);
			}
			try {
				Thread.sleep(100);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new LLMException(LLMException.Kind.CANCELLED, null);
			}
		}
	}

	private HttpResponse<String> once(HttpClient client, HttpRequest req, CancellationToken cancel, int timeoutSec)
			throws LLMException {
		CompletableFuture<HttpResponse<String>> f = client.sendAsync(req, HttpResponse.BodyHandlers.ofString());
		Runnable onCancel = () -> f.cancel(true);
		cancel.onCancel(onCancel);
		try {
			return f.get(timeoutSec + 5L, TimeUnit.SECONDS);
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
			if (c instanceof ConnectException || c instanceof HttpConnectTimeoutException || c instanceof IOException) {
				throw new LLMException(LLMException.Kind.UNREACHABLE, String.valueOf(c.getMessage()), 0, c);
			}
			throw new LLMException(LLMException.Kind.UNREACHABLE, String.valueOf(c), 0, c);
		}
		finally {
			cancel.removeListener(onCancel);
		}
	}
}
