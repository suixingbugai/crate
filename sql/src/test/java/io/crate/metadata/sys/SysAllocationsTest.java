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

package io.crate.metadata.sys;

import io.crate.integrationtests.SQLTransportIntegrationTest;
import io.crate.testing.TestingHelpers;
import io.crate.testing.UseJdbc;
import org.elasticsearch.test.ESIntegTestCase;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

@UseJdbc(0)
@ESIntegTestCase.ClusterScope(numDataNodes = 1)
public class SysAllocationsTest extends SQLTransportIntegrationTest {

    @Before
    public void initTestData() throws Exception {
        execute("CREATE TABLE t1 (id INTEGER, name STRING) CLUSTERED INTO 1 SHARDS WITH (number_of_replicas = 0)");
        execute("ALTER TABLE t1 SET (number_of_replicas = 1)");
    }

    @Test
    public void testUnassignedShardSimpleColumns() {
        execute("SELECT table_name, shard_id, primary, current_state, explanation " +
                "FROM sys.allocations " +
                "WHERE table_name = 't1' " +
                "ORDER BY primary, shard_id");

        assertThat(response.rowCount(), is(2L));
        assertThat(TestingHelpers.printedTable(response.rows()),
            is("t1| 0| false| UNASSIGNED| cannot allocate because allocation is not permitted to any of the nodes\n" +
               "t1| 0| true| STARTED| rebalancing is not allowed\n"));
    }

    @Test
    public void testUnassignedShardDecisionsColumn() {
        execute("SELECT decisions " +
                "FROM sys.allocations " +
                "WHERE table_name = 't1' " +
                "ORDER BY primary, shard_id");
        assertThat(response.rowCount(), is(2L));

        Object[] row;

        // first row: UNASSIGNED shard
        row = response.rows()[0];
        Object[] decisions = (Object[]) row[0];
        assertThat(decisions.length, is(1));
        Map decision = (Map) decisions[0];
        assertNotNull("nodeId must not be null", decision.get("node_id"));
        assertNotNull("nodeName must not be null", decision.get("node_name"));
        assertThat(((String[]) decision.get("explanations"))[0],
            startsWith("the shard cannot be allocated to the same node on which a copy of the shard already exists"));

        // second row: STARTED shard
        row = response.rows()[1];
        decisions = (Object[]) row[0];
        assertNull("for the stared shard decisions must be null", decisions);
    }

    @Test
    public void testUnassignedShardDecisionsColumnSubscript() {
        execute("SELECT decisions['node_id'], decisions['node_name'], decisions['explanations'] " +
                "FROM sys.allocations " +
                "WHERE table_name = 't1' " +
                "ORDER BY primary, shard_id");
        assertThat(response.rowCount(), is(2L));

        Object[] row;

        // first row: UNASSIGNED shard
        row = response.rows()[0];
        String[] nodeIds = (String[]) row[0];
        String[] nodeNames = (String[]) row[1];
        Object[] explanations = (Object[]) row[2]; // we have Object[] because of unknown type in nested array
        assertNotNull("first element of nodeId must not be null", nodeIds[0]);
        assertNotNull("first element of nodeName must not be null", nodeNames[0]);
        assertNotNull("first element of explanations must not be null", explanations[0]);

        // second row: STARTED shard
        row = response.rows()[1];
        nodeIds = (String[]) row[0];
        nodeNames = (String[]) row[1];
        explanations = (Object[]) row[2];
        assertNull("nodeId must be null", nodeIds);
        assertNull("nodeName must be null", nodeNames);
        assertNull("explanations must be null", explanations);
    }

}
