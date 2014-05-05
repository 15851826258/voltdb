/* This file is part of VoltDB.
 * Copyright (C) 2008-2014 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.voltdb.utils;

import au.com.bytecode.opencsv_voltpatches.CSVParser;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import kafka.consumer.ConsumerConfig;
import kafka.consumer.ConsumerIterator;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;

import org.voltcore.logging.VoltLogger;
import org.voltdb.CLIConfig;
import org.voltdb.client.Client;
import org.voltdb.client.ClientConfig;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.VoltBulkLoader.BulkLoaderFailureCallBack;
import org.voltdb.client.VoltBulkLoader.VoltBulkLoader;

/**
 * KafkaConsumer loads data from kafka into voltdb
 * Only csv formatted data is supported at this time.
 * VARBINARY columns are not supported
 */
public class KafkaLoader {

    private static final VoltLogger m_log = new VoltLogger("KAFKALOADER");
    private final KafkaConfig m_config;
    private final static AtomicLong m_failedCount = new AtomicLong(0);
    private VoltBulkLoader m_loader;

    public KafkaLoader(KafkaConfig config) {
        m_config = config;
    }

    public void processKafkaMessages() throws Exception {
        // Split server list
        final String[] serverlist = m_config.servers.split(",");

        // Create connection
        final ClientConfig c_config = new ClientConfig(m_config.user, m_config.password);
        c_config.setProcedureCallTimeout(0); // Set procedure all to infinite

        Client client = getClient(c_config, serverlist);

        m_loader = client.getNewBulkLoader(m_config.table, m_config.batch, new KafkaBulkLoaderCallback());

        final KafkaConsumerConnector consumer = new KafkaConsumerConnector(m_config.zookeeper, m_config.table);
        try {
            ExecutorService es = getConsumerExecutor(consumer, m_loader);

            Runtime.getRuntime().addShutdownHook(
                    new Thread() {
                        @Override
                        public void run() {
                            try {
                                consumer.stop();
                                m_loader.flush();
                                m_loader.drain();
                                m_loader.close();
                            } catch (InterruptedException ex) {
                            }
                        }
                    });

            es.awaitTermination(365, TimeUnit.DAYS);
        } catch (Exception ex) {
            m_log.error("Error in getting Kafka Consumer", ex);
            System.exit(-1);
        }
    }

    /**
     * Configuration options.
     */
    public static class KafkaConfig extends CLIConfig {

        @Option(shortOpt = "t", desc = "Kafka Topic to subscribe to")
        String topic = "";

        @Option(shortOpt = "m", desc = "maximum errors allowed")
        int maxerrors = 100;

        @Option(shortOpt = "s", desc = "list of servers to connect to (default: localhost:21212)")
        String servers = "localhost:21212";

        @Option(desc = "username when connecting to the servers")
        String user = "";

        @Option(desc = "password to use when connecting to servers")
        String password = "";

        @Option(shortOpt = "z", desc = "kafka zookeeper to connect to.")
        String zookeeper = ""; //No default here as default will clash with local voltdb cluster

        /**
         * Batch size for processing batched operations.
         */
        @Option(desc = "Batch Size for processing.")
        public int batch = 200;

        /**
         * Table name to insert CSV data into.
         */
        @AdditionalArgs(desc = "insert the data into this table.")
        public String table = "";

        /**
         * Validate command line options.
         */
        @Override
        public void validate() {
            if (batch < 0) {
                exitWithMessageAndUsage("batch size number must be >= 0");
            }
            if (table.length() <= 0) {
                exitWithMessageAndUsage("Table must be specified.");
            }
            if (topic.length() <= 0) {
                exitWithMessageAndUsage("Topic must be specified.");
            }
            if (zookeeper.length() <= 0) {
                exitWithMessageAndUsage("Kafka Zookeeper must be specified.");
            }
        }

        /**
         * Usage
         */
        @Override
        public void printUsage() {
            System.out.println("Usage: kafkaloader [args] -z kafka-zookeeper -t topic tablename");
            super.printUsage();
        }
    }

    class KafkaBulkLoaderCallback implements BulkLoaderFailureCallBack {

