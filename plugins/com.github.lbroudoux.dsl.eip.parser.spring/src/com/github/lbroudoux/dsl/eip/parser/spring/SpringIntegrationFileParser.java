/*
 * Licensed to Laurent Broudoux (the "Author") under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Author licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.github.lbroudoux.dsl.eip.parser.spring;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.emf.common.util.EList;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.github.lbroudoux.dsl.eip.Channel;
import com.github.lbroudoux.dsl.eip.CompositeProcessor;
import com.github.lbroudoux.dsl.eip.ConditionalRoute;
import com.github.lbroudoux.dsl.eip.EIPModel;
import com.github.lbroudoux.dsl.eip.EipFactory;
import com.github.lbroudoux.dsl.eip.Endpoint;
import com.github.lbroudoux.dsl.eip.Resequencer;
import com.github.lbroudoux.dsl.eip.Route;
import com.github.lbroudoux.dsl.eip.Router;
import com.github.lbroudoux.dsl.eip.RoutingType;
/**
 * Parser for Spring integration context file. Just build a new instance and call
 * <code>parseAndFillModel()</code> with already initialized model and it should go !
 * @author laurent
 */
public class SpringIntegrationFileParser {

   private static final String SPRING_BEANS_NS = "http://www.springframework.org/schema/beans";
   private static final String SPRING_INT_NS = "http://www.springframework.org/schema/integration";
   
   private final File routeFile;
   
   private Map<String, Channel> channelsMap = new HashMap<String, Channel>();
   private Map<String, Endpoint> endpointsMap = new HashMap<String, Endpoint>();

   public SpringIntegrationFileParser(File routeFile) {
      this.routeFile = routeFile;
   }
   
   /**
    * Parse the routeFile given while building the instance and fill the model.
    * @param model The EIP Model to fill with parsed elements from routeFile.
    * @throws InvalidArgumentException if given file is not a valid Spring integration file
    */
   public void parseAndFillModel(EIPModel model) throws Exception {
      // Parse and get root element.
      Document document = parseRouteFile();
      Element root = document.getDocumentElement();
      
      // Check that it's really a Spring context file.
      if (!"beans".equals(root.getLocalName()) ||
            !SPRING_BEANS_NS.equals(root.getNamespaceURI())) {
         throw new IllegalArgumentException("Given routeFile seems not to be a valid Spring context file");
      }
      
      // Pre-requisite : we should have a Route object added to model.
      Route route = EipFactory.eINSTANCE.createRoute();
      route.setName(routeFile.getName().substring(0, routeFile.getName().lastIndexOf('.')));
      model.getOwnedRoutes().add(route);    
      
      // First, extract and add channels to model.
      NodeList channels = root.getElementsByTagNameNS(SPRING_INT_NS, "channel");
      for (int i=0; i<channels.getLength(); i++) {
         Node channelNode = channels.item(i);
         route.getOwnedChannels().add(createChannel(channelNode));
      }
      channels = root.getElementsByTagNameNS(SPRING_INT_NS, "publish-subscribe-channel");
      for (int i=0; i<channels.getLength(); i++) {
         Node channelNode = channels.item(i);
         createPubSubChannels(route, channelNode);
      }
      
      // Then, extract and add endpoints to model.
      NodeList children = root.getChildNodes();
      parseAndFillEndpoints(children, route.getOwnedEndpoints());
   }
   
   /** Parse children NodeList and fill the given endpoint collection. */
   private void parseAndFillEndpoints(NodeList children, EList<Endpoint> endpoints) {
      for (int i=0; i<children.getLength(); i++) {
         Node childNode = children.item(i);
         if (childNode.getNodeType() == Node.ELEMENT_NODE 
               && !"channel".equals(childNode.getLocalName())){
            Endpoint endpoint = createEndpoint(childNode);
            // Prevent unknown null endpoint being inserted into no-null constrained list.
            if (endpoint != null) {
               endpoints.add(endpoint);
               // In case of composite, we should recurse.
               if (endpoint instanceof CompositeProcessor) {
                  NodeList compositeChildren = childNode.getChildNodes();
                  parseAndFillEndpoints(compositeChildren, ((CompositeProcessor)endpoint).getOwnedEndpoints());
               }
            }
         }
      }
   }
   
   /** Create Channel model element from node and its attributes. */
   private Channel createChannel(Node channelNode) {
      Element channelElement = (Element) channelNode;
      Channel channel = EipFactory.eINSTANCE.createChannel();
      channel.setName(channelElement.getAttribute("id"));
      channelsMap.put(channel.getName(), channel);
      return channel;
   }
   
   /** Create Channels mode elements from node and its attributes. */
   private void createPubSubChannels(Route route, Node channelNode) {
      Element channelElement = (Element) channelNode;
      // TODO: retrieval and creation of multiple channels in case of PubSub semantic must be finalized. 
      Channel channel = EipFactory.eINSTANCE.createChannel();
      channel.setName(channelElement.getAttribute("id"));
      channelsMap.put(channel.getName(), channel);
      route.getOwnedChannels().add(channel);
   }
   
