package com.streamsets.dc.flume.cluster;

import com.google.common.io.Resources;
import com.streamsets.dc.MiniSDC;
import com.streamsets.dc.util.ClusterUtil;
import com.streamsets.dc.util.TestUtil;
import com.streamsets.dc.util.VerifyUtils;
import com.streamsets.pipeline.lib.KafkaTestUtil;
import kafka.javaapi.producer.Producer;
import kafka.producer.KeyedMessage;
import org.apache.flume.Channel;
import org.apache.flume.ChannelSelector;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.Transaction;
import org.apache.flume.channel.ChannelProcessor;
import org.apache.flume.channel.MemoryChannel;
import org.apache.flume.channel.ReplicatingChannelSelector;
import org.apache.flume.conf.Configurables;
import org.apache.flume.source.AvroSource;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The pipeline and data provider for this test case should make sure that the pipeline runs continuously and the origin
 *  in the pipeline keeps producing records until stopped.
 *
 * Origin has to be Kafka as of now. Make sure that there is continuous supply of data for the pipeline to keep running.
 * For example to test kafka Origin, a background thread could keep writing to the kafka topic from which the
 * kafka origin reads.
 *
 */
public class TestKafkaToFlume {

  //Kafka messages contain text "Hello Kafka<i>" i in the range [0-29]
  private static int RECORDS_PRODUCED = 30;
  //Based on the expression parser and stream selector target ends up with messages which have even first digit of i.
  //i.e., Hello Kafka<0,2,4,6,8,20,21,22,23,24,25,26,27,28,29>
  private static int RECORDS_REACHING_TARGET= 15;

  protected static URI serverURI;
  protected static MiniSDC miniSDC;
  private static final String TOPIC = "KafkaToFlumeOnCluster";
  private static AvroSource source;
  private static Channel ch;
  private static Producer<String, String> producer;
  private static int flumePort;

  private static final String TEST_NAME = "KafkaToFlumeOnCluster";

  @BeforeClass
  public static void beforeClass() throws Exception {
    //setup kafka to read from
    KafkaTestUtil.startZookeeper();
    KafkaTestUtil.startKafkaBrokers(1);
    KafkaTestUtil.createTopic(TOPIC, 1, 1);
    producer = KafkaTestUtil.createProducer(KafkaTestUtil.getMetadataBrokerURI(), true);
    produceRecords(RECORDS_PRODUCED);

    //setup flume to write to
    source = new AvroSource();
    ch = new MemoryChannel();
    Configurables.configure(ch, new Context());

    Context context = new Context();
    //This should match whats present in the pipeline.json file
    flumePort = TestUtil.getFreePort();
    context.put("port", String.valueOf(flumePort));
    context.put("bind", "localhost");
    Configurables.configure(source, context);

    List<Channel> channels = new ArrayList<>();
    channels.add(ch);
    ChannelSelector rcs = new ReplicatingChannelSelector();
    rcs.setChannels(channels);
    source.setChannelProcessor(new ChannelProcessor(rcs));
    source.start();

    //setup Cluster and start pipeline
    ClusterUtil.setupCluster(TEST_NAME, getPipelineJson(), new YarnConfiguration());
    serverURI = ClusterUtil.getServerURI();
    miniSDC = ClusterUtil.getMiniSDC();
  }

  @AfterClass
  public static void afterClass() throws Exception {
    KafkaTestUtil.shutdown();
    source.stop();
    ch.stop();
    ClusterUtil.tearDownCluster(TEST_NAME);
  }

  private static String getPipelineJson() throws Exception {
    URI uri = Resources.getResource("cluster_kafka_flume.json").toURI();
    String pipelineJson =  new String(Files.readAllBytes(Paths.get(uri)), StandardCharsets.UTF_8);
    pipelineJson = pipelineJson.replace("topicName", TOPIC);
    pipelineJson = pipelineJson.replaceAll("localhost:9092", KafkaTestUtil.getMetadataBrokerURI());
    pipelineJson = pipelineJson.replaceAll("localhost:2181", KafkaTestUtil.getZkConnect());
    pipelineJson = pipelineJson.replaceAll("localhost:9050", "localhost:" + flumePort);
    pipelineJson = pipelineJson.replaceAll("STANDALONE", "CLUSTER");
    return pipelineJson;
  }

  private static void produceRecords(int records) throws InterruptedException {
    int i = 0;
    while (i < records) {
      producer.send(new KeyedMessage<>(TOPIC, "0", "Hello Kafka" + i));
      i++;
    }
  }

  @Test
  public void testKafkaToFlumeOnCluster() throws Exception {
    Assert.assertEquals("RUNNING", VerifyUtils.getPipelineState(serverURI, "kafka_origin_pipeline_cluster", "0"));
    List<URI> list = miniSDC.getListOfSlaveSDCURI();
    Assert.assertTrue(list != null && !list.isEmpty());

    Map<String, Map<String, Object>> countersMap = VerifyUtils.getCounters(list, "kafka_origin_pipeline_cluster", "0");
    Assert.assertNotNull(countersMap);
    Assert.assertEquals("Output records counters for source should be equal to " + RECORDS_PRODUCED, RECORDS_PRODUCED,
      VerifyUtils.getSourceOutputRecords(countersMap));
    Assert.assertEquals("Output records counters for target should be equal to " + RECORDS_REACHING_TARGET, RECORDS_REACHING_TARGET,
      VerifyUtils.getTargetInputRecords(countersMap));

    //check from flume
    for(int i = 0; i < RECORDS_REACHING_TARGET; i++) {
      Transaction transaction = ch.getTransaction();
      transaction.begin();
      Event event = ch.take();
      Assert.assertNotNull(event);
      String text = new String(event.getBody()).trim();
      Assert.assertTrue(text.contains("Hello Kafka"));
      int j = Integer.parseInt(text.substring(11, 12));
      Assert.assertTrue(j%2 == 0);

      transaction.commit();
      transaction.close();
    }
  }

}