        @Override
        public void failureCallback(Object rowHandle, Object[] fieldList, ClientResponse response) {
            if (response.getStatus() != ClientResponse.SUCCESS) {
                m_log.error("Failed to Insert Row: " + rowHandle);
                long fc = m_failedCount.incrementAndGet();
                if (m_config.maxerrors > 0 && fc > m_config.maxerrors) {
                    System.exit(1);
                }
            }
        }
    }

    private static class KafkaConsumerConnector {

        final ConsumerConfig m_consumerConfig;
        final ConsumerConnector m_consumer;

        public KafkaConsumerConnector(String zk, String tableName) {
            //Get group id which should be unique for table so as to keep offsets clean for multiple runs.
            String groupId = "voltdb-" + tableName;
            //TODO: Should get this from properties file or something as override?
            Properties props = new Properties();
            props.put("zookeeper.connect", zk);
            props.put("group.id", groupId);
            props.put("zookeeper.session.timeout.ms", "400");
            props.put("zookeeper.sync.time.ms", "200");
            props.put("auto.commit.interval.ms", "1000");
            props.put("auto.commit.enable", "true");
            props.put("auto.offset.reset", "smallest");

            m_consumerConfig = new ConsumerConfig(props);

            m_consumer = kafka.consumer.Consumer.createJavaConsumerConnector(m_consumerConfig);
        }

        public void stop() {
            try {
                //Let offset get pushed to zk....so sleep for auto.commit.interval.ms
                Thread.sleep(1100);
            } catch (InterruptedException ex) { }
            finally {
                m_consumer.commitOffsets();
                m_consumer.shutdown();
            }
        }
    }

    public static class KafkaConsumer implements Runnable {

        private final KafkaStream m_stream;
        private final VoltBulkLoader m_loader;
        private final CSVParser m_csvParser;

        public KafkaConsumer(KafkaStream a_stream, VoltBulkLoader loader) {
            m_stream = a_stream;
            m_loader = loader;
            m_csvParser = new CSVParser();
        }

        @Override
        public void run() {
            ConsumerIterator<byte[], byte[]> it = m_stream.iterator();
            while (it.hasNext()) {
                byte msg[] = it.next().message();
                String smsg = new String(msg);
                try {
                    m_loader.insertRow(smsg, (Object[]) m_csvParser.parseLine(smsg));
                } catch (InterruptedException ex) {
                    m_log.error("Consumer stopped", ex);
                } catch (IOException ex) {
                    m_log.error("Consumer stopped", ex);
                }
            }
        }

    }

    private ExecutorService getConsumerExecutor(KafkaConsumerConnector consumer,
            VoltBulkLoader loader) throws Exception {

        Map<String, Integer> topicCountMap = new HashMap<>();
        //Get this from config or arg. Use 3 threads default.
        ExecutorService executor = Executors.newFixedThreadPool(3);
        topicCountMap.put(m_config.topic, 3);
        Map<String, List<KafkaStream<byte[], byte[]>>> consumerMap = consumer.m_consumer.createMessageStreams(topicCountMap);
        List<KafkaStream<byte[], byte[]>> streams = consumerMap.get(m_config.topic);

        // now launch all the threads for partitions.
        for (final KafkaStream stream : streams) {
            KafkaConsumer bconsumer = new KafkaConsumer(stream, loader);
            executor.submit(bconsumer);
        }

        return executor;
    }

    /**
     * Get connection to servers in cluster.
     *
     * @param config
     * @param servers
     * @return client
     * @throws Exception
     */
    public static Client getClient(ClientConfig config, String[] servers) throws Exception {
        final Client client = ClientFactory.createClient(config);
        for (String server : servers) {
            int port = Client.VOLTDB_SERVER_PORT;
            String serverandport[] = server.split(":");
            String s = serverandport[0];
            if (serverandport.length > 1) {
                port = Integer.parseInt(serverandport[1]);
            }
            client.createConnection(s.trim(), port);
        }
        return client;
    }

    /**
     * kafkaloader main
     *
     * @param args
     *
     */
    public static void main(String[] args) {

        final KafkaConfig cfg = new KafkaConfig();
        cfg.parse(KafkaLoader.class.getName(), args);
        try {
            KafkaLoader kloader = new KafkaLoader(cfg);
            kloader.processKafkaMessages();
        } catch (Exception e) {
            m_log.error("Failure in kafkaloader", e);
            System.exit(-1);
        }

        System.exit(0);
    }


}