   /** Create Endpoint model element from node, its attributes and perhaps children. */
   private Endpoint createEndpoint(Node endpointNode) {
      Element endpointElement = (Element) endpointNode;
      Endpoint endpoint = null;
      
      // Determine the correct implementation of Endpoint.
      if ("filter".equals(endpointElement.getLocalName())) {
         endpoint = EipFactory.eINSTANCE.createFilter();
      } else if ("transformer".equals(endpointElement.getLocalName())) {
         endpoint = EipFactory.eINSTANCE.createTransformer();
      } else if ("enricher".equals(endpointElement.getLocalName())) {
         endpoint = EipFactory.eINSTANCE.createEnricher();
      } else if ("chain".equals(endpointElement.getLocalName())) {
         endpoint = EipFactory.eINSTANCE.createCompositeProcessor();
      } else if ("splitter".equals(endpointElement.getLocalName())) {
         endpoint = EipFactory.eINSTANCE.createSplitter();
      } else if ("aggregator".equals(endpointElement.getLocalName())) {
         endpoint = EipFactory.eINSTANCE.createAggregator();
      } else if ("resequencer".equals(endpointElement.getLocalName())) {
         endpoint = EipFactory.eINSTANCE.createResequencer();
         // Complete specific attributes of Resequencer.
         Resequencer resequencer = (Resequencer) endpoint;
         String strategy = endpointElement.getAttribute("correlation-strategy");
         String expression = endpointElement.getAttribute("correlation-strategy-expression");
         String streamSequences = endpointElement.getAttribute("release-partial-sequences");
         if (strategy != null && strategy.length() > 0) {
            resequencer.setStrategy(strategy);
         }
         if (expression != null && expression.length() > 0) {
            resequencer.setExpression(expression);
         }
         if (streamSequences != null && streamSequences.length() > 0) {
            resequencer.setStreamSequences(Boolean.valueOf(streamSequences));
         }
      } else if ("gateway".equals(endpointElement.getLocalName())) {
         endpoint = EipFactory.eINSTANCE.createGateway();
      } else if ("service-activator".equals(endpointElement.getLocalName())) {
         endpoint = EipFactory.eINSTANCE.createServiceActivator();
      } else if (endpointElement.getLocalName().contains("router")) {
         // Complete common attributes for Router.
         endpoint = EipFactory.eINSTANCE.createRouter();
         Router router = ((Router) endpoint);
         String channelOut = endpointElement.getAttribute("default-output-channel");
         if (channelOut != null && channelOut.trim().length() > 0) {
            router.getToChannels().add(channelsMap.get(channelOut));
         }
         NodeList mappings = endpointElement.getElementsByTagNameNS(SPRING_INT_NS, "mapping");
         // Add a conditional route for each mapping.
         for (int i=0; i<mappings.getLength(); i++) {
            Element mapping = (Element)mappings.item(i);
            ConditionalRoute cRoute = EipFactory.eINSTANCE.createConditionalRoute();
            String cRouteChannel = mapping.getAttribute("channel");
            cRoute.setChannel(channelsMap.get(cRouteChannel));
            cRoute.setCondition(mapping.getAttribute("id"));
            router.getOwnedRoutes().add(cRoute);
         }
         
         // Complete specific router type attributes.
         if ("header-value-router".equals(endpointElement.getLocalName())) {
            router.setType(RoutingType.HEADER_VALUE);
         } else if ("payload-type-router".equals(endpointElement.getLocalName())) {
            router.setType(RoutingType.PAYLOAD_TYPE);
         } else if ("router".equals(endpointElement.getLocalName())) {
            router.setType(RoutingType.PAYLOAD_VALUE);
         }
      }
      
      // Complete Endpoint with common attributes if any.
      if (endpoint != null) {
         endpoint.setName(endpointElement.getAttribute("id"));
         String inputChannelName = endpointElement.getAttribute("input-channel");
         if (inputChannelName != null && inputChannelName.trim().length() > 0) {
            Channel inputChannel = channelsMap.get(inputChannelName);
            // Prevent unknown null channel from being inserted in no-null constrained list.
            if (inputChannel != null) {
               endpoint.getFromChannels().add(inputChannel);
            }
         }
         String outputChannelName = endpointElement.getAttribute("output-channel");
         if (outputChannelName != null && outputChannelName.trim().length() > 0) {
            endpoint.getToChannels().add(channelsMap.get(outputChannelName));
         }
         endpointsMap.put(endpoint.getName(), endpoint);
      }
      return endpoint;
   }
   
   /** Parse the Spring integration route file and return DOM Document. */
   private Document parseRouteFile() throws Exception {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      DocumentBuilder builder = factory.newDocumentBuilder();
      return builder.parse(routeFile);
   }
   
   /** Get a mandatory unique child from an element using its tag name. */
   private static Node getUniqueChild(Element element, String tagName){
      NodeList list = element.getElementsByTagName(tagName);
      assert list.getLength() == 1;
      return list.item(0);
   }
   /** Get an optional unique child from an element using its tag name. */
   private static Node getOptionalChild(Element element, String tagName){
      NodeList list = element.getElementsByTagName(tagName);
      if (list.getLength() > 0){
         assert list.getLength() == 1;
         return list.item(0);
      }
      return null;
   }
}
