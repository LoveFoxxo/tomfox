/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tomcat.manager2;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletResponse;

import org.apache.catalina.Cluster;
import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Executor;
import org.apache.catalina.Host;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.Loader;
import org.apache.catalina.Manager;
import org.apache.catalina.Realm;
import org.apache.catalina.Server;
import org.apache.catalina.Service;
import org.apache.catalina.SessionIdGenerator;
import org.apache.catalina.Valve;
import org.apache.catalina.WebResourceRoot;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardEngine;
import org.apache.catalina.core.StandardServer;
import org.apache.catalina.core.StandardService;
import org.apache.catalina.deploy.NamingResourcesImpl;
import org.apache.catalina.ha.CatalinaCluster;
import org.apache.catalina.ha.ClusterDeployer;
import org.apache.catalina.ha.ClusterListener;
import org.apache.catalina.ha.ClusterManager;
import org.apache.catalina.ha.tcp.SimpleTcpCluster;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.realm.CombinedRealm;
import org.apache.catalina.tribes.Channel;
import org.apache.catalina.tribes.ChannelInterceptor;
import org.apache.catalina.tribes.ChannelReceiver;
import org.apache.catalina.tribes.ChannelSender;
import org.apache.catalina.tribes.ManagedChannel;
import org.apache.catalina.tribes.Member;
import org.apache.catalina.tribes.MembershipService;
import org.apache.catalina.tribes.group.GroupChannel;
import org.apache.catalina.tribes.group.interceptors.MessageDispatchInterceptor;
import org.apache.catalina.tribes.group.interceptors.StaticMembershipInterceptor;
import org.apache.catalina.tribes.group.interceptors.TcpFailureDetector;
import org.apache.catalina.tribes.membership.McastService;
import org.apache.catalina.tribes.membership.StaticMember;
import org.apache.catalina.tribes.membership.StaticMembershipService;
import org.apache.catalina.tribes.transport.AbstractSender;
import org.apache.catalina.tribes.transport.MultiPointSender;
import org.apache.catalina.tribes.transport.ReceiverBase;
import org.apache.catalina.tribes.transport.ReplicationTransmitter;
import org.apache.catalina.util.LifecycleBase;
import org.apache.catalina.util.SessionIdGeneratorBase;
import org.apache.coyote.UpgradeProtocol;
import org.apache.coyote.http2.Http2Protocol;
import org.apache.tomcat.util.descriptor.web.ContextEjb;
import org.apache.tomcat.util.descriptor.web.ContextEnvironment;
import org.apache.tomcat.util.descriptor.web.ContextLocalEjb;
import org.apache.tomcat.util.descriptor.web.ContextResource;
import org.apache.tomcat.util.descriptor.web.ContextResourceEnvRef;
import org.apache.tomcat.util.descriptor.web.ContextResourceLink;
import org.apache.tomcat.util.descriptor.web.ContextService;
import org.apache.tomcat.util.descriptor.web.ResourceBase;
import org.apache.tomcat.util.http.CookieProcessor;
import org.apache.tomcat.util.http.CookieProcessorBase;
import org.apache.tomcat.util.modeler.AttributeInfo;
import org.apache.tomcat.util.modeler.ManagedBean;
import org.apache.tomcat.util.modeler.Registry;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.apache.tomcat.util.net.SSLHostConfigPreSharedKey;


/** Component-tree navigation, node metadata and stable identifier resolution. */
abstract class ConfigApiNavigationServlet extends ConfigApiProtocolServlet {

    // ------------------------------------------------------- Tree building


    /**
     * Build the tree entry of one component, including its children.
     */
    protected Map<String, Object> build(Object component, String id, String type) {

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("className", component.getClass().getName());
        node.put("name", displayName(component, type));
        if (component instanceof Lifecycle lifecycle) {
            node.put("state", lifecycle.getState().toString());
        }
        if ("context".equals(type) && component == selfContext) {
            node.put("self", Boolean.TRUE);
        }
        if ("namingResources".equals(type) && component instanceof NamingResourcesImpl namingResources) {
            // The server level node (container is the Server) does not
            // accept resourceLink entries; the client filters them out.
            node.put("global", Boolean.valueOf(namingResources.getContainer() instanceof Server));
        }

        // The node type identifies the component kind; the tree is built from
        // components of exactly these types (and resolve() enforces the same
        // types for the node endpoint). Only the cluster (whose implementation
        // class is not constrained beyond the Cluster interface), the managed
        // nature of a channel and the static membership interceptor remain
        // instanceof checks: they are capability probes, not type dispatch.
        List<Map<String, Object>> children = new ArrayList<>();
        switch (type) {
            case "server" -> {
                StandardServer s = (StandardServer) component;
                children.add(build(s.getGlobalNamingResources(), id + "/namingResources/0", "namingResources"));
                for (Service service : s.findServices()) {
                    children.add(build(service, id + "/service/" + enc(service.getName()), "service"));
                }
                children.addAll(childrenOfListeners(s, id));
            }
            case "service" -> {
                StandardService service = (StandardService) component;
                Engine engine = service.getContainer();
                if (engine != null) {
                    children.add(build(engine, id + "/engine/" + enc(engine.getName()), "engine"));
                }
                children.addAll(childrenOfConnectors(service, id));
                children.addAll(childrenOfExecutors(service, id));
                children.addAll(childrenOfListeners(service, id));
            }
            case "engine" -> {
                StandardEngine engine = (StandardEngine) component;
                for (Container child : engine.findChildren()) {
                    if (child instanceof Host) {
                        children.add(build(child, id + "/host/" + enc(child.getName()), "host"));
                    }
                }
                children.addAll(childrenOfRealm(engine, id));
                children.addAll(childrenOfCluster(engine, id));
                children.addAll(childrenOfValves(engine, id));
                children.addAll(childrenOfListeners(engine, id));
            }
            case "host" -> {
                Host host = (Host) component;
                for (String alias : host.findAliases()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", id + "/alias/" + enc(alias));
                    entry.put("type", "alias");
                    entry.put("className", String.class.getName());
                    entry.put("name", alias);
                    entry.put("children", new ArrayList<Map<String, Object>>());
                    children.add(entry);
                }
                for (Container child : host.findChildren()) {
                    if (child instanceof Context context) {
                        children.add(build(context, id + "/context/" + encContextPath(context.getPath()), "context"));
                    }
                }
                children.addAll(childrenOfRealm(host, id));
                children.addAll(childrenOfCluster(host, id));
                children.addAll(childrenOfValves(host, id));
                children.addAll(childrenOfListeners((LifecycleBase) host, id));
            }
            case "context" -> {
                Context context = (Context) component;
                for (Container child : context.findChildren()) {
                    if (child instanceof Wrapper wrapper) {
                        children.add(build(wrapper, id + "/wrapper/" + enc(wrapper.getName()), "wrapper"));
                    }
                }
                children.addAll(childrenOfRealm(context, id));
                children.addAll(childrenOfCluster(context, id));
                children.addAll(childrenOfContextComponents(context, id));
                children.add(build(context.getNamingResources(), id + "/namingResources/0", "namingResources"));
                children.addAll(childrenOfValves(context, id));
                children.addAll(childrenOfListeners((LifecycleBase) context, id));
            }
            case "connector" -> {
                Connector connector = (Connector) component;
                children.addAll(sslHostConfigChildren(connector, id));
                children.addAll(upgradeProtocolChildren(connector, id));
            }
            case "sslHostConfig" -> {
                SSLHostConfig hostConfig = (SSLHostConfig) component;
                children.addAll(certificateChildren(hostConfig, id));
                children.addAll(preSharedKeyChildren(hostConfig, id));
            }
            case "cluster" -> {
                if (component instanceof CatalinaCluster cluster) {
                    children.addAll(clusterChildren(cluster, id));
                }
            }
            case "channel" -> {
                if (component instanceof ManagedChannel managed) {
                    children.addAll(channelChildren(managed, id));
                }
            }
            case "sender" -> children.addAll(senderChildren((ChannelSender) component, id));
            case "membership" -> children.addAll(membershipChildren((MembershipService) component, id));
            case "interceptor" -> {
                if (component instanceof StaticMembershipInterceptor interceptor) {
                    children.addAll(interceptorChildren(interceptor, id));
                }
            }
            case "realm" -> children.addAll(childrenOfSubRealms((Realm) component, id));
            // A cluster manager template is a Manager as well; its session id
            // generator is shown (and can be replaced) the same way.
            case "manager", "clusterManager" ->
                    children.addAll(sessionIdGeneratorChildren((Manager) component, id));
            case "namingResources" ->
                    children.addAll(childrenOfNamingResources((NamingResourcesImpl) component, id));
            default -> {
            }
        }
        node.put("children", children);
        return node;
    }


    /**
     * The tree entries of the TLS (SSL) host configurations of a connector, each with its certificate and pre-shared
     * key children.
     */
    protected List<Map<String, Object>> sslHostConfigChildren(Connector connector, String parentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        SSLHostConfig[] hostConfigs = connector.findSslHostConfigs();
        if (hostConfigs == null || hostConfigs.length == 0) {
            return result;
        }
        // The configurations are held in a hash map; sort them by host
        // name so that the tree (and the order in which they are
        // displayed) is stable across requests and restarts.
        SSLHostConfig[] sorted = hostConfigs.clone();
        Arrays.sort(sorted, (a, b) -> a.getHostName().compareTo(b.getHostName()));
        for (SSLHostConfig hostConfig : sorted) {
            Map<String, Object> entry = new LinkedHashMap<>();
            String id = parentId + "/sslHostConfig/" + enc(hostConfig.getHostName());
            entry.put("id", id);
            entry.put("type", "sslHostConfig");
            entry.put("className", hostConfig.getClass().getName());
            entry.put("name", hostConfig.getHostName());
            List<Map<String, Object>> hostConfigChildren = certificateChildren(hostConfig, id);
            hostConfigChildren.addAll(preSharedKeyChildren(hostConfig, id));
            entry.put("children", hostConfigChildren);
            result.add(entry);
        }
        return result;
    }


