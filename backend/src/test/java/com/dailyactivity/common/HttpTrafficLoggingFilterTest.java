package com.dailyactivity.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HttpTrafficLoggingFilterTest {
    private final HttpTrafficLoggingFilter filter = new HttpTrafficLoggingFilter(new ObjectMapper());

    @Test
    void redactsCredentialsAndPersonalDataFromJsonLogs() {
        String body = "{\"orderNo\":\"O-100\",\"password\":\"secret\",\"email\":\"customer@example.com\",\"nested\":{\"accessToken\":\"jwt\"}}";

        String logged = filter.bodyForLog(body.getBytes(StandardCharsets.UTF_8), "application/json");

        assertThat(logged)
                .contains("\"orderNo\":\"O-100\"")
                .contains("\"password\":\"***\"")
                .contains("\"email\":\"***\"")
                .contains("\"accessToken\":\"***\"")
                .doesNotContain("customer@example.com", "\"jwt\"", "\"secret\"");
    }

    @Test
    void marksNonJsonResponseWithoutLoggingItsContent() {
        assertThat(filter.bodyForLog("csv-content".getBytes(StandardCharsets.UTF_8), "text/csv"))
                .isEqualTo("<not-json bytes=11>");
    }

    @Test
    void preservesJsonResponseBodyAfterLogging() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/customer/orders");
        request.setContentType("application/json");
        request.setContent("{\"productId\":1}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            res.setContentType("application/json");
            res.getWriter().write("{\"id\":1}");
        });

        assertThat(response.getContentAsString()).isEqualTo("{\"id\":1}");
    }
}
