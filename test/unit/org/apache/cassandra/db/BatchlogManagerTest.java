/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;

import com.google.common.collect.Lists;
import org.junit.Before;
import org.junit.Test;

import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.Util;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.cql3.UntypedResultSet;
import org.apache.cassandra.db.commitlog.ReplayPosition;
import org.apache.cassandra.db.filter.QueryPath;
import org.apache.cassandra.locator.TokenMetadata;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.utils.UUIDGen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import static org.apache.cassandra.utils.ByteBufferUtil.bytes;

public class BatchlogManagerTest extends SchemaLoader
{
    @Before
    public void setUp() throws Exception
    {
        TokenMetadata metadata = StorageService.instance.getTokenMetadata();
        InetAddress localhost = InetAddress.getByName("127.0.0.1");
        metadata.updateNormalToken(Util.token("A"), localhost);
        metadata.updateHostId(UUIDGen.getTimeUUID(), localhost);
    }

    @Test
    public void testReplay() throws Exception
    {
        long initialAllBatches = BatchlogManager.instance.countAllBatches();
        long initialReplayedBatches = BatchlogManager.instance.getTotalBatchesReplayed();

        // Generate 1000 mutations and put them all into the batchlog.
        // Half (500) ready to be replayed, half not.
        for (int i = 0; i < 1000; i++)
        {
            RowMutation mutation = new RowMutation("Keyspace1", bytes(i));
            mutation.add(new QueryPath("Standard1", null, bytes(i)), bytes(i), 0);
            long timestamp = System.currentTimeMillis();
            if (i < 500)
                timestamp -= DatabaseDescriptor.getWriteRpcTimeout() * 2;
            BatchlogManager.getBatchlogMutationFor(Collections.singleton(mutation), UUIDGen.getTimeUUID(), timestamp * 1000).apply();
        }

        // Flush the batchlog to disk (see CASSANDRA-6822).
        Table.open(Table.SYSTEM_KS).getColumnFamilyStore(SystemTable.BATCHLOG_CF).forceFlush();

        assertEquals(1000, BatchlogManager.instance.countAllBatches() - initialAllBatches);
        assertEquals(0, BatchlogManager.instance.getTotalBatchesReplayed() - initialReplayedBatches);

        // Force batchlog replay.
        BatchlogManager.instance.replayAllFailedBatches();

        // Ensure that the first half, and only the first half, got replayed.
        assertEquals(500, BatchlogManager.instance.countAllBatches() - initialAllBatches);
        assertEquals(500, BatchlogManager.instance.getTotalBatchesReplayed() - initialReplayedBatches);

        for (int i = 0; i < 1000; i++)
        {
            UntypedResultSet result = QueryProcessor.processInternal(String.format("SELECT * FROM \"Keyspace1\".\"Standard1\" WHERE key = intAsBlob(%d)", i));
            if (i < 500)
            {
                assertEquals(bytes(i), result.one().getBytes("key"));
                assertEquals(bytes(i), result.one().getBytes("column1"));
                assertEquals(bytes(i), result.one().getBytes("value"));
            }
            else
            {
                assertTrue(result.isEmpty());
            }
        }

        // Ensure that no stray mutations got somehow applied.
        UntypedResultSet result = QueryProcessor.processInternal(String.format("SELECT count(*) FROM \"Keyspace1\".\"Standard1\""));
        assertEquals(500, result.one().getLong("count"));
    }

    @Test
    public void testTruncatedReplay() throws InterruptedException, ExecutionException
    {
        // Generate 2000 mutations (1000 batchlog entries) and put them all into the batchlog.
        // Each batchlog entry with a mutation for Standard2 and Standard3.
        // In the middle of the process, 'truncate' Standard2.
        for (int i = 0; i < 1000; i++)
        {
            RowMutation mutation1 = new RowMutation("Keyspace1", bytes(i));
            mutation1.add(new QueryPath("Standard2", null, bytes(i)), bytes(i), 0);
            RowMutation mutation2 = new RowMutation("Keyspace1", bytes(i));
            mutation2.add(new QueryPath("Standard3", null, bytes(i)), bytes(i), 0);
            List<RowMutation> mutations = Lists.newArrayList(mutation1, mutation2);

            // Make sure it's ready to be replayed, so adjust the timestamp.
            long timestamp = System.currentTimeMillis() - DatabaseDescriptor.getWriteRpcTimeout() * 2;

            if (i == 500)
                SystemTable.saveTruncationRecord(Table.open("Keyspace1").getColumnFamilyStore("Standard2"),
                                                 timestamp,
                                                 ReplayPosition.NONE);

            // Adjust the timestamp (slightly) to make the test deterministic.
            if (i >= 500)
                timestamp++;
            else
                timestamp--;

            BatchlogManager.getBatchlogMutationFor(mutations, UUIDGen.getTimeUUID(), timestamp * 1000).apply();
        }

        // Flush the batchlog to disk (see CASSANDRA-6822).
        Table.open(Table.SYSTEM_KS).getColumnFamilyStore(SystemTable.BATCHLOG_CF).forceFlush();

        // Force batchlog replay.
        BatchlogManager.instance.replayAllFailedBatches();

        // We should see half of Standard2-targeted mutations written after the replay and all of Standard3 mutations applied.
        for (int i = 0; i < 1000; i++)
        {
            UntypedResultSet result = QueryProcessor.processInternal(String.format("SELECT * FROM \"Keyspace1\".\"Standard2\" WHERE key = intAsBlob(%d)", i));
            if (i >= 500)
            {
                assertEquals(bytes(i), result.one().getBytes("key"));
                assertEquals(bytes(i), result.one().getBytes("column1"));
                assertEquals(bytes(i), result.one().getBytes("value"));
            }
            else
            {
                assertTrue(result.isEmpty());
            }
        }

        for (int i = 0; i < 1000; i++)
        {
            UntypedResultSet result = QueryProcessor.processInternal(String.format("SELECT * FROM \"Keyspace1\".\"Standard3\" WHERE key = intAsBlob(%d)", i));
            assertEquals(bytes(i), result.one().getBytes("key"));
            assertEquals(bytes(i), result.one().getBytes("column1"));
            assertEquals(bytes(i), result.one().getBytes("value"));
        }
    }
}