    /**
     * The tree entries of the certificates of an SSL host configuration.
     */
    protected List<Map<String, Object>> certificateChildren(SSLHostConfig hostConfig, String parentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        SSLHostConfigCertificate[] certificates = hostConfig.getCertificates().toArray(new SSLHostConfigCertificate[0]);
        for (int i = 0; i < certificates.length; i++) {
            SSLHostConfigCertificate certificate = certificates[i];
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", parentId + "/certificate/" + i);
            entry.put("type", "certificate");
            entry.put("className", certificate.getClass().getName());
            entry.put("name", certificateLabel(certificate));
            entry.put("children", new ArrayList<Map<String, Object>>());
            result.add(entry);
        }
        return result;
    }


    /**
     * A human readable name for a certificate configuration: the certificate type, with the (typeless) default
     * certificate shown as "default".
     */
    protected static String certificateLabel(SSLHostConfigCertificate certificate) {
        if (certificate.getType() == SSLHostConfigCertificate.Type.UNDEFINED) {
            return "default";
        }
        return certificate.getType().name();
    }


    /**
     * The tree entries of the pre-shared keys of an SSL host configuration, addressed by a positional index in the
     * order they were added.
     */
    protected List<Map<String, Object>> preSharedKeyChildren(SSLHostConfig hostConfig, String parentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        SSLHostConfigPreSharedKey[] preSharedKeys =
                hostConfig.getPreSharedKeys().toArray(new SSLHostConfigPreSharedKey[0]);
        for (int i = 0; i < preSharedKeys.length; i++) {
            SSLHostConfigPreSharedKey preSharedKey = preSharedKeys[i];
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", parentId + "/preSharedKey/" + i);
            entry.put("type", "preSharedKey");
            entry.put("className", preSharedKey.getClass().getName());
            entry.put("name", preSharedKeyLabel(preSharedKey));
            entry.put("children", new ArrayList<Map<String, Object>>());
            result.add(entry);
        }
        return result;
    }


    /**
     * A human readable name for a pre-shared key configuration: its identity.
     */
    protected static String preSharedKeyLabel(SSLHostConfigPreSharedKey preSharedKey) {
        String label = preSharedKey.getIdentity();
        if (label == null || label.isEmpty()) {
            label = preSharedKey.getClass().getSimpleName();
        }
        return label;
    }


    /**
     * The tree entries of the upgrade protocols of a connector, addressed by a positional index in the order they were
     * added.
     */
    protected List<Map<String, Object>> upgradeProtocolChildren(Connector connector, String parentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        UpgradeProtocol[] upgradeProtocols = connector.findUpgradeProtocols();
        for (int i = 0; i < upgradeProtocols.length; i++) {
            UpgradeProtocol upgradeProtocol = upgradeProtocols[i];
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", parentId + "/upgradeProtocol/" + i);
            entry.put("type", "upgradeProtocol");
            entry.put("className", upgradeProtocol.getClass().getName());
            entry.put("name", upgradeProtocolLabel(upgradeProtocol));
            entry.put("children", new ArrayList<Map<String, Object>>());
            result.add(entry);
        }
        return result;
    }


    /**
     * A human readable name for an upgrade protocol: the ALPN name (e.g. h2), then the HTTP upgrade name (e.g. h2c),
     * then the simple name of the class.
     */
    protected static String upgradeProtocolLabel(UpgradeProtocol upgradeProtocol) {
        String label = upgradeProtocol.getAlpnName();
        if (label == null || label.isEmpty()) {
            label = upgradeProtocol.getHttpUpgradeName(false);
        }
        if (label == null || label.isEmpty()) {
            label = upgradeProtocol.getHttpUpgradeName(true);
        }
        if (label == null || label.isEmpty()) {
            label = upgradeProtocol.getClass().getSimpleName();
        }
        return label;
    }


    protected List<Map<String, Object>> childrenOfConnectors(StandardService service, String parentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        Connector[] connectors = service.findConnectors();
        for (int i = 0; i < connectors.length; i++) {
            Connector connector = connectors[i];
            String id = parentId + "/connector/" + i;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", id);
            entry.put("type", "connector");
            entry.put("className", connector.getClass().getName());
            entry.put("name", connectorLabel(connector));
            entry.put("state", connector.getState().toString());
            List<Map<String, Object>> connectorChildren = sslHostConfigChildren(connector, id);
            connectorChildren.addAll(upgradeProtocolChildren(connector, id));
            entry.put("children", connectorChildren);
            result.add(entry);
        }
        return result;
    }


    /**
     * A human readable name for a connector: the configured protocol when known, otherwise the simple name of the
     * protocol handler class.
     */
    protected static String connectorLabel(Connector connector) {
        String protocol = connector.getProtocol();
        if (protocol == null || protocol.isEmpty()) {
            String handler = connector.getProtocolHandlerClassName();
            int separator = handler != null ? handler.lastIndexOf('.') : -1;
            protocol = (separator >= 0) ? handler.substring(separator + 1) : "connector";
        }
        return protocol + " (port " + connector.getPort() + ")";
    }


