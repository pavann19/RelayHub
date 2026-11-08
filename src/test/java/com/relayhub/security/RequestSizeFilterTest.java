package com.relayhub.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class RequestSizeFilterTest {
    @Test void rejectsOversizedKnownAndUnknownLengthBodies() throws Exception {
        for (boolean unknown : new boolean[]{false, true}) {
            var request = new MockHttpServletRequest("POST", "/api/v1/events") {
                @Override public long getContentLengthLong() { return unknown ? -1 : super.getContentLengthLong(); }
            };
            request.setContent(new byte[RequestSizeFilter.MAX_BYTES + 1]);
            var response = new MockHttpServletResponse();
            new RequestSizeFilter().doFilter(request, response, (a,b) -> fail("Must not deserialize oversized body"));
            assertEquals(413, response.getStatus());
        }
    }
    @Test void preservesBodyAtLimit() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/events");
        request.setContent(new byte[RequestSizeFilter.MAX_BYTES]);
        new RequestSizeFilter().doFilter(request, new MockHttpServletResponse(), (a,b) ->
                assertEquals(RequestSizeFilter.MAX_BYTES, a.getInputStream().readAllBytes().length));
    }
}
