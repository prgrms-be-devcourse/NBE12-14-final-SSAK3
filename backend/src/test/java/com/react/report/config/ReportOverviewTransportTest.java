package com.react.report.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportOverviewTransportTest {
    @Test
    @Timeout(10)
    void 서버가_본문을_읽고_응답_전에_끊겨도_같은_POST를_재전송하지_않는다() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                requests.incrementAndGet();
            }
        });
        server.start();
        try {
            var client = RestClient.builder().requestFactory(ReportOverviewConfiguration.transport()).build();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/responses";
            assertThatThrownBy(() -> client.post().uri(url).body(Map.of("background", true))
                    .retrieve().toBodilessEntity()).isInstanceOf(RestClientException.class);
            assertThat(requests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }
}
