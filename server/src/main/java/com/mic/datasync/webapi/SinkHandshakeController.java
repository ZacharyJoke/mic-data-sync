package com.mic.datasync.webapi;

import com.mic.datasync.sink.SinkHandshakeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sink 握手接口（Authorization: Bearer Token 认证）。
 */
@RestController
@RequestMapping("/api/v1/sink")
public class SinkHandshakeController {

    private final SinkHandshakeService handshakeService;

    public SinkHandshakeController(SinkHandshakeService handshakeService) {
        this.handshakeService = handshakeService;
    }

    /** 握手：Source 侧获取 Sink 身份、能力与批次限制。 */
    @PostMapping("/handshake")
    public SinkHandshakeService.HandshakeResponse handshake() {
        return handshakeService.handshake();
    }
}
