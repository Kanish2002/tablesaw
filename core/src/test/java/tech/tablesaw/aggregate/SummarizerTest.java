/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tech.tablesaw.aggregate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static tech.tablesaw.aggregate.AggregateFunctions.countTrue;
import static tech.tablesaw.aggregate.AggregateFunctions.mean;
import static tech.tablesaw.aggregate.AggregateFunctions.sum;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tech.tablesaw.api.BooleanColumn;
import tech.tablesaw.api.LongColumn;
import tech.tablesaw.api.NumericColumn;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

class SummarizerTest {

  @ParameterizedTest
  @CsvSource({"metricf1, metricf2", "metricf2, metricf1"})
  void testSingleFunctionPreservesRequestedColumnOrder(String first, String second) {
    Table result = source().summarize(List.of(first, second), sum).by("id");

    assertEquals(
        List.of("id", "Sum [" + first + "]", "Sum [" + second + "]"), result.columnNames());
    result = result.sortOn("id");
    assertEquals(List.of(3.0, 7.0), result.doubleColumn("Sum [metricf1]").asList());
    assertEquals(List.of(30.0, 70.0), result.doubleColumn("Sum [metricf2]").asList());
  }

  @ParameterizedTest
  @CsvSource({
    "apply, metricf1, metricf2",
    "apply, metricf2, metricf1",
    "by, metricf1, metricf2",
    "by, metricf2, metricf1",
    "groupBy, metricf1, metricf2",
    "groupBy, metricf2, metricf1",
    "having, metricf1, metricf2",
    "having, metricf2, metricf1"
  })
  void testMultipleFunctionsPreserveRequestedOrder(String mode, String first, String second) {
    Table result = summarize(source().summarize(List.of(first, second), sum, mean), mode);
    List<String> expected = new ArrayList<>();
    if (!mode.equals("apply")) {
      expected.add("id");
    }
    expected.addAll(
        List.of(
            "Sum [" + first + "]",
            "Mean [" + first + "]",
            "Sum [" + second + "]",
            "Mean [" + second + "]"));

    assertEquals(expected, result.columnNames());
    if (mode.equals("apply")) {
      assertEquals(10.0, result.doubleColumn("Sum [metricf1]").getDouble(0));
      assertEquals(25.0, result.doubleColumn("Mean [metricf2]").getDouble(0));
    } else {
      Table firstGroup = result.where(result.stringColumn("id").isEqualTo("a"));
      assertEquals(3.0, firstGroup.doubleColumn("Sum [metricf1]").getDouble(0));
      assertEquals(15.0, firstGroup.doubleColumn("Mean [metricf2]").getDouble(0));
      assertEquals(mode.equals("having") ? 1 : 2, result.rowCount());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"apply", "by", "groupBy", "having"})
  void testEachFunctionRunsOncePerColumnAndGroup(String mode) {
    AtomicInteger calls = new AtomicInteger();
    NumericAggregateFunction countingSum =
        new NumericAggregateFunction("Counting sum") {
          @Override
          public Double summarize(NumericColumn<?> column) {
            calls.incrementAndGet();
            return column.sum();
          }
        };

    Table result =
        summarize(source().summarize(List.of("metricf1", "metricf2"), countingSum, mean), mode);

    assertEquals(mode.equals("apply") ? 2 : 4, calls.get());
    assertEquals(mode.equals("apply") ? 4 : 5, result.columnCount());
  }

  @Test
  void testCompatibleFunctionsKeepTheirRequestedOrder() {
    Table table = source();
    table.addColumns(BooleanColumn.create("flag", true, false, true, false));

    Table result = table.summarize(List.of("flag", "metricf1"), mean, countTrue, sum).apply();

    assertEquals(
        List.of("Number True [flag]", "Mean [metricf1]", "Sum [metricf1]"), result.columnNames());
    assertEquals(2.0, result.doubleColumn(0).getDouble(0));
    assertEquals(2.5, result.doubleColumn(1).getDouble(0));
    assertEquals(10.0, result.doubleColumn(2).getDouble(0));
  }

  @Test
  void testColumnWithNoCompatibleFunctionDoesNotChangeOrder() {
    Table result = source().summarize(List.of("metricf1", "id", "metricf2"), sum).apply();

    assertEquals(List.of("Sum [metricf1]", "Sum [metricf2]"), result.columnNames());
  }

  private Table source() {
    return Table.create(
        "metrics",
        StringColumn.create("id", "a", "a", "b", "b"),
        LongColumn.create("metricf1", 1, 2, 3, 4),
        LongColumn.create("metricf2", 10, 20, 30, 40));
  }

  private Table summarize(Summarizer summarizer, String mode) {
    switch (mode) {
      case "apply":
        return summarizer.apply();
      case "by":
        return summarizer.by("id");
      case "groupBy":
        return summarizer.groupBy("id").apply();
      case "having":
        return summarizer.groupBy("id").having(result -> result.stringColumn("id").isEqualTo("a"));
      default:
        throw new IllegalArgumentException("Unknown summary mode: " + mode);
    }
  }
}