    protected List<Map<String, Object>> childrenOfExecutors(StandardService service, String parentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Executor executor : service.findExecutors()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", parentId + "/executor/" + enc(executor.getName()));
            entry.put("type", "executor");
            entry.put("className", executor.getClass().getName());
            entry.put("name", executor.getName());
            entry.put("state", executor.getState().toString());
            entry.put("children", new ArrayList<Map<String, Object>>());
            result.add(entry);
        }
        return result;
    }


    protected List<Map<String, Object>> childrenOfValves(Container container, String parentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        Valve[] valves = container.getPipeline().getValves();
        for (int i = 0; i < valves.length; i++) {
            Valve valve = valves[i];
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", parentId + "/valve/" + i);
            entry.put("type", "valve");
            entry.put("className", valve.getClass().getName());
            entry.put("name", valve.getClass().getSimpleName());
            entry.put("basic", Boolean.valueOf(i == 0));
            entry.put("children", new ArrayList<Map<String, Object>>());
            result.add(entry);
        }
        return result;
    }


    protected List<Map<String, Object>> childrenOfListeners(LifecycleBase component, String parentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        LifecycleListener[] listeners = component.findLifecycleListeners();
        for (int i = 0; i < listeners.length; i++) {
            LifecycleListener listener = listeners[i];
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", parentId + "/listener/" + i);
            entry.put("type", "listener");
            entry.put("className", listener.getClass().getName());
            entry.put("name", listener.getClass().getSimpleName());
            entry.put("children", new ArrayList<Map<String, Object>>());
            result.add(entry);
        }
        return result;
    }


    /**
     * The realm directly attached to the container, or {@code null} when the container has none of its own.
     * {@code getRealm()} falls back to the parent container's realm, so the fallback (the same comparison storeconfig
     * uses) must be excluded.
     */
    protected static Realm ownRealm(Container container) {
        Realm realm = container.getRealm();
        if (realm == null) {
            return null;
        }
        Container parent = container.getParent();
        if (parent != null && realm == parent.getRealm()) {
            return null;
        }
        return realm;
    }


    /**
     * The realm the container resolves to (via {@code getRealm()}) once its own realm has been detached.
     */
    protected static Realm fallbackRealm(Container container) {
        Container parent = container.getParent();
        return parent != null ? parent.getRealm() : null;
    }


    /**
     * The tree entry for the realm directly attached to the container, if it has one.
     */
    protected List<Map<String, Object>> childrenOfRealm(Container container, String parentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        Realm realm = ownRealm(container);
        if (realm != null) {
            result.add(realmEntry(realm, parentId + "/realm/0"));
        }
        return result;
    }


    /**
     * The tree entries of the (sub) realms nested in the given realm. Only combined realms hold sub realms.
     */
    protected List<Map<String, Object>> childrenOfSubRealms(Realm realm, String parentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(realm instanceof CombinedRealm combined)) {
            return result;
        }
        Realm[] nested = combined.getNestedRealms();
        for (int i = 0; i < nested.length; i++) {
            result.add(realmEntry(nested[i], parentId + "/realm/" + i));
        }
        return result;
    }


    protected Map<String, Object> realmEntry(Realm realm, String id) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("type", "realm");
        entry.put("className", realm.getClass().getName());
        entry.put("name", realm.getClass().getSimpleName());
        if (realm instanceof Lifecycle lifecycle) {
            entry.put("state", lifecycle.getState().toString());
        }
        entry.put("children", childrenOfSubRealms(realm, id));
        return entry;
    }


    /**
     * The tree entries of the sub components of a context (manager, resources, loader, cookie processor). A running
     * context always has all of them (the defaults are created at context start), so they are always shown once the
     * context has started.
     */
    protected List<Map<String, Object>> childrenOfContextComponents(Context context, String parentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        Manager manager = context.getManager();
        if (manager != null) {
            result.add(build(manager, parentId + "/manager/0", "manager"));
        }
        WebResourceRoot resources = context.getResources();
        if (resources != null) {
            result.add(build(resources, parentId + "/resources/0", "resources"));
        }
        Loader loader = context.getLoader();
        if (loader != null) {
            result.add(build(loader, parentId + "/loader/0", "loader"));
        }
        CookieProcessor cookieProcessor = context.getCookieProcessor();
        if (cookieProcessor != null) {
            result.add(build(cookieProcessor, parentId + "/cookieProcessor/0", "cookieProcessor"));
        }
        return result;
    }


    /**
     * The tree entry of the session id generator of a manager, if it has one (a running manager always does; the
     * manager creates a default one at start).
     */
    protected List<Map<String, Object>> sessionIdGeneratorChildren(Manager manager, String parentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        SessionIdGenerator generator = manager.getSessionIdGenerator();
        if (generator != null) {
            result.add(build(generator, parentId + "/sessionIdGenerator/0", "sessionIdGenerator"));
        }
        return result;
    }


    /**
     * The tree entries of the JNDI entries of a {@code NamingResourcesImpl}, keyed by their JNDI names. A server level
     * instance (container is the {@code Server}) does not expose resource links: they are only part of a context JNDI
     * environment and are not parsed from {@code <GlobalNamingResources>}.
     */
    protected List<Map<String, Object>> childrenOfNamingResources(NamingResourcesImpl namingResources, String parentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        boolean global = namingResources.getContainer() instanceof Server;
        for (ContextResource resource : namingResources.findResources()) {
            result.add(namingEntry(resource, "resource", parentId));
        }
        if (!global) {
            for (ContextResourceLink link : namingResources.findResourceLinks()) {
                result.add(namingEntry(link, "resourceLink", parentId));
            }
        }
        for (ContextResourceEnvRef resourceEnvRef : namingResources.findResourceEnvRefs()) {
            result.add(namingEntry(resourceEnvRef, "resourceEnvRef", parentId));
        }
        for (ContextEnvironment environment : namingResources.findEnvironments()) {
            result.add(namingEntry(environment, "environment", parentId));
        }
        for (ContextEjb ejb : namingResources.findEjbs()) {
            result.add(namingEntry(ejb, "ejb", parentId));
        }
        for (ContextLocalEjb localEjb : namingResources.findLocalEjbs()) {
            result.add(namingEntry(localEjb, "localEjb", parentId));
        }
        for (ContextService service : namingResources.findServices()) {
            result.add(namingEntry(service, "serviceRef", parentId));
        }
        return result;
    }


    protected Map<String, Object> namingEntry(ResourceBase entry, String type, String parentId) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", parentId + "/" + type + "/" + enc(entry.getName()));
        node.put("type", type);
        node.put("className", entry.getClass().getName());
        node.put("name", entry.getName());
        node.put("children", new ArrayList<Map<String, Object>>());
        return node;
    }


    // ------------------------------------------------------ Cluster children


    /**
     * The cluster directly attached to the container, or {@code null} when the container has none of its own.
     * {@code getCluster()} falls back to the parent container's cluster, so the fallback (the same comparison
     * storeconfig uses) must be excluded.
     */
    protected static Cluster ownCluster(Container container) {
        Cluster cluster = container.getCluster();
        if (cluster == null) {
            return null;
        }
        Container parent = container.getParent();
        if (parent != null && cluster == parent.getCluster()) {
            return null;
        }
        return cluster;
    }


    /**
     * The tree entry for the cluster directly attached to the container, if it has one.
     */
    protected List<Map<String, Object>> childrenOfCluster(Container container, String parentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        Cluster cluster = ownCluster(container);
        if (cluster != null) {
            result.add(build(cluster, parentId + "/cluster/0", "cluster"));
        }
        return result;
    }


    /**
     * The tree children of a cluster: channel, deployer, valves, manager template (SimpleTcpCluster), lifecycle
     * listeners and cluster listeners.
     */
    protected List<Map<String, Object>> clusterChildren(CatalinaCluster cluster, String parentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        Channel channel = cluster.getChannel();
        if (channel != null) {
            result.add(build(channel, parentId + "/channel/0", "channel"));
        }
        ClusterDeployer deployer = cluster.getClusterDeployer();
        if (deployer != null) {
            result.add(build(deployer, parentId + "/deployer/0", "deployer"));
        }
        Valve[] valves = cluster.getValves();
        for (int i = 0; i < valves.length; i++) {
            result.add(build(valves[i], parentId + "/clusterValve/" + i, "clusterValve"));
        }
        if (cluster instanceof SimpleTcpCluster tcp) {
            ClusterManager manager = tcp.getManagerTemplate();
            if (manager != null) {
                result.add(build(manager, parentId + "/clusterManager/0", "clusterManager"));
            }
            if (cluster instanceof LifecycleBase base) {
                LifecycleListener[] listeners = base.findLifecycleListeners();
                for (int i = 0; i < listeners.length; i++) {
                    result.add(build(listeners[i], parentId + "/listener/" + i, "listener"));
                }
            }
            int idx = 0;
            for (ClusterListener listener : tcp.findClusterListeners()) {
                if (listener == deployer) {
                    // The deployer is already shown as its own child.
                    continue;
                }
                result.add(build(listener, parentId + "/clusterListener/" + idx, "clusterListener"));
                idx++;
            }
        }
        return result;
    }


    /**
     * The tree children of a channel: membership, sender, receiver and the (user configured) interceptors.
     */
    protected List<Map<String, Object>> channelChildren(ManagedChannel channel, String parentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        MembershipService membership = channel.getMembershipService();
        if (membership != null) {
            result.add(build(membership, parentId + "/membership/0", "membership"));
        }
        ChannelSender sender = channel.getChannelSender();
        if (sender != null) {
            result.add(build(sender, parentId + "/sender/0", "sender"));
        }
        ChannelReceiver receiver = channel.getChannelReceiver();
        if (receiver != null) {
            result.add(build(receiver, parentId + "/receiver/0", "receiver"));
        }
        Iterator<ChannelInterceptor> interceptors = channel.getInterceptors();
        int i = 0;
        while (interceptors.hasNext()) {
            result.add(build(interceptors.next(), parentId + "/interceptor/" + i, "interceptor"));
            i++;
        }
        return result;
    }


    /**
     * The tree children of a channel sender: the transport (a replication transmitter holds one).
     */
    protected List<Map<String, Object>> senderChildren(ChannelSender sender, String parentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (sender instanceof ReplicationTransmitter transmitter) {
            MultiPointSender transport = transmitter.getTransport();
            if (transport != null) {
                result.add(build(transport, parentId + "/transport/0", "transport"));
            }
        }
        return result;
    }


    /**
     * The tree children of a membership service: the static members when the service is a static membership service.
     */
    protected List<Map<String, Object>> membershipChildren(MembershipService membership, String parentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (membership instanceof StaticMembershipService sms) {
            Member local = sms.getLocalMember(false);
            if (local != null) {
                result.add(build(local, parentId + "/localMember/0", "member"));
            }
            List<StaticMember> members = sms.getStaticMembers();
            for (int i = 0; i < members.size(); i++) {
                result.add(build(members.get(i), parentId + "/member/" + i, "member"));
            }
        }
        return result;
    }


    /**
     * The tree children of an interceptor: the local member when the interceptor is a static membership interceptor.
     */
    protected List<Map<String, Object>> interceptorChildren(ChannelInterceptor interceptor, String parentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (interceptor instanceof StaticMembershipInterceptor smi) {
            Member local = smi.getLocalMember(false);
            if (local != null) {
                result.add(build(local, parentId + "/localMember/0", "member"));
            }
        }
        return result;
    }


    protected static String memberLabel(Member member) {
        byte[] hostBytes = member.getHost();
        String host = hostBytes == null ? null : new String(hostBytes);
        if (host == null || host.isEmpty()) {
            return member.getName();
        }
        return host + ":" + member.getPort();
    }


    protected static String displayName(Object component, String type) {

        // Same reasoning as in build(): the node type identifies the component
        // kind, so dispatch on it instead of an instanceof chain.
        return switch (type) {
            case "service" -> ((Service) component).getName();
            case "engine" -> ((Engine) component).getName();
            case "host" -> ((Host) component).getName();
            case "context" -> {
                // The root context has the empty path; display it as "/".
                String path = ((Context) component).getPath();
                yield path.isEmpty() ? "/" : path;
            }
            case "wrapper" -> ((Wrapper) component).getName();
            case "executor" -> ((Executor) component).getName();
            case "connector" -> connectorLabel((Connector) component);
            case "sslHostConfig" -> ((SSLHostConfig) component).getHostName();
            case "certificate" -> certificateLabel((SSLHostConfigCertificate) component);
            case "preSharedKey" -> preSharedKeyLabel((SSLHostConfigPreSharedKey) component);
            case "upgradeProtocol" -> upgradeProtocolLabel((UpgradeProtocol) component);
            case "member" -> memberLabel((Member) component);
            default -> isNamingEntry(type) ? ((ResourceBase) component).getName()
                    : component.getClass().getSimpleName();
        };
    }


    // --------------------------------------------------------- Node details


    protected Map<String, Object> node(NodeRef ref) throws ConfigException {

        Object component = ref.component;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", ref.id);
        out.put("type", ref.type);
        out.put("name", ref.aliasValue != null ? ref.aliasValue : displayName(component, ref.type));
        out.put("className", component.getClass().getName());
        if (component instanceof Lifecycle lifecycle) {
            out.put("state", lifecycle.getState().toString());
        }
        if ("context".equals(ref.type) && component == selfContext) {
            out.put("self", Boolean.TRUE);
        }
        // Whether a lifecycle listener can be added to this component.
        // Not derivable on the client for a listener node, whose class may
        // or may not implement Lifecycle (e.g. a valve used as a listener).
        // An alias is excluded: its component is the parent host (an alias
        // is just a name, not a component that holds listeners).
        out.put("acceptsListener",
                !"alias".equals(ref.type) && !CLUSTER_SUB_TYPES.contains(ref.type) && component instanceof Lifecycle);
        // Whether the component has a lifecycle (and therefore supports
        // the start / stop / restart operations).
        if (component instanceof Lifecycle) {
            out.put("lifecycle", Boolean.TRUE);
            // Whether a start or stop of the component would interrupt
            // access to this web application (only a restart is allowed
            // for it; the client reconnects at the end of the operation).
            // Not derivable on the client for the service, the engine
            // and the connector (which of them route the requests of
            // this web application is a server-side decision).
            if (affectsSelf(ref)) {
                out.put("affectsSelf", Boolean.TRUE);
            }
        }

        if ("connector".equals(ref.type) && component instanceof Connector connector) {
            out.put("sslEnabled", isSslEnabled(connector));
        }
        if ("realm".equals(ref.type)) {
            // Only combined realms can hold (sub) realms. Not derivable on
            // the client from the class name alone (subclasses).
            out.put("acceptsSubRealm", component instanceof CombinedRealm);
        }
        if ("namingResources".equals(ref.type) && component instanceof NamingResourcesImpl namingResources) {
            // The server level node does not accept resourceLink entries.
            out.put("global", Boolean.valueOf(namingResources.getContainer() instanceof Server));
        }
        if ("sslHostConfig".equals(ref.type) && component instanceof SSLHostConfig hostConfig) {
            Connector owner = connectorOfSsl(hostConfig);
            if (owner != null) {
                try {
                    out.put("isDefault", Boolean
                            .valueOf(endpointOf(owner).getDefaultSSLHostConfigName().equals(hostConfig.getHostName())));
                } catch (ConfigException e) {
                    // No endpoint: leave isDefault out.
                }
            }
        }

        List<Map<String, Object>> properties = new ArrayList<>();
        // Some components have no modeler descriptor; their attribute
        // list is defined explicitly. They are handled before the
        // descriptor branch because the registry falls back to an
        // introspected ManagedBean for them, which would otherwise
        // shadow the (more complete) explicit list.
        ExplicitSpec explicit = explicitAttributes(component, ref.type);
        if (!explicit.attributes().isEmpty()) {
            // Factory options of a resource are localized per factory (the
            // same parameter name may carry a different text depending on
            // the factory that consumes it).
            String paramScope = "resource".equals(ref.type) && component instanceof ContextResource resource
                    ? factoryKey(effectiveFactory(resource)) : null;
            for (ExplicitAttribute attribute : explicit.attributes()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", attribute.getName());
                entry.put("type", attribute.getType());
                String description = attributeDescription(explicit.scope(), paramScope, attribute);
                if (description != null) {
                    entry.put("description", description);
                }
                entry.put("writable", Boolean.valueOf(attribute.isWritable()));
                if (attribute.isParam()) {
                    entry.put("param", Boolean.TRUE);
                }
                entry.put("value", jsonSafe(readExplicitValue(component, attribute)));
                properties.add(entry);
            }
        } else {
            ManagedBean descriptor = descriptor(component);
            if (descriptor != null) {
                for (AttributeInfo attribute : descriptor.getAttributes()) {
                    if (!attribute.isReadable()) {
                        continue;
                    }
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", attribute.getName());
                    entry.put("type", attribute.getType());
                    // The bundle wins when it defines the attribute (the
                    // hook that lets the standard, descriptor-provided
                    // descriptions be localized); otherwise the descriptor
                    // text is used as-is.
                    String description = Strings.sm().getString("manager2.attr." + ref.type + "." + attribute.getName());
                    if (description == null) {
                        description = attribute.getDescription();
                    }
                    if (description != null) {
                        entry.put("description", description);
                    }
                    entry.put("writable",
                            Boolean.valueOf(attribute.isWriteable() && EDITABLE_TYPES.contains(attribute.getType())));
                    entry.put("value", jsonSafe(readValue(component, attribute)));
                    properties.add(entry);
                }
            }
        }
        if ("connector".equals(ref.type) && component instanceof Connector connector) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", "sslEnabled");
            entry.put("type", "boolean");
            entry.put("writable", Boolean.FALSE);
            entry.put("value", Boolean.valueOf(isSslEnabled(connector)));
            properties.add(entry);
        }
        out.put("properties", properties);

        // The direct children of this node (one level, no recursion)
        Map<String, Object> built = build(component, ref.id, ref.type);
        out.put("children", built.get("children"));
        return out;
    }


    /**
     * The modeler descriptor (MBean contract) of the component's class, or {@code null} for classes without a
     * descriptor.
     */
    protected static ManagedBean descriptor(Object component) {
        return Registry.getRegistry(null).findManagedBean(component.getClass().getName());
    }


    protected static AttributeInfo findAttribute(ManagedBean descriptor, String name) {
        if (descriptor != null) {
            for (AttributeInfo attribute : descriptor.getAttributes()) {
                if (attribute.getName().equals(name)) {
                    return attribute;
                }
            }
        }
        return null;
    }


    protected static Object readValue(Object component, AttributeInfo attribute) {
        String method = attribute.getGetMethod();
        if (method == null) {
            method = ("boolean".equals(attribute.getType()) || attribute.isIs())
                    ? "is" + capitalize(attribute.getName())
                    : "get" + capitalize(attribute.getName());
        }
        try {
            Method m = component.getClass().getMethod(method);
            return m.invoke(component);
        } catch (Exception e) {
            return null;
        }
    }


    // ------------------------------------- Explicit component attributes


    /**
     * One explicitly defined attribute of a component whose class has no modeler MBean descriptor (the TLS components
     * {@code SSLHostConfig}/{@code SSLHostConfigCertificate} and the context sub components {@code WebappLoader},
     * {@code CookieProcessorBase} and {@code SessionIdGeneratorBase}), defined here instead of being derived.
     * Descriptions are not carried here: they are looked up from the message bundle by scope and attribute name (see
     * {@link #attributeDescription}).
     */
    protected static final class ExplicitAttribute {

        protected final String name;

        protected final String type;

        protected final boolean writable;

        protected final boolean param;


        ExplicitAttribute(String name, String type, boolean writable) {
            this(name, type, writable, false);
        }


        ExplicitAttribute(String name, String type, boolean writable, boolean param) {
            this.name = name;
            this.type = type;
            this.writable = writable;
            this.param = param;
        }


        String getName() {
            return name;
        }


        String getType() {
            return type;
        }


        boolean isWritable() {
            return writable;
        }


        /**
         * {@code true} for attributes that are not bean properties but string parameters of the generic property map of
         * a JNDI entry (the {@code ResourceBase} properties).
         */
        boolean isParam() {
            return param;
        }
    }


    protected static final List<ExplicitAttribute> SSL_HOST_CONFIG_ATTRIBUTES = List.of(
            new ExplicitAttribute("hostName", "java.lang.String", false),
            new ExplicitAttribute("protocols", "java.lang.String", true),
            new ExplicitAttribute("certificateVerification", "java.lang.String", true),
            new ExplicitAttribute("certificateVerificationDepth", "int", true),
            new ExplicitAttribute("ciphers", "java.lang.String", true),
            new ExplicitAttribute("cipherSuites", "java.lang.String", true),
            new ExplicitAttribute("honorCipherOrder", "boolean", true),
            new ExplicitAttribute("sessionCacheSize", "int", true),
            new ExplicitAttribute("sessionTimeout", "int", true),
            new ExplicitAttribute("groups", "java.lang.String", true),
            new ExplicitAttribute("keyManagerAlgorithm", "java.lang.String", true),
            new ExplicitAttribute("sslProtocol", "java.lang.String", true),
            new ExplicitAttribute("revocationEnabled", "boolean", true),
            new ExplicitAttribute("trustManagerClassName", "java.lang.String", true),
            new ExplicitAttribute("truststoreAlgorithm", "java.lang.String", true),
            new ExplicitAttribute("truststoreFile", "java.lang.String", true),
            new ExplicitAttribute("truststorePassword", "java.lang.String", true),
            new ExplicitAttribute("truststoreProvider", "java.lang.String", true),
            new ExplicitAttribute("truststoreType", "java.lang.String", true),
            new ExplicitAttribute("caCertificateFile", "java.lang.String", true),
            new ExplicitAttribute("caCertificatePath", "java.lang.String", true),
            new ExplicitAttribute("certificateRevocationListPath", "java.lang.String", true),
            new ExplicitAttribute("disableCompression", "boolean", true),
            new ExplicitAttribute("disableSessionTickets", "boolean", true),
            new ExplicitAttribute("insecureRenegotiation", "boolean", true));


    protected static final List<ExplicitAttribute> CERTIFICATE_ATTRIBUTES = List.of(
            new ExplicitAttribute("type", "java.lang.String", false),
            new ExplicitAttribute("certificateKeystoreFile", "java.lang.String", true),
            new ExplicitAttribute("certificateKeystorePassword", "java.lang.String", true),
            new ExplicitAttribute("certificateKeystorePasswordFile", "java.lang.String", true),
            new ExplicitAttribute("certificateKeystoreType", "java.lang.String", true),
            new ExplicitAttribute("certificateKeystoreProvider", "java.lang.String", true),
            new ExplicitAttribute("certificateKeyAlias", "java.lang.String", true),
            new ExplicitAttribute("certificateKeyPassword", "java.lang.String", true),
            new ExplicitAttribute("certificateKeyPasswordFile", "java.lang.String", true),
            new ExplicitAttribute("certificateFile", "java.lang.String", true),
            new ExplicitAttribute("certificateChainFile", "java.lang.String", true),
            new ExplicitAttribute("certificateKeyFile", "java.lang.String", true));

    // The editable attributes of a pre-shared key configuration (the
    // SSLHostConfigPreSharedKey class has no modeler descriptor). The key
    // is the hexadecimal string form the setter and the store use.

    protected static final List<ExplicitAttribute> PRE_SHARED_KEY_ATTRIBUTES = List.of(
            new ExplicitAttribute("identity", "java.lang.String", true),
            new ExplicitAttribute("key", "java.lang.String", true),
            new ExplicitAttribute("digest", "java.lang.String", true));


    protected static final List<ExplicitAttribute> WEBAPP_LOADER_ATTRIBUTES = List.of(
            new ExplicitAttribute("delegate", "boolean", true),
            new ExplicitAttribute("loaderClass", "java.lang.String", true),
            new ExplicitAttribute("jakartaConverter", "java.lang.String", true));


    protected static final List<ExplicitAttribute> COOKIE_PROCESSOR_ATTRIBUTES = List.of(
            new ExplicitAttribute("cookiesWithoutEquals", "java.lang.String", true),
            new ExplicitAttribute("sameSiteCookies", "java.lang.String", true),
            new ExplicitAttribute("partitioned", "boolean", true));


    protected static final List<ExplicitAttribute> SESSION_ID_GENERATOR_ATTRIBUTES = List.of(
            new ExplicitAttribute("secureRandomClass", "java.lang.String", true),
            new ExplicitAttribute("jvmRoute", "java.lang.String", true),
            new ExplicitAttribute("sessionIdLength", "int", true));


    // The HTTP/2 upgrade protocol has no modeler descriptor either. Only the
    // common, documented knobs are listed (like the cluster channel
    // components). All changes take effect when the owning connector is
    // (re)started, the same as the protocol itself.
    protected static final List<ExplicitAttribute> HTTP2_PROTOCOL_ATTRIBUTES = List.of(
            new ExplicitAttribute("readTimeout", "long", true),
            new ExplicitAttribute("writeTimeout", "long", true),
            new ExplicitAttribute("keepAliveTimeout", "long", true),
            new ExplicitAttribute("streamReadTimeout", "long", true),
            new ExplicitAttribute("streamWriteTimeout", "long", true),
            new ExplicitAttribute("maxConcurrentStreams", "long", true),
            new ExplicitAttribute("maxConcurrentStreamExecution", "int", true),
            new ExplicitAttribute("initialWindowSize", "int", true),
            new ExplicitAttribute("useSendfile", "boolean", true),
            new ExplicitAttribute("allowSchemeMismatch", "boolean", true),
            new ExplicitAttribute("maxHeaderCount", "int", true),
            new ExplicitAttribute("maxHeaderSize", "int", false),
            new ExplicitAttribute("maxTrailerCount", "int", true),
            new ExplicitAttribute("maxTrailerSize", "int", false),
            new ExplicitAttribute("overheadCountFactor", "int", true),
            new ExplicitAttribute("overheadResetFactor", "int", true),
            new ExplicitAttribute("overheadContinuationThreshold", "int", true),
            new ExplicitAttribute("overheadDataThreshold", "int", true),
            new ExplicitAttribute("overheadWindowUpdateThreshold", "int", true),
            new ExplicitAttribute("initiatePingDisabled", "boolean", true),
            new ExplicitAttribute("discardRequestsAndResponses", "boolean", true),
            new ExplicitAttribute("drainTimeout", "long", true));


    // --------------------------------- Cluster channel attributes

    // The sub components of a cluster channel (GroupChannel, the multicast
    // membership service, the receiver, the sender transport and the
    // interceptors) have no modeler descriptors, so their attributes are
    // defined explicitly. Only the common, documented knobs are listed.

    protected static final List<ExplicitAttribute> CHANNEL_ATTRIBUTES = List.of(
            new ExplicitAttribute("name", "java.lang.String", true),
            new ExplicitAttribute("heartbeat", "boolean", true),
            new ExplicitAttribute("heartbeatSleeptime", "long", true),
            new ExplicitAttribute("jmxDomain", "java.lang.String", true),
            new ExplicitAttribute("jmxPrefix", "java.lang.String", true),
            new ExplicitAttribute("optionCheck", "boolean", true));

    protected static final List<ExplicitAttribute> MCAST_ATTRIBUTES = List.of(
            new ExplicitAttribute("address", "java.lang.String", true),
            new ExplicitAttribute("port", "int", true),
            new ExplicitAttribute("frequency", "long", true),
            new ExplicitAttribute("dropTime", "long", true),
            new ExplicitAttribute("ttl", "int", true),
            new ExplicitAttribute("soTimeout", "int", true),
            new ExplicitAttribute("recoveryEnabled", "boolean", true),
            new ExplicitAttribute("recoverySleepTime", "long", true),
            new ExplicitAttribute("localLoopbackDisabled", "boolean", true));

    protected static final List<ExplicitAttribute> RECEIVER_ATTRIBUTES = List.of(
            new ExplicitAttribute("port", "int", true),
            new ExplicitAttribute("autoBind", "int", true),
            new ExplicitAttribute("address", "java.lang.String", true),
            new ExplicitAttribute("udpPort", "int", true),
            new ExplicitAttribute("maxThreads", "int", true),
            new ExplicitAttribute("minThreads", "int", true),
            new ExplicitAttribute("selectorTimeout", "long", true),
            new ExplicitAttribute("tcpNoDelay", "boolean", true),
            new ExplicitAttribute("soKeepAlive", "boolean", true),
            new ExplicitAttribute("soReuseAddress", "boolean", true));

    protected static final List<ExplicitAttribute> TRANSPORT_ATTRIBUTES = List.of(
            new ExplicitAttribute("poolSize", "int", true),
            new ExplicitAttribute("timeout", "long", true),
            new ExplicitAttribute("maxRetryAttempts", "int", true),
            new ExplicitAttribute("udpPort", "int", true),
            new ExplicitAttribute("directBuffer", "boolean", true),
            new ExplicitAttribute("tcpNoDelay", "boolean", true),
            new ExplicitAttribute("soKeepAlive", "boolean", true),
            new ExplicitAttribute("soReuseAddress", "boolean", true));

    protected static final List<ExplicitAttribute> MESSAGE_DISPATCH_INTERCEPTOR_ATTRIBUTES = List.of(
            new ExplicitAttribute("maxQueueSize", "long", true),
            new ExplicitAttribute("maxThreads", "int", true),
            new ExplicitAttribute("maxSpareThreads", "int", true),
            new ExplicitAttribute("keepAliveTime", "long", true),
            new ExplicitAttribute("useDeepClone", "boolean", true),
            new ExplicitAttribute("alwaysSend", "boolean", true));

    protected static final List<ExplicitAttribute> TCP_FAILURE_DETECTOR_ATTRIBUTES = List.of(
            new ExplicitAttribute("connectTimeout", "long", true),
            new ExplicitAttribute("readTestTimeout", "long", true),
            new ExplicitAttribute("performSendTest", "boolean", true),
            new ExplicitAttribute("performReadTest", "boolean", true),
            new ExplicitAttribute("removeSuspectsTimeout", "int", true));

    protected static final List<ExplicitAttribute> INTERCEPTOR_ATTRIBUTES = List.of(new ExplicitAttribute("optionFlag", "int", true));


    // ------------------------------------------------- JNDI entry attributes

    // The JNDI entry types (children of a namingResources node).

    protected static final List<ExplicitAttribute> RESOURCE_ATTRIBUTES = List.of(
            new ExplicitAttribute("name", "java.lang.String", true),
            new ExplicitAttribute("type", "java.lang.String", true),
            new ExplicitAttribute("auth", "java.lang.String", true),
            new ExplicitAttribute("scope", "java.lang.String", true),
            new ExplicitAttribute("singleton", "boolean", true),
            new ExplicitAttribute("closeMethod", "java.lang.String", true),
            new ExplicitAttribute("lookupName", "java.lang.String", true),
            new ExplicitAttribute("description", "java.lang.String", true));

    protected static final List<ExplicitAttribute> RESOURCE_LINK_ATTRIBUTES = List.of(
            new ExplicitAttribute("name", "java.lang.String", true),
            new ExplicitAttribute("type", "java.lang.String", true),
            new ExplicitAttribute("global", "java.lang.String", true),
            new ExplicitAttribute("factory", "java.lang.String", true),
            new ExplicitAttribute("description", "java.lang.String", true));

    protected static final List<ExplicitAttribute> RESOURCE_ENV_REF_ATTRIBUTES = List.of(
            new ExplicitAttribute("name", "java.lang.String", true),
            new ExplicitAttribute("type", "java.lang.String", true),
            new ExplicitAttribute("override", "boolean", true),
            new ExplicitAttribute("description", "java.lang.String", true));

    protected static final List<ExplicitAttribute> ENVIRONMENT_ATTRIBUTES = List.of(
            new ExplicitAttribute("name", "java.lang.String", true),
            new ExplicitAttribute("type", "java.lang.String", true),
            new ExplicitAttribute("value", "java.lang.String", true),
            new ExplicitAttribute("override", "boolean", true),
            new ExplicitAttribute("description", "java.lang.String", true));

    protected static final List<ExplicitAttribute> EJB_ATTRIBUTES = List.of(
            new ExplicitAttribute("name", "java.lang.String", true),
            new ExplicitAttribute("type", "java.lang.String", true),
            new ExplicitAttribute("home", "java.lang.String", true),
            new ExplicitAttribute("link", "java.lang.String", true),
            new ExplicitAttribute("remote", "java.lang.String", true),
            new ExplicitAttribute("description", "java.lang.String", true));

    protected static final List<ExplicitAttribute> LOCAL_EJB_ATTRIBUTES = List.of(
            new ExplicitAttribute("name", "java.lang.String", true),
            new ExplicitAttribute("type", "java.lang.String", true),
            new ExplicitAttribute("local", "java.lang.String", true),
            new ExplicitAttribute("home", "java.lang.String", true),
            new ExplicitAttribute("link", "java.lang.String", true),
            new ExplicitAttribute("description", "java.lang.String", true));

    protected static final List<ExplicitAttribute> SERVICE_ATTRIBUTES = List.of(
            new ExplicitAttribute("name", "java.lang.String", true),
            new ExplicitAttribute("type", "java.lang.String", true),
            new ExplicitAttribute("interface", "java.lang.String", true),
            new ExplicitAttribute("displayname", "java.lang.String", true),
            new ExplicitAttribute("wsdlfile", "java.lang.String", true),
            new ExplicitAttribute("description", "java.lang.String", true));

    // The first party JNDI ObjectFactory implementations shipped with
    // Tomcat and the string parameters (RefAddr keys) each one consumes.

    protected static final String BASIC_DATA_SOURCE_FACTORY = "org.apache.tomcat.dbcp.dbcp2.BasicDataSourceFactory";

    protected static final String MEMORY_USER_DATABASE_FACTORY = "org.apache.catalina.users.MemoryUserDatabaseFactory";

    protected static final String DATA_SOURCE_USER_DATABASE_FACTORY = "org.apache.catalina.users.DataSourceUserDatabaseFactory";

    protected static final String PER_USER_POOL_DATA_SOURCE_FACTORY = "org.apache.tomcat.dbcp.dbcp2.datasources.PerUserPoolDataSourceFactory";

    protected static final String SHARED_POOL_DATA_SOURCE_FACTORY = "org.apache.tomcat.dbcp.dbcp2.datasources.SharedPoolDataSourceFactory";

    protected static final List<ExplicitAttribute> BASIC_DATA_SOURCE_FACTORY_OPTIONS = List.of(
            option("defaultAutoCommit", "boolean"),
            option("defaultReadOnly", "boolean"),
            option("defaultTransactionIsolation", "java.lang.String"),
            option("defaultCatalog", "java.lang.String"),
            option("defaultSchema", "java.lang.String"),
            option("cacheState", "boolean"),
            option("driverClassName", "java.lang.String"),
            option("lifo", "boolean"),
            option("maxTotal", "int"),
            option("maxIdle", "int"),
            option("minIdle", "int"),
            option("initialSize", "int"),
            option("maxWaitMillis", "long"),
            option("testOnCreate", "boolean"),
            option("testOnBorrow", "boolean"),
            option("testOnReturn", "boolean"),
            option("timeBetweenEvictionRunsMillis", "long"),
            option("numTestsPerEvictionRun", "int"),
            option("minEvictableIdleTimeMillis", "long"),
            option("softMinEvictableIdleTimeMillis", "long"),
            option("evictionPolicyClassName", "java.lang.String"),
            option("testWhileIdle", "boolean"),
            option("password", "java.lang.String"),
            option("url", "java.lang.String"),
            option("username", "java.lang.String"),
            option("validationQuery", "java.lang.String"),
            option("validationQueryTimeout", "long"),
            option("connectionInitSqls", "java.lang.String"),
            option("accessToUnderlyingConnectionAllowed", "boolean"),
            option("removeAbandonedOnBorrow", "boolean"),
            option("removeAbandonedOnMaintenance", "boolean"),
            option("removeAbandonedTimeout", "long"),
            option("logAbandoned", "boolean"),
            option("abandonedUsageTracking", "java.lang.String"),
            option("poolPreparedStatements", "boolean"),
            option("clearStatementPoolOnReturn", "boolean"),
            option("maxOpenPreparedStatements", "int"),
            option("connectionProperties", "java.lang.String"),
            option("maxConnLifetimeMillis", "long"),
            option("logExpiredConnections", "boolean"),
            option("rollbackOnReturn", "boolean"),
            option("enableAutoCommitOnReturn", "boolean"),
            option("defaultQueryTimeout", "long"),
            option("fastFailValidation", "boolean"),
            option("disconnectionSqlCodes", "java.lang.String"),
            option("disconnectionIgnoreSqlCodes", "java.lang.String"),
            option("jmxName", "java.lang.String"),
            option("registerConnectionMBean", "boolean"),
            option("connectionFactoryClassName", "java.lang.String"));

    protected static final List<ExplicitAttribute> MEMORY_USER_DATABASE_FACTORY_OPTIONS = List.of(
            option("pathname", "java.lang.String"),
            option("readonly", "boolean"),
            option("watchSource", "boolean"));

    protected static final List<ExplicitAttribute> DATA_SOURCE_USER_DATABASE_FACTORY_OPTIONS = List.of(
            option("dataSourceName", "java.lang.String"),
            option("readonly", "boolean"),
            option("userTable", "java.lang.String"),
            option("groupTable", "java.lang.String"),
            option("roleTable", "java.lang.String"),
            option("userRoleTable", "java.lang.String"),
            option("userGroupTable", "java.lang.String"),
            option("groupRoleTable", "java.lang.String"),
            option("roleNameCol", "java.lang.String"),
            option("roleAndGroupDescriptionCol", "java.lang.String"),
            option("groupNameCol", "java.lang.String"),
            option("userCredCol", "java.lang.String"),
            option("userFullNameCol", "java.lang.String"),
            option("userNameCol", "java.lang.String"));

    protected static final List<ExplicitAttribute> POOL_DATA_SOURCE_FACTORY_OPTIONS = List.of(
            option("instanceKey", "java.lang.String"),
            option("description", "java.lang.String"),
            option("loginTimeout", "int"),
            option("blockWhenExhausted", "boolean"),
            option("evictionPolicyClassName", "java.lang.String"),
            option("lifo", "boolean"),
            option("maxIdlePerKey", "int"),
            option("maxTotalPerKey", "int"),
            option("maxWaitMillis", "long"),
            option("minEvictableIdleTimeMillis", "long"),
            option("minIdlePerKey", "int"),
            option("numTestsPerEvictionRun", "int"),
            option("softMinEvictableIdleTimeMillis", "long"),
            option("testOnCreate", "boolean"),
            option("testOnBorrow", "boolean"),
            option("testOnReturn", "boolean"),
            option("testWhileIdle", "boolean"),
            option("timeBetweenEvictionRunsMillis", "long"),
            option("validationQuery", "java.lang.String"),
            option("validationQueryTimeout", "int"),
            option("rollbackAfterValidation", "boolean"),
            option("maxConnLifetimeMillis", "long"),
            option("defaultAutoCommit", "boolean"),
            option("defaultTransactionIsolation", "int"),
            option("defaultReadOnly", "boolean"));

    protected static final List<ExplicitAttribute> PER_USER_POOL_DATA_SOURCE_FACTORY_OPTIONS = poolOptions(
            option("defaultMaxTotal", "int"),
            option("defaultMaxIdle", "int"),
            option("defaultMaxWaitMillis", "long"));

    protected static final List<ExplicitAttribute> SHARED_POOL_DATA_SOURCE_FACTORY_OPTIONS = poolOptions(
            option("maxTotal", "int"));

    protected static List<ExplicitAttribute> poolOptions(ExplicitAttribute... first) {
        List<ExplicitAttribute> result = new ArrayList<>(Arrays.asList(first));
        result.addAll(POOL_DATA_SOURCE_FACTORY_OPTIONS);
        return List.copyOf(result);
    }

    protected static ExplicitAttribute option(String name, String type) {
        return new ExplicitAttribute(name, type, true, true);
    }


    /**
     * The factory options (RefAddr keys) of the given first party JNDI factory, or {@code null} when the factory is not
     * one of the factories shipped with Tomcat (their parameters stay free form).
     */
    protected static List<ExplicitAttribute> factoryOptions(String factory) {
        if (factory == null) {
            return null;
        }
        return switch (factory) {
            case BASIC_DATA_SOURCE_FACTORY -> BASIC_DATA_SOURCE_FACTORY_OPTIONS;
            case MEMORY_USER_DATABASE_FACTORY -> MEMORY_USER_DATABASE_FACTORY_OPTIONS;
            case DATA_SOURCE_USER_DATABASE_FACTORY -> DATA_SOURCE_USER_DATABASE_FACTORY_OPTIONS;
            case PER_USER_POOL_DATA_SOURCE_FACTORY -> PER_USER_POOL_DATA_SOURCE_FACTORY_OPTIONS;
            case SHARED_POOL_DATA_SOURCE_FACTORY -> SHARED_POOL_DATA_SOURCE_FACTORY_OPTIONS;
            default -> null;
        };
    }


    /**
     * The factory a resource resolves to: the explicit {@code factory} parameter, or the default factory the
     * {@code ResourceFactory} dispatches the resource type to.
     */
    protected static String effectiveFactory(ContextResource resource) {
        Object factory = resource.getProperty("factory");
        if (factory != null && !String.valueOf(factory).isEmpty()) {
            return String.valueOf(factory);
        }
        // Default dispatch of org.apache.naming.factory.ResourceFactory.
        if ("javax.sql.DataSource".equals(resource.getType())) {
            return BASIC_DATA_SOURCE_FACTORY;
        }
        return null;
    }


    protected static boolean isNamingEntry(String type) {
        return "resource".equals(type) || "resourceLink".equals(type) || "resourceEnvRef".equals(type) ||
                "environment".equals(type) || "ejb".equals(type) || "localEjb".equals(type) ||
                "serviceRef".equals(type);
    }


    /**
     * The attribute list of one JNDI entry: the core attributes of the entry type, plus - for a resource whose factory
     * is one of the first party factories with a closed set of parameters - the factory options, plus the free form
     * parameters that are set but not covered by the list.
     */
    protected static List<ExplicitAttribute> namingEntryAttributes(Object component, String type) {
        List<ExplicitAttribute> result;
        if ("resource".equals(type)) {
            ContextResource resource = (ContextResource) component;
            result = new ArrayList<>(RESOURCE_ATTRIBUTES);
            List<ExplicitAttribute> options = factoryOptions(effectiveFactory(resource));
            if (options != null) {
                result.addAll(options);
            }
        } else {
            result = new ArrayList<>(switch (type) {
                        case "resourceLink" -> RESOURCE_LINK_ATTRIBUTES;
                        case "resourceEnvRef" -> RESOURCE_ENV_REF_ATTRIBUTES;
                        case "environment" -> ENVIRONMENT_ATTRIBUTES;
                        case "ejb" -> EJB_ATTRIBUTES;
                        case "localEjb" -> LOCAL_EJB_ATTRIBUTES;
                        case "serviceRef" -> SERVICE_ATTRIBUTES;
                        default -> List.of();
                    });
        }
        // The string parameters of the entry (the ResourceBase properties)
        // that are not covered by the explicit attribute tables above.
        appendParams((ResourceBase) component, result);
        return result;
    }


    /**
     * Append the string parameters of the given JNDI entry that are not already part of its explicit attribute list.
     */
    protected static void appendParams(ResourceBase entry, List<ExplicitAttribute> result) {
        Set<String> covered = new HashSet<>();
        for (ExplicitAttribute attribute : result) {
            covered.add(attribute.getName());
        }
        for (Iterator<String> it = entry.listProperties(); it.hasNext();) {
            String key = it.next();
            if (covered.add(key)) {
                result.add(new ExplicitAttribute(key, "java.lang.String", true, true));
            }
        }
    }


    /**
     * The explicitly defined attributes of the given component, or an empty list when the component's class has a
     * modeler descriptor (or is not one of the explicitly supported classes).
     */
    protected static ExplicitSpec explicitAttributes(Object component, String type) {
        if ("sslHostConfig".equals(type)) {
            return new ExplicitSpec("sslHostConfig", SSL_HOST_CONFIG_ATTRIBUTES);
        }
        if ("certificate".equals(type)) {
            return new ExplicitSpec("certificate", CERTIFICATE_ATTRIBUTES);
        }
        if ("preSharedKey".equals(type)) {
            return new ExplicitSpec("preSharedKey", PRE_SHARED_KEY_ATTRIBUTES);
        }
        if (component instanceof WebappLoader) {
            return new ExplicitSpec("loader", WEBAPP_LOADER_ATTRIBUTES);
        }
        if (component instanceof CookieProcessorBase) {
            return new ExplicitSpec("cookieProcessor", COOKIE_PROCESSOR_ATTRIBUTES);
        }
        if (component instanceof SessionIdGeneratorBase) {
            return new ExplicitSpec("sessionIdGenerator", SESSION_ID_GENERATOR_ATTRIBUTES);
        }
        if (component instanceof Http2Protocol) {
            return new ExplicitSpec("http2Protocol", HTTP2_PROTOCOL_ATTRIBUTES);
        }
        if (component instanceof GroupChannel) {
            return new ExplicitSpec("channel", CHANNEL_ATTRIBUTES);
        }
        if (component instanceof McastService) {
            return new ExplicitSpec("membership", MCAST_ATTRIBUTES);
        }
        if (component instanceof ReceiverBase) {
            return new ExplicitSpec("receiver", RECEIVER_ATTRIBUTES);
        }
        if (component instanceof AbstractSender) {
            return new ExplicitSpec("transport", TRANSPORT_ATTRIBUTES);
        }
        if (component instanceof MessageDispatchInterceptor) {
            return new ExplicitSpec("messageDispatchInterceptor", MESSAGE_DISPATCH_INTERCEPTOR_ATTRIBUTES);
        }
        if (component instanceof TcpFailureDetector) {
            return new ExplicitSpec("tcpFailureDetector", TCP_FAILURE_DETECTOR_ATTRIBUTES);
        }
        if (component instanceof ChannelInterceptor) {
            return new ExplicitSpec("channelInterceptor", INTERCEPTOR_ATTRIBUTES);
        }
        if (isNamingEntry(type) && component instanceof ResourceBase) {
            return new ExplicitSpec(type, namingEntryAttributes(component, type));
        }
        return new ExplicitSpec(type, List.of());
    }


    /**
     * The explicit attribute list of a component together with the key scope its descriptions are looked up under
     * (see {@link #attributeDescription}).
     */
    protected record ExplicitSpec(String scope, List<ExplicitAttribute> attributes) {
    }


    /**
     * The localized description of one explicitly defined attribute, looked up from the message bundle by its
     * {@code name}: {@code manager2.attr.<scope>.<name>} for a bean attribute; for a string parameter,
     * {@code manager2.param.<factoryKey>.<name>} first (a factory with a closed parameter set may give the name its
     * own text) and then {@code manager2.param.<name>}.
     *
     * @param scope      the key scope of the attribute list the attribute belongs to
     * @param paramScope the factory key scope for the parameters of a resource bound to a first party factory, or
     *                   {@code null}
     * @param attribute  the attribute
     *
     * @return the localized description, or {@code null} when the bundle defines no message for the attribute
     */
    protected static String attributeDescription(String scope, String paramScope, ExplicitAttribute attribute) {
        var sm = Strings.sm();
        if (attribute.isParam()) {
            if (paramScope != null) {
                String scoped = sm.getString("manager2.param." + paramScope + "." + attribute.getName());
                if (scoped != null) {
                    return scoped;
                }
            }
            return sm.getString("manager2.param." + attribute.getName());
        }
        return sm.getString("manager2.attr." + scope + "." + attribute.getName());
    }


    /**
     * The bundle key scope of the parameters of a first party JNDI factory (see
     * {@link #attributeDescription(String, String, ExplicitAttribute)}).
     *
     * @param factory the factory class name, possibly {@code null}
     *
     * @return the key scope of the factory, or {@code null} for a factory that is not shipped with Tomcat
     */
    protected static String factoryKey(String factory) {
        if (factory == null) {
            return null;
        }
        return switch (factory) {
            case BASIC_DATA_SOURCE_FACTORY -> "basicDataSource";
            case MEMORY_USER_DATABASE_FACTORY -> "memoryUserDatabase";
            case DATA_SOURCE_USER_DATABASE_FACTORY -> "dataSourceUserDatabase";
            case PER_USER_POOL_DATA_SOURCE_FACTORY -> "perUserPoolDataSource";
            case SHARED_POOL_DATA_SOURCE_FACTORY -> "sharedPoolDataSource";
            default -> null;
        };
    }


    protected static ExplicitAttribute findExplicitAttribute(Object component, String type, String name) {
        for (ExplicitAttribute attribute : explicitAttributes(component, type).attributes()) {
            if (attribute.getName().equals(name)) {
                return attribute;
            }
        }
        return null;
    }


    /**
     * Read the value of one explicitly defined attribute. A few attributes need a dedicated reader: the protocol set is
     * shown as the {@code +} separated list that {@code setProtocols} accepts, and the certificate verification level
     * as its string form.
     */
    protected static Object readExplicitValue(Object component, ExplicitAttribute attribute) {
        String name = attribute.getName();
        if (attribute.isParam()) {
            Object value = ((ResourceBase) component).getProperty(name);
            return value == null ? null : String.valueOf(value);
        }
        try {
            if ("protocols".equals(name) && component instanceof SSLHostConfig hostConfig) {
                StringBuilder protocols = new StringBuilder();
                for (String protocol : hostConfig.getProtocols()) {
                    if (!protocols.isEmpty()) {
                        protocols.append('+');
                    }
                    protocols.append(protocol);
                }
                return protocols.toString();
            }
            if ("certificateVerification".equals(name) && component instanceof SSLHostConfig hostConfig) {
                return hostConfig.getCertificateVerificationAsString();
            }
            try {
                return component.getClass().getMethod("get" + capitalize(name)).invoke(component);
            } catch (NoSuchMethodException nsm) {
                return component.getClass().getMethod("is" + capitalize(name)).invoke(component);
            }
        } catch (Exception e) {
            return null;
        }
    }


    protected static void setExplicitValue(Object component, ExplicitAttribute attribute, Object value)
            throws ConfigException {
        if (attribute.isParam()) {
            // A string parameter of the generic property map of a JNDI
            // entry; an empty value removes the parameter.
            ResourceBase base = (ResourceBase) component;
            if (value == null || (value instanceof String s && s.isEmpty())) {
                base.removeProperty(attribute.getName());
            } else {
                base.setProperty(attribute.getName(), String.valueOf(value));
            }
            return;
        }
        String method = "set" + capitalize(attribute.getName());
        try {
            Method m = component.getClass().getMethod(method, classForType(attribute.getType()));
            m.invoke(component, value);
        } catch (NoSuchMethodException e) {
            throw new ConfigException(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "NO_SETTER",
                    Strings.sm().getString("manager2.configNoSetter", method));
        } catch (InvocationTargetException e) {
            Throwable t = e.getTargetException();
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "SET_FAILED",
                    Strings.sm().getString("manager2.configSetFailed", attribute.getName(),
                            t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
        } catch (IllegalAccessException e) {
            throw new ConfigException(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "NO_SETTER",
                    Strings.sm().getString("manager2.configNoSetter", method));
        }
    }


    /**
     * Convert an arbitrary component value to a JSON safe representation (null, String, Number, Boolean, List or Map).
     */
    protected static Object jsonSafe(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number && !(value instanceof Double d && (d.isNaN() || d.isInfinite()))) {
            return value;
        }
        if (value instanceof String[] array) {
            return List.of(array);
        }
        if (value instanceof Object[] array) {
            List<String> list = new ArrayList<>(array.length);
            for (Object item : array) {
                list.add(String.valueOf(item));
            }
            return list;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                result.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
            return result;
        }
        if (value instanceof Double d && (d.isNaN() || d.isInfinite())) {
            return String.valueOf(value);
        }
        return String.valueOf(value);
    }


    // ---------------------------------------------------------- Resolving


    /**
     * Resolve a node id (see the class javadoc for the grammar) to a component.
     */
    protected NodeRef resolve(String id) throws ConfigException {

        if (id == null || id.isEmpty()) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_ID",
                    Strings.sm().getString("manager2.configInvalidId"));
        }

        String[] segments = id.split("/", -1);
        if (!"server".equals(segments[0])) {
            throw new ConfigException(HttpServletResponse.SC_NOT_FOUND, "NOT_FOUND",
                    Strings.sm().getString("manager2.configNotFound"));
        }

        Object current = server;
        Object parent = null;
        String type = "server";

        for (int i = 1; i < segments.length; i += 2) {
            if (i + 1 >= segments.length) {
                throw new ConfigException(HttpServletResponse.SC_NOT_FOUND, "NOT_FOUND",
                        Strings.sm().getString("manager2.configNotFound"));
            }
            String kind = segments[i];
            String value = dec(segments[i + 1]);

            switch (kind) {
                case "service" -> {
                    Service service = server.findService(value);
                    if (service == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = service;
                }
                case "engine" -> {
                    if (!(current instanceof StandardService service) ||
                            !(service.getContainer() instanceof Engine engine)) {
                        throw notFound();
                    }
                    parent = current;
                    current = engine;
                }
                case "host" -> {
                    if (!(current instanceof StandardEngine engine) ||
                            !(engine.findChild(value) instanceof Host host)) {
                        throw notFound();
                    }
                    parent = current;
                    current = host;
                }
                case "context" -> {
                    // The root context is registered under the empty name,
                    // not "/", so map the bare "+" segment accordingly.
                    String contextName = "+".equals(segments[i + 1]) ? "" : value;
                    if (!(current instanceof Host host) || !(host.findChild(contextName) instanceof Context context)) {
                        throw notFound();
                    }
                    parent = current;
                    current = context;
                }
                case "wrapper" -> {
                    if (!(current instanceof Context context) ||
                            !(context.findChild(value) instanceof Wrapper wrapper)) {
                        throw notFound();
                    }
                    parent = current;
                    current = wrapper;
                }
                case "alias" -> {
                    if (!(current instanceof Host host)) {
                        throw notFound();
                    }
                    if (!Arrays.asList(host.findAliases()).contains(value)) {
                        throw notFound();
                    }
                    return new NodeRef(host, host, "alias", value, id);
                }
                case "valve" -> {
                    if (!(current instanceof Container container)) {
                        throw notFound();
                    }
                    Valve[] valves = container.getPipeline().getValves();
                    int index = index(value);
                    if (index < 0 || index >= valves.length) {
                        throw notFound();
                    }
                    parent = current;
                    current = valves[index];
                }
                case "connector" -> {
                    if (!(current instanceof StandardService service)) {
                        throw notFound();
                    }
                    Connector[] connectors = service.findConnectors();
                    int idx = index(value);
                    if (idx < 0 || idx >= connectors.length) {
                        throw notFound();
                    }
                    parent = current;
                    current = connectors[idx];
                }
                case "executor" -> {
                    if (!(current instanceof StandardService service)) {
                        throw notFound();
                    }
                    Executor found = null;
                    for (Executor executor : service.findExecutors()) {
                        if (executor.getName().equals(value)) {
                            found = executor;
                            break;
                        }
                    }
                    if (found == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = found;
                }
                case "listener" -> {
                    if (!(current instanceof LifecycleBase base)) {
                        throw notFound();
                    }
                    LifecycleListener[] listeners = base.findLifecycleListeners();
                    int index = index(value);
                    if (index < 0 || index >= listeners.length) {
                        throw notFound();
                    }
                    parent = current;
                    current = listeners[index];
                }
                case "realm" -> {
                    int index = index(value);
                    Realm found = null;
                    if (current instanceof Container container) {
                        // A container holds at most one directly attached
                        // realm, addressed as index 0.
                        if (index == 0) {
                            found = ownRealm(container);
                        }
                    } else if (current instanceof CombinedRealm combined) {
                        Realm[] nested = combined.getNestedRealms();
                        if (index >= 0 && index < nested.length) {
                            found = nested[index];
                        }
                    }
                    if (found == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = found;
                }
                case "sslHostConfig" -> {
                    if (!(current instanceof Connector connector)) {
                        throw notFound();
                    }
                    SSLHostConfig[] hostConfigs = connector.findSslHostConfigs();
                    SSLHostConfig found = null;
                    if (hostConfigs != null) {
                        for (SSLHostConfig hostConfig : hostConfigs) {
                            // Host names are case-insensitive and stored
                            // in lower case.
                            if (hostConfig.getHostName().equalsIgnoreCase(value)) {
                                found = hostConfig;
                                break;
                            }
                        }
                    }
                    if (found == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = found;
                }
                case "upgradeProtocol" -> {
                    if (!(current instanceof Connector connector)) {
                        throw notFound();
                    }
                    UpgradeProtocol[] upgradeProtocols = connector.findUpgradeProtocols();
                    int index = index(value);
                    if (index < 0 || index >= upgradeProtocols.length) {
                        throw notFound();
                    }
                    parent = current;
                    current = upgradeProtocols[index];
                }
                case "certificate" -> {
                    if (!(current instanceof SSLHostConfig hostConfig)) {
                        throw notFound();
                    }
                    SSLHostConfigCertificate[] certificates = hostConfig.getCertificates()
                            .toArray(new SSLHostConfigCertificate[0]);
                    int index = index(value);
                    if (index < 0 || index >= certificates.length) {
                        throw notFound();
                    }
                    parent = current;
                    current = certificates[index];
                }
                case "preSharedKey" -> {
                    if (!(current instanceof SSLHostConfig hostConfig)) {
                        throw notFound();
                    }
                    SSLHostConfigPreSharedKey[] preSharedKeys =
                            hostConfig.getPreSharedKeys().toArray(new SSLHostConfigPreSharedKey[0]);
                    int index = index(value);
                    if (index < 0 || index >= preSharedKeys.length) {
                        throw notFound();
                    }
                    parent = current;
                    current = preSharedKeys[index];
                }
                case "manager" -> {
                    // A context holds exactly one manager, addressed as
                    // index 0.
                    if (!(current instanceof Context context) || index(value) != 0) {
                        throw notFound();
                    }
                    Manager manager = context.getManager();
                    if (manager == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = manager;
                }
                case "resources" -> {
                    if (!(current instanceof Context context) || index(value) != 0) {
                        throw notFound();
                    }
                    WebResourceRoot resources = context.getResources();
                    if (resources == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = resources;
                }
                case "loader" -> {
                    if (!(current instanceof Context context) || index(value) != 0) {
                        throw notFound();
                    }
                    Loader loader = context.getLoader();
                    if (loader == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = loader;
                }
                case "cookieProcessor" -> {
                    if (!(current instanceof Context context) || index(value) != 0) {
                        throw notFound();
                    }
                    CookieProcessor cookieProcessor = context.getCookieProcessor();
                    if (cookieProcessor == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = cookieProcessor;
                }
                case "sessionIdGenerator" -> {
                    // A manager holds at most one session id generator,
                    // addressed as index 0.
                    if (!(current instanceof Manager manager) || index(value) != 0) {
                        throw notFound();
                    }
                    SessionIdGenerator generator = manager.getSessionIdGenerator();
                    if (generator == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = generator;
                }
                case "namingResources" -> {
                    // The server and each context hold exactly one
                    // NamingResourcesImpl, addressed as index 0.
                    if (index(value) != 0) {
                        throw notFound();
                    }
                    NamingResourcesImpl namingResources = null;
                    if (current instanceof StandardServer s) {
                        namingResources = s.getGlobalNamingResources();
                    } else if (current instanceof Context context) {
                        namingResources = context.getNamingResources();
                    }
                    if (namingResources == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = namingResources;
                }
                case "resource" -> {
                    if (!(current instanceof NamingResourcesImpl namingResources) ||
                            namingResources.findResource(value) == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = namingResources.findResource(value);
                }
                case "resourceLink" -> {
                    if (!(current instanceof NamingResourcesImpl namingResources) ||
                            namingResources.findResourceLink(value) == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = namingResources.findResourceLink(value);
                }
                case "resourceEnvRef" -> {
                    if (!(current instanceof NamingResourcesImpl namingResources) ||
                            namingResources.findResourceEnvRef(value) == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = namingResources.findResourceEnvRef(value);
                }
                case "environment" -> {
                    if (!(current instanceof NamingResourcesImpl namingResources) ||
                            namingResources.findEnvironment(value) == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = namingResources.findEnvironment(value);
                }
                case "ejb" -> {
                    if (!(current instanceof NamingResourcesImpl namingResources) ||
                            namingResources.findEjb(value) == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = namingResources.findEjb(value);
                }
                case "localEjb" -> {
                    if (!(current instanceof NamingResourcesImpl namingResources) ||
                            namingResources.findLocalEjb(value) == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = namingResources.findLocalEjb(value);
                }
                case "serviceRef" -> {
                    if (!(current instanceof NamingResourcesImpl namingResources) ||
                            namingResources.findService(value) == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = namingResources.findService(value);
                }
                case "cluster" -> {
                    if (!(current instanceof Container container) || ownCluster(container) == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = container.getCluster();
                }
                case "channel" -> {
                    if (!(current instanceof CatalinaCluster cluster) || index(value) != 0) {
                        throw notFound();
                    }
                    Channel channel = cluster.getChannel();
                    if (channel == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = channel;
                }
                case "membership" -> {
                    if (!(current instanceof ManagedChannel managed) || index(value) != 0) {
                        throw notFound();
                    }
                    MembershipService membership = managed.getMembershipService();
                    if (membership == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = membership;
                }
                case "sender" -> {
                    if (!(current instanceof ManagedChannel managed) || index(value) != 0) {
                        throw notFound();
                    }
                    ChannelSender sender = managed.getChannelSender();
                    if (sender == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = sender;
                }
                case "transport" -> {
                    if (!(current instanceof ReplicationTransmitter transmitter) || index(value) != 0) {
                        throw notFound();
                    }
                    MultiPointSender transport = transmitter.getTransport();
                    if (transport == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = transport;
                }
                case "receiver" -> {
                    if (!(current instanceof ManagedChannel managed) || index(value) != 0) {
                        throw notFound();
                    }
                    ChannelReceiver receiver = managed.getChannelReceiver();
                    if (receiver == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = receiver;
                }
                case "interceptor" -> {
                    if (!(current instanceof ManagedChannel managed)) {
                        throw notFound();
                    }
                    int wantedInterceptor = index(value);
                    Iterator<ChannelInterceptor> interceptorIter = managed.getInterceptors();
                    int interceptorIndex = 0;
                    ChannelInterceptor foundInterceptor = null;
                    while (interceptorIter.hasNext()) {
                        ChannelInterceptor interceptor = interceptorIter.next();
                        if (interceptorIndex == wantedInterceptor) {
                            foundInterceptor = interceptor;
                            break;
                        }
                        interceptorIndex++;
                    }
                    if (foundInterceptor == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = foundInterceptor;
                }
                case "clusterValve" -> {
                    if (!(current instanceof CatalinaCluster cluster)) {
                        throw notFound();
                    }
                    Valve[] clusterValves = cluster.getValves();
                    int valveIndex = index(value);
                    if (valveIndex < 0 || valveIndex >= clusterValves.length) {
                        throw notFound();
                    }
                    parent = current;
                    current = clusterValves[valveIndex];
                }
                case "deployer" -> {
                    if (!(current instanceof CatalinaCluster cluster) || index(value) != 0) {
                        throw notFound();
                    }
                    ClusterDeployer deployer = cluster.getClusterDeployer();
                    if (deployer == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = deployer;
                }
                case "clusterManager" -> {
                    if (!(current instanceof SimpleTcpCluster tcp) || index(value) != 0) {
                        throw notFound();
                    }
                    ClusterManager manager = tcp.getManagerTemplate();
                    if (manager == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = manager;
                }
                case "clusterListener" -> {
                    if (!(current instanceof SimpleTcpCluster tcp)) {
                        throw notFound();
                    }
                    int wantedListener = index(value);
                    int listenerIndex = 0;
                    ClusterListener foundListener = null;
                    for (ClusterListener listener : tcp.findClusterListeners()) {
                        if (listener == tcp.getClusterDeployer()) {
                            continue;
                        }
                        if (listenerIndex == wantedListener) {
                            foundListener = listener;
                            break;
                        }
                        listenerIndex++;
                    }
                    if (foundListener == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = foundListener;
                }
                case "localMember" -> {
                    Member foundLocalMember = null;
                    if (current instanceof StaticMembershipService sms) {
                        foundLocalMember = sms.getLocalMember(false);
                    } else if (current instanceof StaticMembershipInterceptor smi) {
                        foundLocalMember = smi.getLocalMember(false);
                    }
                    if (foundLocalMember == null) {
                        throw notFound();
                    }
                    parent = current;
                    current = foundLocalMember;
                }
                case "member" -> {
                    if (!(current instanceof StaticMembershipService sms)) {
                        throw notFound();
                    }
                    List<StaticMember> staticMembers = sms.getStaticMembers();
                    int memberIndex = index(value);
                    if (memberIndex < 0 || memberIndex >= staticMembers.size()) {
                        throw notFound();
                    }
                    parent = current;
                    current = staticMembers.get(memberIndex);
                }
                default -> throw notFound();
            }

            type = "localMember".equals(kind) ? "member" : kind;
        }

        return new NodeRef(current, parent, type, null, id);
    }


    protected static ConfigException notFound() {
        return new ConfigException(HttpServletResponse.SC_NOT_FOUND, "NOT_FOUND",
                Strings.sm().getString("manager2.configNotFound"));
    }


    protected static int index(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }


}
