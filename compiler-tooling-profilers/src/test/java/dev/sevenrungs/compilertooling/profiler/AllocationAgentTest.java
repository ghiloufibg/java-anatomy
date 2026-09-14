package dev.sevenrungs.compilertooling.profiler;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Forks a real child JVM under each implementation, proving the real agent path end to end - not
 * just the in-process {@link Instrumenter} call. A clean exit from each run is also the JVM
 * verifier's pass/fail signal (JVMS §4.10): a {@code VerifyError} on class definition would abort
 * the target's startup outright, so there is no separate "run it through a verifier" step needed.
 * See {@link AgentProcessSupport}'s Javadoc for the real {@code VerifyError} this design caught - a
 * genuine bug in {@link ByteBuddyInstrumenter}, not an in-process-only artifact.
 */
class AllocationAgentTest {

  @ParameterizedTest
  @ValueSource(strings = {"classfile", "asm", "bytebuddy"})
  @Timeout(30)
  void instrumentsWorkloadAndReportsRealAllocationCounts(String implementation) throws Exception {
    String output = AgentProcessSupport.runWorkloadUnderAgent(implementation);

    assertTrue(
        output.contains("workload complete"),
        "[" + implementation + "] instrumentation must not change Workload's own behavior");
    assertTrue(
        output.contains(
            "dev/sevenrungs/compilertooling/profiler/Workload.allocateObject:java/lang/Object"),
        "["
            + implementation
            + "] expected the object-allocation site in the report; output: "
            + output);
    assertTrue(
        output.contains("dev/sevenrungs/compilertooling/profiler/Workload.allocateMultiArray:[[I"),
        "[" + implementation + "] expected the multi-array site in the report; output: " + output);
  }
}
