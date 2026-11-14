package net.kigawa.kivra.agent.docker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The k8s proxy URL may be a dotted DNS name; the docker one stays a single label (DockerSpecValidationTest pins that). */
class K8sProxyUrlTest {
    @Test
    fun k8sAcceptsSingleLabelAndDottedHosts() {
        for (url in listOf("http://p:8080", "http://kivra-agent-proxy-svc:8080", "http://kivra-agent-proxy-svc.ns.svc:8080", "http://a.b.c.d.e.f:1")) {
            assertTrue(DockerPatterns.isK8sProxyUrl(url), url)
        }
    }

    @Test
    fun k8sStillRefusesEverythingElse() {
        val bad = listOf(
            "http://h.example:8080/x", "https://h:8080", "http://h:8080\n", "http://h:8080@evil", "http://a b:8080",
            "h:8080", "http://-h:8080", "http://.h:8080", "http://h.:8080", "http://h..x:8080", "http://H.Example:8080",
            "http://a.b.c.d.e.f.g:8080", "http://h:", "http://h:123456", "http://[::1]:8080",
        )
        for (url in bad) assertFalse(DockerPatterns.isK8sProxyUrl(url), url)
    }

    @Test
    fun k8sRefusesAHostOver253Characters() {
        val label = "a".repeat(63)
        assertFalse(DockerPatterns.isK8sProxyUrl("http://$label.$label.$label.$label.$label.$label:8080")) // 6 labels = 383 chars
    }

    @Test
    fun theDockerPatternIsUnchanged() {
        assertFalse(DockerPatterns.PROXY_URL.matches("http://kivra-agent-proxy-svc.ns.svc:8080"))
        assertTrue(DockerPatterns.PROXY_URL.matches("http://p:8080"))
        assertFailsWith<DockerSpecException> { ProxyEnv.javaToolOptions("http://p.ns.svc:8080") }
    }

    @Test
    fun k8sJavaToolOptionsKeepsTheDottedHost() {
        assertEquals(
            "-Dhttp.proxyHost=p.ns.svc -Dhttp.proxyPort=8080 -Dhttps.proxyHost=p.ns.svc -Dhttps.proxyPort=8080",
            ProxyEnv.k8sJavaToolOptions("http://p.ns.svc:8080"),
        )
        assertFailsWith<DockerSpecException> { ProxyEnv.k8sJavaToolOptions("http://p.ns.svc:0") }
        assertFailsWith<DockerSpecException> { ProxyEnv.k8sJavaToolOptions("http://p.ns.svc:8080/x") }
    }
}
