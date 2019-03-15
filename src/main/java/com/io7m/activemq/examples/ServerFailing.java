/*
 * Copyright © 2019 Mark Raynsford <code@io7m.com> http://io7m.com
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
 * SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR
 * IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package com.io7m.activemq.examples;

import org.apache.activemq.artemis.api.core.ActiveMQNotConnectedException;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.remoting.impl.netty.NettyAcceptorFactory;
import org.apache.activemq.artemis.core.security.Role;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ServerFailing
{
  private static final Logger LOG = LoggerFactory.getLogger(ServerFailing.class);
  private static final String SERVER_ADDRESS = "127.0.0.1";
  private static final int SERVER_PORT = 9999;

  private ServerFailing()
  {

  }

  public static void main(
    final String[] args)
    throws Exception
  {
    final var executor = Executors.newScheduledThreadPool(8, r -> {
      final var thread = new Thread(r);
      thread.setName("x-" + thread.getId());
      return thread;
    });

    executor.execute(() -> {
      try {
        final var server = startServer(SERVER_ADDRESS, SERVER_PORT);
        server.getActiveMQServer().createQueue(
          new SimpleString("abcd"),
          RoutingType.ANYCAST,
          new SimpleString("abcd"),
          null,
          false,
          false);
      } catch (final Exception e) {
        LOG.error("server failed: ", e);
      }
    });

    executor.execute(() -> {
      try {
        startProducerClient("person0", SERVER_ADDRESS, SERVER_PORT);
      } catch (final Exception e) {
        LOG.error("client failed: ", e);
      }
    });

    executor.execute(() -> {
      try {
        startConsumerClient("person1", SERVER_ADDRESS, SERVER_PORT);
      } catch (final Exception e) {
        LOG.error("client failed: ", e);
      }
    });

    System.in.read();
    executor.shutdown();
    executor.awaitTermination(10L, TimeUnit.SECONDS);
  }

  private static void startProducerClient(
    final String user,
    final String server_address,
    final int server_port)
  {
    final var url =
      new StringBuilder(128)
        .append("tcp://")
        .append(server_address)
        .append(":")
        .append(server_port)
        .toString();

    while (true) {
      try {
        LOG.debug("opening locator");
        try (var locator = ActiveMQClient.createServerLocator(url)) {
          LOG.debug("opening session factory");
          try (var clients = locator.createSessionFactory()) {
            LOG.debug("opening session");
            try (var session = clients.createSession(user, "1234", false, false, false, false, 1)) {
              session.start();

              LOG.debug("creating producer");
              try (var producer = session.createProducer("abcd")) {

                while (true) {
                  LOG.debug("sending message");

                  final var message = session.createMessage(false);
                  message.writeBodyBufferString("Hello");
                  producer.send(message);
                  session.commit();
                  Thread.sleep(1_000L);
                }
              }
            }
          }
        }
      } catch (final Exception e) {
        LOG.error("producer failed: ", e);
        try {
          Thread.sleep(1_000L);
        } catch (final InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  private static void startConsumerClient(
    final String user,
    final String server_address,
    final int server_port)
  {
    final var url =
      new StringBuilder(128)
        .append("tcp://")
        .append(server_address)
        .append(":")
        .append(server_port)
        .toString();

    while (true) {
      try {
        LOG.debug("opening locator");
        try (var locator = ActiveMQClient.createServerLocator(url)) {
          LOG.debug("opening session factory");
          try (var clients = locator.createSessionFactory()) {
            LOG.debug("opening session");
            try (var session = clients.createSession(user, "1234", false, false, false, false, 1)) {
              session.start();

              LOG.debug("creating consumer");
              try (var consumer = session.createConsumer("abcd")) {
                while (true) {
                  final var message = consumer.receive();
                  LOG.debug("message: {}", message);
                  message.acknowledge();
                }
              }
            }
          }
        }
      } catch (final Exception e) {
        LOG.error("consumer failed: ", e);
        try {
          Thread.sleep(1_000L);
        } catch (final InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  private static EmbeddedActiveMQ startServer(
    final String server_address,
    final int server_port)
    throws Exception
  {
    final Configuration config = new ConfigurationImpl();

    final var dir = Paths.get("/tmp/activemq");
    Files.createDirectories(dir);

    final var dir_bind = dir.resolve("bindings");
    final var dir_journal = dir.resolve("journal");
    final var dir_paging = dir.resolve("paging");
    final var dir_large = dir.resolve("large");

    final Map<String, Object> params = new LinkedHashMap<>();
    params.put("protocols", "CORE");
    params.put("host", server_address);
    params.put("port", Integer.valueOf(server_port));
    final var transport =
      new TransportConfiguration(NettyAcceptorFactory.class.getName(), params, "core");

    final var roles = new HashMap<String, Set<Role>>();

    config.setAcceptorConfigurations(Collections.singleton(transport));
    config.setSecurityEnabled(false);
    config.setSecurityRoles(roles);

    config.setName("broker-" + System.currentTimeMillis() + server_port);
    config.setBindingsDirectory(dir_bind.toString());
    config.setJournalDirectory(dir_journal.toString());
    config.setPagingDirectory(dir_paging.toString());
    config.setLargeMessagesDirectory(dir_large.toString());
    config.setPersistenceEnabled(false);

    final var embedded = new EmbeddedActiveMQ();
    embedded.setConfiguration(config);
    embedded.start();

    final var server = embedded.getActiveMQServer();
    while (true) {
      if (server.isStarted()) {
        break;
      }
      try {
        Thread.sleep(1_000L);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    LOG.debug("server started");
    return embedded;
  }
}
