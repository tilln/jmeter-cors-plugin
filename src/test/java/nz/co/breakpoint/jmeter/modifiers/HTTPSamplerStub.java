package nz.co.breakpoint.jmeter.modifiers;

import java.net.URL;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;

/** Dummy sampler that creates a mock preflight response with some relevant headers.
 */
public class HTTPSamplerStub extends HTTPSamplerBase {

    long maxAge = 300;
    String allowHeaders = "x-foo";
    String allowMethods = "*";

    public HTTPSamplerStub() {} // for cloning

    public HTTPSamplerStub(String name, String method, String url) {
        super();
        setName(name);
        setMethod(method);
        setHeaderManager(new HeaderManager());
        setPath(url);
    }

    public void addHeader(String name, String value) {
        getHeaderManager().add(new Header(name, value));
    }

    @Override
    protected HTTPSampleResult sample(URL url,
            String method, boolean areFollowingRedirect, int depth) {
        HTTPSampleResult result = new HTTPSampleResult();

        result.setSampleLabel(url.toString());
        result.setHTTPMethod(method);
        result.setURL(url);

        result.setRequestHeaders(StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(getHeaderManager().getHeaders().iterator(), Spliterator.ORDERED), false)
                .map(headerProp -> {
                    Header header = (Header) headerProp.getObjectValue();
                    return String.format("%s: %s", header.getName(), header.getValue());
                })
                .collect(Collectors.joining("\n"))
        );
        result.setResponseHeaders(String.format(
            "Access-Control-Max-Age: %d\n" +
            "Access-Control-Allow-Headers: %s\n" +
            "Access-Control-Allow-Methods: %s\n",
                maxAge, allowHeaders, allowMethods)
        );

        return result;
    }

    public Object clone() {
        Object clone = super.clone();
        ((HTTPSamplerStub) clone).maxAge = this.maxAge;
        ((HTTPSamplerStub) clone).allowHeaders = this.allowHeaders;
        ((HTTPSamplerStub) clone).allowMethods = this.allowMethods;
        return clone;
    }
}
