/*
 * JBoss, Home of Professional Open Source
 * Copyright 2009 Red Hat Inc. and/or its affiliates and other
 * contributors as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.infinispan.test.fwk;

import org.infinispan.util.LegacyKeySupportSystemProperties;
import org.jgroups.conf.ConfiguratorFactory;
import org.jgroups.conf.ProtocolConfiguration;
import org.jgroups.conf.ProtocolStackConfigurator;
import org.jgroups.conf.XmlConfigurator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.infinispan.test.fwk.JGroupsConfigBuilder.ProtocolType.*;
import static org.infinispan.util.Immutables.immutableMapCopy;

/**
 * This class owns the logic of associating network resources(i.e. ports) with threads, in order to make sure that there
 * won't be any clashes between multiple clusters running in parallel on same host. Used for parallel test suite.
 *
 * @author Mircea.Markus@jboss.com
 * @author Galder Zamarreño
 */
public class JGroupsConfigBuilder {

   public static final String JGROUPS_STACK;
   // Load the XML just once
   private static final ProtocolStackConfigurator tcpConfigurator = loadTcp();
   private static final ProtocolStackConfigurator udpConfigurator = loadUdp();

   private static final ThreadLocal<String> threadTcpStartPort = new ThreadLocal<String>() {
      private final AtomicInteger uniqueAddr = new AtomicInteger(7900);

      @Override
      protected String initialValue() {
         return String.valueOf(uniqueAddr.getAndAdd(50));
      }
   };

   /**
    * Holds unique mcast_addr for each thread used for JGroups channel construction.
    */
   private static final ThreadLocal<String> threadMcastIP = new ThreadLocal<String>() {
      private final AtomicInteger uniqueAddr = new AtomicInteger(11);

      @Override
      protected String initialValue() {
         return "228.10.10." + uniqueAddr.getAndIncrement();
      }
   };

   /**
    * Holds unique mcast_port for each thread used for JGroups channel construction.
    */
   private static final ThreadLocal<Integer> threadMcastPort = new ThreadLocal<Integer>() {
      private final AtomicInteger uniquePort = new AtomicInteger(45589);

      @Override
      protected Integer initialValue() {
         return uniquePort.getAndIncrement();
      }
   };

   static {
      JGROUPS_STACK = LegacyKeySupportSystemProperties.getProperty("infinispan.test.jgroups.protocol", "protocol.stack", "tcp");
      System.out.println("Transport protocol stack used = " + JGROUPS_STACK);
   }

   public static String getJGroupsConfig(String fullTestName, TransportFlags flags) {
      if (JGROUPS_STACK.equalsIgnoreCase("tcp")) return getTcpConfig(fullTestName, flags);
      if (JGROUPS_STACK.equalsIgnoreCase("udp")) return getUdpConfig(fullTestName, flags);
      throw new IllegalStateException("Unknown protocol stack : " + JGROUPS_STACK);
   }

   public static String getTcpConfig(String fullTestName, TransportFlags flags) {
      // With the XML already parsed, make a safe copy of the
      // protocol stack configurator and use that accordingly.
      JGroupsProtocolCfg jgroupsCfg =
            getJGroupsProtocolCfg(tcpConfigurator.getProtocolStack());

      if (!flags.withFD())
         removeFailureDetectionTcp(jgroupsCfg);

      if (!flags.withMerge())
         removeMerge(jgroupsCfg);

      if (jgroupsCfg.containsProtocol(TEST_PING)) {
         replaceTcpStartPort(jgroupsCfg);
         if (fullTestName == null)
            return jgroupsCfg.toString(); // IDE run of test
         else
            return getTestPingDiscovery(fullTestName, jgroupsCfg); // Cmd line test run
      } else {
         return replaceMCastAddressAndPort(jgroupsCfg);
      }
   }

   private static void removeMerge(JGroupsProtocolCfg jgroupsCfg) {
      jgroupsCfg.removeProtocol(MERGE2);
   }

   public static String getUdpConfig(String fullTestName, TransportFlags flags) {
      JGroupsProtocolCfg jgroupsCfg =
            getJGroupsProtocolCfg(udpConfigurator.getProtocolStack());

      if (!flags.withFD())
         removeFailureDetectionUdp(jgroupsCfg);

      if (!flags.withMerge())
         removeMerge(jgroupsCfg);

      if (jgroupsCfg.containsProtocol(TEST_PING)) {
         if (fullTestName != null)
            return getTestPingDiscovery(fullTestName, jgroupsCfg); // Cmd line test run
      }

      return replaceMCastAddressAndPort(jgroupsCfg);
   }

   /**
    * Remove all failure detection related
    * protocols from the given JGroups TCP stack.
    */
   private static void removeFailureDetectionTcp(JGroupsProtocolCfg jgroupsCfg) {
      jgroupsCfg.removeProtocol(FD)
            .removeProtocol(FD_SOCK)
            .removeProtocol(VERIFY_SUSPECT);
   }

   private static String getTestPingDiscovery(String fullTestName, JGroupsProtocolCfg jgroupsCfg) {
      ProtocolType type = TEST_PING;
      Map<String, String> props = jgroupsCfg.getProtocol(type).getProperties();
      props.put("testName", fullTestName);
      return replaceProperties(jgroupsCfg, props, type);
   }

