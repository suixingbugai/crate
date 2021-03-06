/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.execution.engine.collect.collectors;

import io.crate.data.CollectionBucket;
import io.crate.data.CompositeBatchIterator;
import io.crate.data.InMemoryBatchIterator;
import io.crate.data.RowConsumer;
import io.crate.test.integration.CrateUnitTest;
import io.crate.testing.TestingBatchIterators;
import io.crate.testing.TestingHelpers;
import io.crate.testing.TestingRowConsumer;
import org.junit.Test;

import java.util.List;

import static io.crate.data.SentinelRow.SENTINEL;
import static org.hamcrest.Matchers.is;

public class MultiConsumerTest extends CrateUnitTest {

    @Test
    public void testSuccessfulMultiConsumerUsage() throws Exception {
        TestingRowConsumer batchConsumer = new TestingRowConsumer();
        RowConsumer consumer = new CompositeCollector.MultiConsumer(2, batchConsumer, CompositeBatchIterator::new);

        consumer.accept(TestingBatchIterators.range(3, 6), null);
        consumer.accept(TestingBatchIterators.range(0, 3), null);

        List<Object[]> result = batchConsumer.getResult();
        assertThat(TestingHelpers.printedTable(new CollectionBucket(result)),
            is("0\n" +
               "1\n" +
               "2\n" +
               "3\n" +
               "4\n" +
               "5\n"));
    }

    @Test
    public void testFirstAcceptNullIteratorDoesNotCauseNPE() throws Exception {
        TestingRowConsumer batchConsumer = new TestingRowConsumer();
        RowConsumer consumer = new CompositeCollector.MultiConsumer(2, batchConsumer, CompositeBatchIterator::new);
        consumer.accept(null, new IllegalStateException("dummy"));
        consumer.accept(InMemoryBatchIterator.empty(SENTINEL), null);

        expectedException.expect(IllegalStateException.class);
        expectedException.expectMessage("dummy");
        batchConsumer.getResult();
    }
}
