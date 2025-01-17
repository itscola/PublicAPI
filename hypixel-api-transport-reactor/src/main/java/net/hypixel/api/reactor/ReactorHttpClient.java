package net.hypixel.api.reactor;

import io.netty.handler.codec.http.HttpResponseStatus;
import net.hypixel.api.http.HypixelHttpClient;
import net.hypixel.api.http.HypixelHttpResponse;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;
import reactor.util.function.Tuple2;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReactorHttpClient implements HypixelHttpClient {
    private final HttpClient httpClient;
    private final UUID apiKey;

    // Marker to reset the request counter and release waiting threads
    private final AtomicBoolean firstRequestReturned = new AtomicBoolean(false);
    // Marker to only schedule a reset clock once on error 429
    private final AtomicBoolean overflowStartedNewClock = new AtomicBoolean(false);

    // Callbacks that will trigger their corresponding requests
    private final ArrayBlockingQueue<RequestCallback> blockingQueue;

    // For shutting down the flux that emits request callbacks
    private final Disposable requestCallbackFluxDisposable;

    private final Object lock = new Object();

    /*
     * How many requests we can send before reaching the limit
     * Starts as 1 so the first request returns and resets this value before allowing other requests to be sent.
     */
    private int actionsLeftThisMinute = 1;

    /**
     * Constructs a new instance of this client using the specified API key.
     *
     * @param apiKey                  the key associated with this connection
     * @param minDelayBetweenRequests minimum time between sending requests (in ms) default is 8
     * @param bufferCapacity          fixed size of blockingQueue
     */
    public ReactorHttpClient(UUID apiKey, long minDelayBetweenRequests, int bufferCapacity) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.create().secure();
        this.blockingQueue = new ArrayBlockingQueue<>(bufferCapacity);

        this.requestCallbackFluxDisposable = Flux.<RequestCallback>generate((synchronousSink) -> {
            try {
                RequestCallback callback = blockingQueue.take();
                // prune skipped/completed requests to avoid counting them
                while (callback.isCanceled()) {
                    callback = blockingQueue.take();
                }

                synchronized (lock) {
                    while (this.actionsLeftThisMinute <= 0) {
                        lock.wait();
                    }

                    actionsLeftThisMinute--;
                }
                synchronousSink.next(callback);
            } catch (InterruptedException e) {
                throw new AssertionError("This should not have been possible", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).delayElements(Duration.ofMillis(minDelayBetweenRequests), Schedulers.boundedElastic()).subscribe(RequestCallback::sendRequest);
    }

    public ReactorHttpClient(UUID apiKey, long minDelayBetweenRequests) {
        this(apiKey, minDelayBetweenRequests, 500);
    }

    public ReactorHttpClient(UUID apiKey, int bufferCapacity) {
        this(apiKey, 8, bufferCapacity);
    }

    public ReactorHttpClient(UUID apiKey) {
        this(apiKey, 8, 500);
    }

    /**
     * Canceling the returned future will result in canceling the request if possible
     */
    @Override
    public CompletableFuture<HypixelHttpResponse> makeRequest(String url) {
        return toHypixelResponseFuture(makeRequest(url, false));
    }

    /**
     * Canceling the returned future will result in canceling the request if possible
     */
    @Override
    public CompletableFuture<HypixelHttpResponse> makeAuthenticatedRequest(String url) {
        return toHypixelResponseFuture(makeRequest(url, true));
    }

    private static CompletableFuture<HypixelHttpResponse> toHypixelResponseFuture(Mono<Tuple2<String, Integer>> result) {
        return result.map(tuple -> new HypixelHttpResponse(tuple.getT2(), tuple.getT1()))
                .toFuture();
    }

    @Override
    public void shutdown() {
        this.requestCallbackFluxDisposable.dispose();
    }

    /**
     * Makes a request to the Hypixel api and returns a {@link Mono<Tuple2<String, Integer>>} containing
     * the response body and status code, canceling this mono will prevent the request from being sent if possible
     *
     * @param path            full url
     * @param isAuthenticated whether to enable authentication or not
     */
    public Mono<Tuple2<String, Integer>> makeRequest(String path, boolean isAuthenticated) {
        return Mono.<Tuple2<String, Integer>>create(sink -> {
            RequestCallback callback = new RequestCallback(path, sink, isAuthenticated, this);

            try {
                this.blockingQueue.put(callback);
            } catch (InterruptedException e) {
                sink.error(e);
                throw new AssertionError("Queue insertion interrupted. This should not have been possible", e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Reads response status and retries error 429 (too many requests)
     * The first request after every limit reset will be used to schedule the next limit reset
     *
     * @param response        the {@link HttpClientResponse} from our request
     * @param requestCallback the callback controlling our request
     * @return whether to return the request body or wait for a retry
     */
    private ResponseHandlingResult handleResponse(HttpClientResponse response, RequestCallback requestCallback) throws InterruptedException {
        if (response.status() == HttpResponseStatus.TOO_MANY_REQUESTS) {
            int timeRemaining = Math.max(1, response.responseHeaders().getInt("ratelimit-reset", 10));

            if (this.overflowStartedNewClock.compareAndSet(false, true)) {
                synchronized (lock) {
                    this.actionsLeftThisMinute = 0;
                }
                resetForFirstRequest(timeRemaining);
            }

            // execute this last to prevent a possible exception from messing up our clock synchronization
            this.blockingQueue.put(requestCallback);
            return new ResponseHandlingResult(false, response.status().code());
        }

        if (this.firstRequestReturned.compareAndSet(false, true)) {
            int timeRemaining = Math.max(1, response.responseHeaders().getInt("ratelimit-reset", 10));
            int requestsRemaining = response.responseHeaders().getInt("ratelimit-remaining", 110);

            synchronized (lock) {
                this.actionsLeftThisMinute = requestsRemaining;
                lock.notifyAll();
            }

            resetForFirstRequest(timeRemaining);
        }
        return new ResponseHandlingResult(true, response.status().code());
    }

    /**
     * Wakes up all waiting threads in the specified amount of seconds
     * (Adds two seconds to account for sync errors in the server).
     *
     * @param timeRemaining how much time is left until the next reset
     */
    private void resetForFirstRequest(int timeRemaining) {
        Schedulers.parallel().schedule(() -> {
            this.firstRequestReturned.set(false);
            this.overflowStartedNewClock.set(false);
            synchronized (lock) {
                this.actionsLeftThisMinute = 1;
                lock.notifyAll();
            }
        }, timeRemaining + 2, TimeUnit.SECONDS);
    }

    /**
     * Controls a request
     */
    private static class RequestCallback {
        private final String url;
        private final MonoSink<Tuple2<String, Integer>> monoSink;
        private final ReactorHttpClient requestRateLimiter;
        private final boolean isAuthenticated;
        private boolean isCanceled = false;

        private RequestCallback(String url, MonoSink<Tuple2<String, Integer>> monoSink, boolean isAuthenticated, ReactorHttpClient requestRateLimiter) {
            this.url = url;
            this.monoSink = monoSink;
            this.requestRateLimiter = requestRateLimiter;
            this.isAuthenticated = isAuthenticated;

            this.monoSink.onCancel(() -> {
                synchronized (this) {
                    this.isCanceled = true;
                }
            });
        }

        public boolean isCanceled() {
            return this.isCanceled;
        }

        private void sendRequest() {
            synchronized (this) {
                if (isCanceled) {
                    synchronized (this.requestRateLimiter.lock) {
                        this.requestRateLimiter.actionsLeftThisMinute++;
                        this.requestRateLimiter.lock.notifyAll();
                    }
                    return;
                }
            }

            (this.isAuthenticated ? requestRateLimiter.httpClient.headers(headers -> headers.add("API-Key", requestRateLimiter.apiKey.toString())) : requestRateLimiter.httpClient).get()
                    .uri(url)
                    .responseSingle((response, body) -> {
                        try {
                            ResponseHandlingResult result = requestRateLimiter.handleResponse(response, this);

                            if (result.allowToPass) {
                                return body.asString().zipWith(Mono.just(result.statusCode));
                            }
                            return Mono.empty();
                        } catch (InterruptedException e) {
                            monoSink.error(e);
                            throw new AssertionError("ERROR: Queue insertion got interrupted, serious problem! (this should not happen!!)", e);
                        }
                    }).subscribe(this.monoSink::success);
        }
    }

    /**
     * Data object
     */
    private static class ResponseHandlingResult {
        public final boolean allowToPass;
        public final int statusCode;

        public ResponseHandlingResult(boolean allowToPass, int statusCode) {
            this.allowToPass = allowToPass;
            this.statusCode = statusCode;
        }
    }
}
