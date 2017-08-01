package com.io7m.activemq.examples;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.Message;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.ActiveMQTopic;
import org.apache.activemq.network.NetworkConnector;
import org.apache.activemq.store.memory.MemoryPersistenceAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.DeliveryMode;
import javax.jms.JMSException;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import javax.jms.TopicConnection;
import javax.jms.TopicConnectionFactory;
import javax.jms.TopicPublisher;
import javax.jms.TopicSession;
import javax.jms.TopicSubscriber;
import java.net.URI;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ForwardingServers
{
  private static final Logger LOG =
    LoggerFactory.getLogger(ForwardingServers.class);

  private static final String PROXY_BROKER_URI =
    "tcp://[fd38:73b9:8748:8f82::1]:61616";
  private static final String RECEIVER_BROKER_URI =
    "tcp://[fd38:73b9:8748:8f82::2]:61616";

  private ForwardingServers()
  {

  }

  public static void main(
    final String[] args)
    throws Exception
  {
    /*
     * Start both brokers.
     */

    final BrokerService s0 = createReceiverBroker();
    final BrokerService s1 = createProxyBroker();

    /*
     * Start sending and receiving messages.
     */

    final ExecutorService exec = Executors.newCachedThreadPool();
    exec.submit(ForwardingServers::receiveMessages);
    exec.submit(ForwardingServers::sendMessages);

    /*
     * Shut down when someone types a character on stdin.
     */

    System.in.read();
    s0.stop();
    s1.stop();
    exec.shutdown();
  }

  /**
   * Set up a proxy broker that simply forwards any messages to the main
   * broker.
   */

  private static BrokerService createProxyBroker()
    throws Exception
  {
    final BrokerService service = new BrokerService();
    service.setBrokerName("broker-proxy");
    service.setDataDirectory("/tmp/broker-proxy");
    service.setUseJmx(true);

    final StaticNetworkConnector connector =
      new StaticNetworkConnector(URI.create("static:(" + RECEIVER_BROKER_URI + ")"));
    connector.setStaticBridge(true);
    connector.setConduitSubscriptions(true);
    connector.setDuplex(true);

    final ActiveMQTopic topic = new ActiveMQTopic(">");
    final ArrayList<ActiveMQDestination> destinations = new ArrayList<>();
    destinations.add(topic);
    connector.setStaticallyIncludedDestinations(destinations);

    service.addNetworkConnector(connector);
    service.setPersistenceAdapter(new MemoryPersistenceAdapter());
    service.addConnector(PROXY_BROKER_URI);
    service.start();
    return service;
  }

  /**
   * Set up the main broker.
   */

  private static BrokerService createReceiverBroker()
    throws Exception
  {
    final BrokerService service = new BrokerService();
    service.setBrokerName("broker-receiver");
    service.setDataDirectory("/tmp/broker-receiver");
    service.setUseJmx(true);

    service.setPersistenceAdapter(new MemoryPersistenceAdapter());
    service.addConnector(RECEIVER_BROKER_URI);
    service.start();
    return service;
  }

  /**
   * Subscribe to a topic on the main broker.
   */

  private static Void receiveMessages()
    throws JMSException
  {
    Thread.currentThread().setName("receiver");

    final TopicConnectionFactory cfact =
      new ActiveMQConnectionFactory(RECEIVER_BROKER_URI);
    final TopicConnection conn =
      cfact.createTopicConnection();

    conn.start();

    final TopicSession session =
      conn.createTopicSession(false, Session.AUTO_ACKNOWLEDGE);
    final Topic topic =
      session.createTopic("test0");
    final TopicSubscriber destination =
      session.createSubscriber(topic);

    destination.setMessageListener(message -> {
      if (message instanceof TextMessage) {
        try {
          final TextMessage tm = (TextMessage) message;
          LOG.debug("received: {}", tm.getText());
        } catch (final JMSException e) {
          LOG.error("failed to retrieve text message: ", e);
        }
      } else {
        LOG.debug("received: {}", message);
      }
    });

    return null;
  }

  /**
   * Send messages to a topic on the second broker. The messages should be
   * forwarded to subscribers on the main broker.
   */

  private static Void sendMessages()
    throws JMSException
  {
    Thread.currentThread().setName("sender");

    final TopicConnectionFactory cfact =
      new ActiveMQConnectionFactory(PROXY_BROKER_URI);
    final TopicConnection conn =
      cfact.createTopicConnection();

    conn.start();

    final TopicSession session =
      conn.createTopicSession(false, Session.AUTO_ACKNOWLEDGE);
    final Topic topic =
      session.createTopic("test0");
    final TopicPublisher publisher =
      session.createPublisher(topic);

    for (int index = 0; index < 1000_000; ++index) {
      final TextMessage message =
        session.createTextMessage("Hello: " + ZonedDateTime.now());

      publisher.publish(
        message,
        DeliveryMode.PERSISTENT,
        Message.DEFAULT_PRIORITY,
        TimeUnit.MILLISECONDS.convert(10L, TimeUnit.MINUTES));

      try {
        Thread.sleep(
          TimeUnit.MILLISECONDS.convert(1L, TimeUnit.SECONDS));
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    session.close();
    conn.close();
    return null;
  }

  private static final class StaticNetworkConnector extends NetworkConnector
  {
    public StaticNetworkConnector(
      final URI uri)
    {
      super(uri);
    }
  }
}
