package net.kigawa.kivra.modelgateway

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorClassifierTest {
    @Test fun httpStatusMapping() {
        fun k(s: Int) = ErrorClassifier.fromHttp(s, "").kind
        assertEquals(ErrorKind.RATE_LIMITED, k(429))
        assertEquals(ErrorKind.TRANSIENT, k(500)); assertEquals(ErrorKind.TRANSIENT, k(503)); assertEquals(ErrorKind.TRANSIENT, k(529))
        assertEquals(ErrorKind.AUTH, k(401)); assertEquals(ErrorKind.AUTH, k(403))
        assertEquals(ErrorKind.INVALID_REQUEST, k(400)); assertEquals(ErrorKind.INVALID_REQUEST, k(404)); assertEquals(ErrorKind.INVALID_REQUEST, k(422))
    }

    @Test fun contextExceededIsInvalidRequestWithCode() {
        val e = ErrorClassifier.fromHttp(400, "prompt is too long: 300000 tokens")
        assertEquals(ErrorKind.INVALID_REQUEST, e.kind); assertEquals("CONTEXT_EXCEEDED", e.code)
        assertEquals("CONTEXT_EXCEEDED", ErrorClassifier.fromHttp(413, "").code)
    }

    @Test fun retryAfterIsParsedToMillis() {
        assertEquals(7000L, ErrorClassifier.fromHttp(429, "", "7").retryAfterMs)
        assertEquals(null, ErrorClassifier.fromHttp(429, "", "soon").retryAfterMs)
    }

    @Test fun throwableMapping() {
        class ConnectTimeoutException : IOException("x")
        assertEquals(ErrorKind.TIMEOUT, ErrorClassifier.fromThrowable(ConnectTimeoutException()).kind)
        assertEquals(ErrorKind.TRANSIENT, ErrorClassifier.fromThrowable(IOException("reset")).kind)
        assertEquals(ErrorKind.POLICY_DENIED, ErrorClassifier.fromThrowable(GatewayException(GatewayError(ErrorKind.POLICY_DENIED, "x"))).kind)
    }

    @Test fun secretsAreRedactedAndMessagesBounded() {
        val e = ErrorClassifier.fromHttp(401, "bad key sk-secret-123 " + "z".repeat(2000), secrets = listOf("sk-secret-123"))
        assertFalse(e.message.contains("sk-secret-123")); assertTrue(e.message.length < 600)
    }

    @Test fun fallbackAndRetryPolicyMatchesDesignTable() {
        assertTrue(ErrorKind.RATE_LIMITED.retrySameModel && ErrorKind.RATE_LIMITED.fallback)
        assertTrue(ErrorKind.TRANSIENT.retrySameModel && ErrorKind.TRANSIENT.fallback)
        for (k in listOf(ErrorKind.INVALID_REQUEST, ErrorKind.CONTENT_FILTER, ErrorKind.POLICY_DENIED, ErrorKind.CANCELLED)) assertFalse(k.fallback, "$k")
        assertTrue(ErrorKind.AUTH.fallback && !ErrorKind.AUTH.retrySameModel)
        assertTrue(ErrorKind.CAPABILITY_UNSUPPORTED.fallback)
    }

    @Test fun connectionPhaseFailuresAreProvenNotSent() {
        assertEquals(SendState.NOT_SENT, ErrorClassifier.fromThrowable(ConnectException("refused")).sendState)
        assertEquals(SendState.NOT_SENT, ErrorClassifier.fromThrowable(IOException("dns", UnknownHostException("x"))).sendState)
        assertEquals(SendState.NOT_SENT, ErrorClassifier.fromThrowable(IOException("route", NoRouteToHostException("x"))).sendState)
    }

    @Test fun anyFailureAfterConnectIsMaybeSent() {
        assertEquals(SendState.MAYBE_SENT, ErrorClassifier.fromThrowable(SocketException("reset")).sendState)
        assertEquals(SendState.MAYBE_SENT, ErrorClassifier.fromThrowable(IOException("reset")).sendState)
        assertEquals(SendState.MAYBE_SENT, ErrorClassifier.fromThrowable(IllegalStateException("boom")).sendState)
        assertEquals(SendState.MAYBE_SENT, ErrorClassifier.fromHttp(500, "").sendState)
        assertEquals(SendState.MAYBE_SENT, ErrorClassifier.fromHttp(429, "").sendState)
        assertEquals(SendState.MAYBE_SENT, ErrorClassifier.fromHttp(400, "").sendState)
    }

    @Test fun gatewayErrorsKeepTheirOwnSendState() {
        val e = GatewayError(ErrorKind.AUTH, "no credential", sendState = SendState.NOT_SENT)
        assertEquals(SendState.NOT_SENT, ErrorClassifier.fromThrowable(GatewayException(e)).sendState)
    }

    @Test fun providerErrorTypeIsTakenFromTheFixedSetOnly() {
        val body = """{"type":"error","error":{"type":"invalid_request_error","message":"SECRET-PROMPT-FRAGMENT"}}"""
        assertEquals("invalid_request_error", ErrorClassifier.fromHttp(400, body).providerErrorType)
        assertEquals("rate_limit_error", ErrorClassifier.fromHttp(429, """{"error":{"type":"rate_limit_error"}}""").providerErrorType)
        assertEquals("other", ErrorClassifier.fromHttp(400, """{"error":{"type":"made_up_by_provider"}}""").providerErrorType)
        assertEquals("other", ErrorClassifier.fromHttp(400, """{"error":{}}""").providerErrorType)
        assertEquals(null, ErrorClassifier.fromHttp(502, "<html>bad gateway</html>").providerErrorType)
    }

    @Test fun providerMessageIsNeverKeptInHttpErrors() {
        val body = """{"type":"error","error":{"type":"invalid_request_error","message":"SECRET-PROMPT-FRAGMENT"}}"""
        for (status in listOf(400, 401, 404, 413, 422, 429, 500, 503)) {
            val e = ErrorClassifier.fromHttp(status, body)
            assertFalse(e.message.contains("SECRET-PROMPT-FRAGMENT"), "status $status")
            assertEquals("HTTP $status", e.message)
        }
    }

    @Test fun outcomeUnknownNeverRetriesOrFallsBack() {
        assertFalse(ErrorKind.OUTCOME_UNKNOWN.retrySameModel); assertFalse(ErrorKind.OUTCOME_UNKNOWN.fallback)
    }
}
