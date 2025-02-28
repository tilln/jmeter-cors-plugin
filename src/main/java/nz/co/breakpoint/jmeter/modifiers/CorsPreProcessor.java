package nz.co.breakpoint.jmeter.modifiers;

import java.net.MalformedURLException;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.stream.Collectors;
import org.apache.commons.collections4.map.PassiveExpiringMap;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.jmeter.engine.event.LoopIterationEvent;
import org.apache.jmeter.processor.PreProcessor;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.testbeans.TestBean;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestIterationListener;
import org.apache.jmeter.testelement.ThreadListener;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.threads.ListenerNotifier;
import org.apache.jorphan.collections.SearchByClass;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

public class CorsPreProcessor extends AbstractTestElement
        implements PreProcessor, ThreadListener, TestIterationListener, TestBean {

    private static final long serialVersionUID = 1L;

    public static Logger log = LoggerFactory.getLogger(CorsPreProcessor.class);

    protected transient ListenerNotifier notifier = new ListenerNotifier();
    protected transient List<SampleListener> listeners;

    protected transient PassiveExpiringMap<ImmutableTriple<String, String, String>, Long> preflightCache = // cached value is simply the expiration time in epoch millis
            new PassiveExpiringMap<>((PassiveExpiringMap.ExpirationPolicy<ImmutableTriple<String, String, String>, Long>)
                    (key, value) -> value);

    public static final String PREFLIGHT_LABEL_SUFFIX = "preflightLabelSuffix";
    public static final String CLEAR_EACH_ITERATION = "clearEachIteration";
    public static final String DEFAULT_CACHE_EXPIRY = "defaultCacheExpiry";

    public static final String PREFLIGHT_LABEL_SUFFIX_DEFAULT = "-preflight";
    public static final String allowedMethods = "GET|HEAD|POST";
    public static final String forbiddenMethods = "CONNECT|TRACE|TRACK";
    public static final String methodOverrideHeaders = "x-http-method|x-http-method-override|x-method-override";
    public static final String OPTIONS = "OPTIONS";
    public static final String ORIGIN = "Origin";
    public static final String AUTHORIZATION = "Authorization";
    public static final String ACCEPT = "Accept";
    public static final String ACCESS_CONTROL_REQUEST_METHOD = "Access-Control-Request-Method";
    public static final String ACCESS_CONTROL_REQUEST_HEADERS = "Access-Control-Request-Headers";
    public static final Pattern MAX_AGE_HEADER_PATTERN = Pattern.compile("(?i)\\bAccess-Control-Max-Age: (\\V*)");
    public static final Pattern ALLOW_HEADERS_HEADER_PATTERN = Pattern.compile("(?i)\\bAccess-Control-Allow-Headers: (\\V*)");
    public static final Pattern ALLOW_METHODS_HEADER_PATTERN = Pattern.compile("(?i)\\bAccess-Control-Allow-Methods: (\\V*)");

    public static final Map<String, String> safeListedHeaders = new HashMap<String, String>() {{
            put("accept", ".*");
            put("accept-language", ".*");
            put("content-language", ".*");
            put("content-type", "(application/x-www-form-urlencoded|multipart/form-data|text/plain).*");
            put("range", "bytes=([0-9]+-[0-9]*|-[0-9]+)");
    }};

    public static final String forbiddenHeaders = String.join("|",
            "accept-charset",
            "accept-encoding",
            "access-control-request-headers",
            "access-control-request-method",
            "connection",
            "content-length",
            "cookie",
            "cookie2",
            "date",
            "dnt",
            "expect",
            "host",
            "keep-alive",
            "origin",
            "proxy-.*",
            "referer",
            "sec-.*",
            "set-cookie",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "via"
    );

    public static final String nonWildcardHeaders = String.join("|",
            "authorization"
    );

    @Override
    public void process() {
        JMeterContext context = getThreadContext();
        Sampler sampler = context.getCurrentSampler();

        if (!(sampler instanceof HTTPSamplerBase)) {
            return;
        }

        HTTPSamplerBase httpSampler = (HTTPSamplerBase) sampler;
        final String method = httpSampler.getMethod();
        final String url;
        try {
            url = String.valueOf(httpSampler.getUrl());
        } catch (MalformedURLException e) {
            log.error("Invalid sampler URL", e);
            return;
        }
        if (getHeaders(httpSampler.getHeaderManager()).noneMatch(h -> ORIGIN.equalsIgnoreCase(h.getName()))) {
            log.debug("No Origin header present, skipping.");
            return;
        }
        Collection<String> preflightHeaders = getPreflightHeaders(httpSampler.getHeaderManager());

        if (method.matches(allowedMethods) && preflightHeaders.isEmpty()) return; // simple request

        HTTPSamplerBase preflight = (HTTPSamplerBase) sampler.clone();
        HeaderManager hm = preflight.getHeaderManager();
        hm.removeHeaderNamed(AUTHORIZATION);
        hm.removeHeaderNamed(ACCEPT);
        hm.add(new Header(ACCEPT, "*/*"));
        hm.add(new Header(ACCESS_CONTROL_REQUEST_METHOD, method));
        hm.add(new Header(ACCESS_CONTROL_REQUEST_HEADERS, String.join(",", preflightHeaders)));

        preflight.setMethod(OPTIONS);
        preflight.setName(preflight.getName() + getPreflightLabelSuffix());
        preflight.setThreadContext(context);
        preflight.setThreadName(context.getThread().getThreadName());

        if (isInPreflightCache(url, method, preflightHeaders)) {
            log.debug("Preflight still cached, skipping.");
            return;
        }
        SampleResult result = preflight.sample();
        result.setThreadName(context.getThread().getThreadName());
        addToPreflightCache(result);
        notifier.notifyListeners(new SampleEvent(result, context.getThreadGroup().getName()), listeners);
    }

    /**
     * @param h JMeter Header of the actual HTTP request
     * @return true iff the header is one that requires a preflight request
     */
    public static boolean isPreflightHeader(Header h) {
        final String name = h.getName().toLowerCase();

        if (name.matches(forbiddenHeaders)) return false;

        if (name.matches(methodOverrideHeaders)
                && h.getValue().toUpperCase().matches(forbiddenMethods))
            return false;

        String safePattern = safeListedHeaders.get(name);
        if (safePattern != null) {
            return !h.getValue().matches(safePattern);
        }
        return true;
    }

    public static Stream<Header> getHeaders(HeaderManager hm) {
        return hm == null || hm.getHeaders() == null ? Stream.empty()
                : StreamSupport.stream(Spliterators.spliteratorUnknownSize(hm.getHeaders().iterator(), 0), false)
                .map(JMeterProperty::getObjectValue)
                .map(Header.class::cast);
    }

    /**
     * @param hm JMeter HeaderManager of the actual HTTP request
     * @return list of header names to be sent in the
     * "Access-Control-Request-Headers" preflight request header
     */
    public static Collection<String> getPreflightHeaders(HeaderManager hm) {
        return getHeaders(hm)
                .filter(CorsPreProcessor::isPreflightHeader)
                .map(Header::getName)
                .collect(Collectors.toList());
    }

    protected boolean isInPreflightCache(String url, String method, Collection<String> headers) {
        return (preflightCache.containsKey(ImmutableTriple.of(url, method.toUpperCase(), "")) ||
                preflightCache.containsKey(ImmutableTriple.of(url, "*", ""))
        ) && headers.stream().map(String::toLowerCase).allMatch(header ->
                preflightCache.containsKey(ImmutableTriple.of(url, "", header)) ||
                preflightCache.containsKey(ImmutableTriple.of(url, "", "*"))
                        && !header.matches(nonWildcardHeaders));
    }

    protected void addToPreflightCache(SampleResult result) {
        long maxAge = getMaxAge(result);
        log.debug("Caching \"{}\" for {} seconds", result.getSampleLabel(), maxAge);
        long expiry = Instant.now().plusSeconds(maxAge).toEpochMilli();
        final String url = result.getUrlAsString();
        getAllowHeaders(result).forEach(header ->
                preflightCache.put(ImmutableTriple.of(url, "", header.toLowerCase()), expiry));
        getAllowMethods(result).forEach(method ->
                preflightCache.put(ImmutableTriple.of(url, method.toUpperCase(), ""), expiry));
    }

    /**
     * @param result JMeter SampleResult of a preflight request
     * @return duration (in seconds) until preflight expiry as per Access-Control-Max-Age response header,
     * or the default of 5 seconds if the header is not received
     */
    public long getMaxAge(SampleResult result) {
        final Matcher m = MAX_AGE_HEADER_PATTERN.matcher(result.getResponseHeaders());
        return m.find() ? Long.parseLong(m.group(1)) : getDefaultCacheExpiry();
    }

    public List<String> getAllowHeaders(SampleResult result) {
        final Matcher m = ALLOW_HEADERS_HEADER_PATTERN.matcher(result.getResponseHeaders());
        return m.find() ? Arrays.asList(m.group(1).split("[\\s,]+")) : Collections.emptyList();
    }

    public List<String> getAllowMethods(SampleResult result) {
        final Matcher m = ALLOW_METHODS_HEADER_PATTERN.matcher(result.getResponseHeaders());
        return m.find() ? Arrays.asList(m.group(1).split("[\\s,]+")) : Collections.emptyList();
    }

    /** When the JMeter Thread is started, find all SampleListeners in Test Plan, so they can be notified
     * when the preflight request is made.
     */
    @Override
    public void threadStarted() {
        SearchByClass<SampleListener> listenersSearch = new SearchByClass<>(SampleListener.class);
        getThreadContext().getThread().getTestTree().traverse(listenersSearch);
        listeners = listenersSearch.getSearchResults().stream().distinct().collect(Collectors.toList());
    }

    @Override
    public void threadFinished() {}

    @Override
    public void testIterationStart(LoopIterationEvent event) {
        boolean sameUser = getThreadContext().getVariables().isSameUserOnNextIteration();
        if (getClearEachIteration().isEmpty() && !sameUser
                || "true".equalsIgnoreCase(getClearEachIteration())) {
            log.debug("Clearing preflight cache");
            preflightCache.clear();
        }
    }

    public String getPreflightLabelSuffix() { return getPropertyAsString(PREFLIGHT_LABEL_SUFFIX); }
    public void setPreflightLabelSuffix(String suffix) { setProperty(PREFLIGHT_LABEL_SUFFIX, suffix); }
    public long getDefaultCacheExpiry() { return getPropertyAsLong(DEFAULT_CACHE_EXPIRY); }
    public void setDefaultCacheExpiry(long seconds) { setProperty(DEFAULT_CACHE_EXPIRY, seconds); }
    public String getClearEachIteration() { return getPropertyAsString(CLEAR_EACH_ITERATION); }
    public void setClearEachIteration(String clear) { setProperty(CLEAR_EACH_ITERATION, clear); }

}
