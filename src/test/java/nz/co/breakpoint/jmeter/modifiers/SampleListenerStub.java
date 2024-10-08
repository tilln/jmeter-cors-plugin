package nz.co.breakpoint.jmeter.modifiers;

import java.util.ArrayList;
import java.util.List;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.testelement.AbstractTestElement;

/** Dummy Listener that collects sample results generated by the test instances.
 */
public class SampleListenerStub extends AbstractTestElement implements SampleListener {
    protected List<HTTPSampleResult> results = new ArrayList<>();

    @Override
    public void sampleOccurred(SampleEvent sampleEvent) {
        results.add((HTTPSampleResult) sampleEvent.getResult());
    }

    @Override
    public void sampleStarted(SampleEvent sampleEvent) {}

    @Override
    public void sampleStopped(SampleEvent sampleEvent) {}

    public void clear() {
        super.clear();
        results.clear();
    }
}
