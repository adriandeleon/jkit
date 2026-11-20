package dev.jkit.sources;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * {@link Http} on the JDK's HttpClient. HttpClient ignores HTTPS_PROXY, so the proxy is read
 * from the environment here.
 */
public final class JdkHttp implements Http {
    private static final String USER_AGENT = "jkit/0.1";

    private final HttpClient client;

    public JdkHttp(Map<String, String> env) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15));
        String proxy = firstNonBlank(env.get("JKIT_NETWORK_PROXY"), env.get("HTTPS_PROXY"), env.get("https_proxy"));
        if (proxy != null) {
            URI uri = URI.create(proxy.contains("://") ? proxy : "http://" + proxy);
            int port = uri.getPort() > 0 ? uri.getPort() : 8080;
            builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(), port)));
        }
        this.client = builder.build();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private HttpRequest request(URI uri) {
        return HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
    }

    @Override
    public String get(URI uri) throws IOException {
        try {
            HttpResponse<String> response = client.send(request(uri), HttpResponse.BodyHandlers.ofString());
            check(uri, response.statusCode());
            return response.body();
        } catch (HttpStatusException e) {
            throw e;
        } catch (IOException e) {
            throw new NetworkException(uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    @Override
    public void download(URI uri, Path target, Progress progress) throws IOException {
        try {
            HttpResponse<InputStream> response =
                    client.send(request(uri), HttpResponse.BodyHandlers.ofInputStream());
            check(uri, response.statusCode());
            long total = response.headers().firstValueAsLong("content-length").orElse(-1);
            try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(target)) {
                byte[] buffer = new byte[1 << 16];
                long done = 0;
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    done += n;
                    progress.update(done, total);
                }
            }
        } catch (HttpStatusException e) {
            throw e;
        } catch (IOException e) {
            throw new NetworkException(uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static void check(URI uri, int status) throws IOException {
        if (status / 100 != 2) {
            throw new HttpStatusException(status, uri);
        }
    }
}
