package nz.co.breakpoint.jmeter.modifiers;

import kg.apc.emulators.TestJMeterUtils;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.JMeterContextService;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import java.util.Arrays;

import static org.junit.Assert.*;

public class TestCorsPreProcessor {
    protected JMeterContext context;
    protected CorsPreProcessor instance;
    protected HTTPSamplerStub sampler;
    protected SampleListenerStub resultsListener = new SampleListenerStub();

    @BeforeClass
    public static void setUpClass() {
        TestJMeterUtils.createJmeterEnv();
    }

    @Before
    public void setUp() {
        sampler = new HTTPSamplerStub("GET it", "GET", "https://target.co.nz");
        sampler.addHeader("Origin", "https://origin.co.nz");
        sampler.addHeader("X-foo", "bar");

        context = JMeterContextService.getContext();
        context.setCurrentSampler(sampler);

        instance = new CorsPreProcessor();
        instance.setThreadContext(context);
        instance.setPreflightLabelSuffix("-preflight");
        instance.listeners = Arrays.asList(resultsListener);
        resultsListener.clear();
    }

    @Test
    public void itShouldRecognisePreflightHeaders() {
        assertTrue(CorsPreProcessor.isPreflightHeader(new Header("Api-Version", "1.0")));
        assertTrue(CorsPreProcessor.isPreflightHeader(new Header("Content-Type", "application/xml")));
        assertTrue(CorsPreProcessor.isPreflightHeader(new Header("X-HTTP-Method", "PUT")));
        assertTrue(CorsPreProcessor.isPreflightHeader(new Header("Range", "bytes=0-1023, -512")));

        assertFalse(CorsPreProcessor.isPreflightHeader(new Header("Accept", "*/*")));
        assertFalse(CorsPreProcessor.isPreflightHeader(new Header("Content-Type", "application/x-www-form-urlencoded")));
        assertFalse(CorsPreProcessor.isPreflightHeader(new Header("Origin", "https://apache.org")));
        assertFalse(CorsPreProcessor.isPreflightHeader(new Header("X-HTTP-Method", "CONNECT")));
        assertFalse(CorsPreProcessor.isPreflightHeader(new Header("Range", "bytes=0-1023")));
    }

    @Test
    public void itShouldExtractAccessControlHeaders() {
        SampleResult prev = SampleResult.createTestSample(1);
        prev.setResponseHeaders(
                "Access-Control-Max-Age: 123\n" +
                "Access-Control-Allow-Headers: content-type, User-Agent, X-FOOBAR\n" +
                "Access-Control-Allow-Methods: CHICKEN, HEAD\n"
        );
        assertEquals(123L, instance.getMaxAge(prev));
        assertEquals(Arrays.asList("content-type", "User-Agent", "X-FOOBAR"), instance.getAllowHeaders(prev));
        assertEquals(Arrays.asList("CHICKEN", "HEAD"), instance.getAllowMethods(prev));

        prev.setResponseHeaders(
                "Access-Control-Allow-Methods: *\n"
        );
        assertEquals(instance.getDefaultCacheExpiry(), instance.getMaxAge(prev));
        assertEquals(Arrays.asList(), instance.getAllowHeaders(prev));
        assertEquals(Arrays.asList("*"), instance.getAllowMethods(prev));
    }

    @Test
    public void itShouldIgnoreSimpleRequests() {
        sampler.getHeaderManager().removeHeaderNamed("X-foo");
        instance.process();
        assertEquals(0, resultsListener.results.size());
    }

    @Test
    public void itShouldCreatePreflightRequests() {
        instance.process();
        assertEquals(1, resultsListener.results.size());
        HTTPSampleResult result = resultsListener.results.get(0);
        assertEquals("OPTIONS", result.getHTTPMethod());
        assertEquals("GET it-preflight", result.getSampleLabel());
    }

    @Test
    public void itShouldCachePreflightRequests() {
        instance.process();
        instance.process();
        assertEquals(1, resultsListener.results.size());
    }

    @Test
    public void itShouldExpireCachedRequests() {
        sampler.maxAge = 0; // make cache expire immediately
        instance.process();
        instance.process();
        assertEquals(2, resultsListener.results.size());
    }

    @Test
    public void itShouldRemoveAuthHeaderFromPreflight() {
        sampler.addHeader("Authorization", "something secret");
        instance.process();
        assertEquals(1, resultsListener.results.size());
        HTTPSampleResult result = resultsListener.results.get(0);
        assertFalse(result.getRequestHeaders().contains("Authorization:"));
    }

}