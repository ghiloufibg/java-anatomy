package dev.sevenrungs.compilertooling.profiler.attribution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.sevenrungs.compilertooling.profiler.attribution.AllocationSites.Site;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AllocationSitesTest {

  @Test
  void parsesTheAgentsReportAndIgnoresEverythingElse() {
    String output =
        """
        HISTOGRAM-END
        dev/sevenrungs/app/Orders.load:java/util/HashMap = 250000
        dev/sevenrungs/app/Orders.load:dev/sevenrungs/app/Orders$Line = 750000
        dev/sevenrungs/app/Codec.encode:byte[] = 12
        some other line = not a count
        """;
    assertEquals(
        List.of(
            new Site("dev.sevenrungs.app.Orders", "load", "java.util.HashMap", 250_000),
            new Site(
                "dev.sevenrungs.app.Orders", "load", "dev.sevenrungs.app.Orders$Line", 750_000),
            new Site("dev.sevenrungs.app.Codec", "encode", "[B", 12)),
        AllocationSites.parse(output).sites());
  }

  @ParameterizedTest
  @CsvSource({
    "java/util/HashMap, java.util.HashMap",
    "dev/sevenrungs/app/Orders$Line, dev.sevenrungs.app.Orders$Line",
    "byte[], [B",
    "long[], [J",
    "java/lang/Object[], [Ljava.lang.Object;",
    "[I[], [[I",
    "[[Ljava/lang/String;, [[Ljava.lang.String;"
  })
  void agentTypeLabelsBecomeHistogramClassNames(String agentType, String histogramName) {
    assertEquals(histogramName, AllocationSites.histogramName(agentType));
  }
}