   private static void removeFailureDetectionUdp(JGroupsProtocolCfg jgroupsCfg) {
      jgroupsCfg.removeProtocol(FD_SOCK).removeProtocol(FD_ALL);
   }

   private static String replaceMCastAddressAndPort(JGroupsProtocolCfg jgroupsCfg) {
      ProtocolType type = UDP;
      Map<String, String> props = jgroupsCfg.getProtocol(type).getProperties();
      props.put("mcast_addr", threadMcastIP.get());
      props.put("mcast_port", threadMcastPort.get().toString());
      return replaceProperties(jgroupsCfg, props, type);
   }

   private static String replaceTcpStartPort(JGroupsProtocolCfg jgroupsCfg) {
      ProtocolType type = TCP;
      Map<String, String> props = jgroupsCfg.getProtocol(type).getProperties();
      props.put("bind_port", threadTcpStartPort.get());
      return replaceProperties(jgroupsCfg, props, type);
   }

   private static String replaceProperties(
         JGroupsProtocolCfg cfg, Map<String, String> newProps, ProtocolType type) {
      ProtocolConfiguration protocol = cfg.getProtocol(type);
      ProtocolConfiguration newProtocol =
            new ProtocolConfiguration(protocol.getProtocolName(), newProps);
      cfg.replaceProtocol(type, newProtocol);
      return cfg.toString();
   }

   private static ProtocolStackConfigurator loadTcp() {
      try {
         return ConfiguratorFactory.getStackConfigurator("stacks/tcp.xml");
      } catch (Exception e) {
         throw new RuntimeException(e);
      }
   }

   private static ProtocolStackConfigurator loadUdp() {
      try {
         return ConfiguratorFactory.getStackConfigurator("stacks/udp.xml");
      } catch (Exception e) {
         throw new RuntimeException(e);
      }
   }

   private static JGroupsProtocolCfg getJGroupsProtocolCfg(List<ProtocolConfiguration> baseStack) {
      JGroupsXmxlConfigurator configurator = new JGroupsXmxlConfigurator(baseStack);
      List<ProtocolConfiguration> protoStack = configurator.getProtocolStack();
      Map<ProtocolType, ProtocolConfiguration> protoMap =
            new HashMap<ProtocolType, ProtocolConfiguration>(protoStack.size());
      for (ProtocolConfiguration cfg : protoStack)
         protoMap.put(getProtocolType(cfg.getProtocolName()), cfg);

      return new JGroupsProtocolCfg(protoMap, configurator);
   }

   private static ProtocolType getProtocolType(String name) {
      int dotIndex = name.lastIndexOf(".");
      return ProtocolType.valueOf(
            dotIndex == -1 ? name : name.substring(dotIndex + 1, name.length()));
   }

   static class JGroupsXmxlConfigurator extends XmlConfigurator {
      protected JGroupsXmxlConfigurator(List<ProtocolConfiguration> protocols) {
         super(copy(protocols));
      }

      static List<ProtocolConfiguration> copy(List<ProtocolConfiguration> protocols) {
         // Make a safe copy of the protocol stack to avoid concurrent modification issues
         List<ProtocolConfiguration> copy =
               new ArrayList<ProtocolConfiguration>(protocols.size());
         for (ProtocolConfiguration p : protocols)
            copy.add(new ProtocolConfiguration(
                  p.getProtocolName(), immutableMapCopy(p.getProperties())));

         return copy;
      }
   }

   static class JGroupsProtocolCfg {
      final Map<ProtocolType, ProtocolConfiguration> protoMap;
      final ProtocolStackConfigurator configurator;

      JGroupsProtocolCfg(Map<ProtocolType, ProtocolConfiguration> protoMap,
                         ProtocolStackConfigurator configurator) {
         this.protoMap = protoMap;
         this.configurator = configurator;
      }

      JGroupsProtocolCfg addProtocol(ProtocolType type, ProtocolConfiguration cfg, int position) {
         protoMap.put(type, cfg);
         configurator.getProtocolStack().add(position, cfg);
         return this;
      }

      JGroupsProtocolCfg removeProtocol(ProtocolType type) {
         // Update the stack and map
         configurator.getProtocolStack().remove(protoMap.remove(type));
         return this;
      }

      ProtocolConfiguration getProtocol(ProtocolType type) {
         return protoMap.get(type);
      }

      boolean containsProtocol(ProtocolType type) {
         return getProtocol(type) != null;
      }

      JGroupsProtocolCfg replaceProtocol(ProtocolType type, ProtocolConfiguration newCfg) {
         ProtocolConfiguration oldCfg = protoMap.get(type);
         int position = configurator.getProtocolStack().indexOf(oldCfg);
         // Remove protocol and put new configuration in same position
         return removeProtocol(type).addProtocol(type, newCfg, position);
      }

      @Override
      public String toString() {
         return configurator.getProtocolStackString();
      }
   }

   enum ProtocolType {
      TCP, UDP,
      MPING, PING, TCPPING, TEST_PING,
      MERGE2,
      FD_SOCK, FD, VERIFY_SUSPECT, FD_ALL,
      BARRIER,
      NAKACK, UNICAST,
      NAKACK2, UNICAST2,
      RSVP,
      STABLE,
      GMS,
      UFC, MFC, FC,
      FRAG2,
      STREAMING_STATE_TRANSFER;
   }

}
