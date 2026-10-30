package xiaozhi.modules.supportmedia.livekit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** JDK-only, no redirects/retries; total deadline includes response-body delivery. */
final class LiveKitHttpTransport implements LiveKitAdapter.Transport,AutoCloseable {
    private final HttpClient client;
    LiveKitHttpTransport(Duration connectTimeout) {
        client=HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Override public LiveKitAdapter.RpcResponse send(URI uri,String authorization,byte[] body,Duration timeout)
            throws IOException,InterruptedException {
        HttpRequest request=HttpRequest.newBuilder(uri).timeout(timeout)
            .header("Authorization",authorization).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        CompletableFuture<HttpResponse<byte[]>> pending=client.sendAsync(request,info->new BoundedBody());
        try {
            HttpResponse<byte[]> response=pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
            return new LiveKitAdapter.RpcResponse(response.statusCode(),response.body());
        } catch(TimeoutException ignored) {
            pending.cancel(true);throw new HttpTimeoutException("LiveKit request deadline");
        } catch(InterruptedException interrupted) {
            pending.cancel(true);throw interrupted;
        } catch(ExecutionException failure) {
            Throwable cause=failure.getCause();
            if(cause instanceof LiveKitFailure sanitized)throw sanitized;
            if(cause instanceof HttpTimeoutException)throw new HttpTimeoutException("LiveKit request deadline");
            throw new IOException("LiveKit transport unavailable");
        }
    }
    @Override public void close() { client.close(); }
    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body=new CompletableFuture<>();
        private final ByteArrayOutputStream output=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription=subscription;subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if(body.isDone())return;
            for(ByteBuffer buffer:buffers) {
                if(buffer.remaining()>LiveKitAdapter.MAX_BODY-output.size()) {
                    subscription.cancel();body.completeExceptionally(new LiveKitFailure(LiveKitFailure.Code.PROTOCOL));return;
                }
                byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);output.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) {
            body.completeExceptionally(new IOException("LiveKit response unavailable"));
        }
        @Override public void onComplete() { body.complete(output.toByteArray()); }
    }
}
