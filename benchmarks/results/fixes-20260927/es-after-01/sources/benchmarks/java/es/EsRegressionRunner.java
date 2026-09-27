import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import java.io.File;
import java.io.PrintWriter;
import java.util.*;

/** Uses the project's cached Jupiter engine and includes its unchanged search tests. */
public class EsRegressionRunner {
    public static void main(String[] args) throws Exception {
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        LauncherFactory.create(LauncherConfig.builder().enableTestEngineAutoRegistration(false)
            .enableTestExecutionListenerAutoRegistration(false).addTestEngines(new JupiterTestEngine()).build())
          .execute(LauncherDiscoveryRequestBuilder.request().selectors(
            DiscoverySelectors.selectClass("SearchFailureRegressionTest"),
            DiscoverySelectors.selectClass("com.github.paicoding.forum.service.article.service.search.impl.ArticleSearchServiceImplTest")
        ).build(), listener);
        TestExecutionSummary summary = listener.getSummary();
        summary.printTo(new PrintWriter(System.out, true));
        summary.printFailuresTo(new PrintWriter(System.out, true));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", summary.getTestsFoundCount());
        result.put("passed", summary.getTestsSucceededCount());
        result.put("failed", summary.getTestsFailedCount());
        result.put("skipped", summary.getTestsSkippedCount());
        result.put("durationMs", summary.getTimeFinished() - summary.getTimeStarted());
        result.put("concurrency", 1);
        result.put("scope", "loopback HTTP stub; mocked DAO and JDBC; transaction rollback not exercised");
        List<Map<String, String>> failures = new ArrayList<>();
        for (TestExecutionSummary.Failure failure : summary.getFailures()) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("test", failure.getTestIdentifier().getDisplayName());
            row.put("exception", failure.getException().toString());
            failures.add(row);
        }
        result.put("failures", failures);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(new File(args[0]), result);
        if (summary.getTestsFoundCount() != 16 || summary.getTotalFailureCount() != 0 || summary.getTestsSkippedCount() != 0) System.exit(1);
    }
}
