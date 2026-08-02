package com.mic.datasync.sink;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Sink 批次认证检查：比对 Source 端令牌与 Sink 端令牌，可选执行真实握手。
 */
@Service
public class SinkAuthCheckService {

    private static final String PREFIX = "mic_";

    private final SinkTokenService sinkTokenService;
    private final SourceSinkTokenService sourceTokenService;
    private final int serverPort;
    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public SinkAuthCheckService(SinkTokenService sinkTokenService,
                                SourceSinkTokenService sourceTokenService,
                                @Value("${server.port:19090}") int serverPort) {
        this.sinkTokenService = sinkTokenService;
        this.sourceTokenService = sourceTokenService;
        this.serverPort = serverPort;
    }

    public SinkAuthCheck check(String sinkUrl) {
        String sourceToken = sourceTokenService.resolve();
        Optional<String> sinkToken = sinkTokenService.currentToken();
        boolean ok = !sourceToken.isBlank()
                && sinkToken.map(token -> token.equals(sourceToken)).orElse(false);
        String handshake = null;
        String target = resolveSinkUrl(sinkUrl);
        if (ok && target != null) {
            handshake = callHandshake(stripTrailingSlash(target), sourceToken);
        }
        return new SinkAuthCheck(
                ok,
                sourceTokenService.configuredFromDb(),
                sourceTokenService.display().orElse(""),
                mask(sinkToken.orElse("")),
                handshake);
    }

    private String resolveSinkUrl(String provided) {
        if (provided != null && !provided.isBlank()) {
            return provided;
        }
        return serverPort > 0 ? "http://127.0.0.1:" + serverPort : null;
    }

    private String callHandshake(String sinkUrl, String token) {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(sinkUrl + "/api/v1/sink/handshake"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return "HTTP " + response.statusCode();
        } catch (Exception ex) {
            return "unreachable";
        }
    }

    private String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String mask(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        int tail = Math.min(4, token.length() - PREFIX.length());
        return PREFIX + "****" + token.substring(token.length() - tail);
    }

    public record SinkAuthCheck(
            boolean ok,
            boolean sourceFromDb,
            String sourceDisplay,
            String sinkMasked,
            String handshake) {
    }
}
