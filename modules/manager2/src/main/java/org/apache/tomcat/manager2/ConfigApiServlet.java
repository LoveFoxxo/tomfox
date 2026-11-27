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

import java.io.File;
import java.io.PrintWriter;
import java.io.Serial;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import org.apache.catalina.Pipeline;
import org.apache.catalina.Realm;
import org.apache.catalina.Server;
import org.apache.catalina.Service;
import org.apache.catalina.SessionIdGenerator;
import org.apache.catalina.Valve;
import org.apache.catalina.WebResourceRoot;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardEngine;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.core.StandardService;
import org.apache.catalina.core.StandardThreadExecutor;
import org.apache.catalina.core.StandardWrapper;
import org.apache.catalina.deploy.NamingResourcesImpl;
import org.apache.catalina.ha.CatalinaCluster;
import org.apache.catalina.ha.ClusterDeployer;
import org.apache.catalina.ha.ClusterListener;
import org.apache.catalina.ha.ClusterManager;
import org.apache.catalina.ha.ClusterValve;
import org.apache.catalina.ha.tcp.SimpleTcpCluster;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.realm.CombinedRealm;
import org.apache.catalina.realm.MemoryRealm;
import org.apache.catalina.realm.RealmBase;
import org.apache.catalina.session.ManagerBase;
import org.apache.catalina.startup.Bootstrap;
import org.apache.catalina.startup.ContextConfig;
import org.apache.catalina.startup.HostConfig;
import org.apache.catalina.storeconfig.IStoreFactory;
import org.apache.catalina.storeconfig.StandardContextSF;
import org.apache.catalina.storeconfig.StoreConfig;
import org.apache.catalina.storeconfig.StoreDescription;
import org.apache.catalina.storeconfig.StoreFileMover;
import org.apache.catalina.storeconfig.StoreLoader;
import org.apache.catalina.storeconfig.XMLFormatPreserver;
import org.apache.catalina.tribes.Channel;
import org.apache.catalina.tribes.ChannelInterceptor;
import org.apache.catalina.tribes.ChannelReceiver;
import org.apache.catalina.tribes.ChannelSender;
import org.apache.catalina.tribes.ManagedChannel;
import org.apache.catalina.tribes.Member;
import org.apache.catalina.tribes.MembershipService;
import org.apache.catalina.tribes.group.GroupChannel;
import org.apache.catalina.tribes.group.interceptors.StaticMembershipInterceptor;
import org.apache.catalina.tribes.membership.McastService;
import org.apache.catalina.tribes.membership.StaticMember;
import org.apache.catalina.tribes.membership.StaticMembershipService;
import org.apache.catalina.tribes.transport.MultiPointSender;
import org.apache.catalina.tribes.transport.ReplicationTransmitter;
import org.apache.catalina.util.ContextName;
import org.apache.catalina.util.LifecycleBase;
import org.apache.catalina.util.SessionIdGeneratorBase;
import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.UpgradeProtocol;
import org.apache.coyote.http11.AbstractHttp11Protocol;
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
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.apache.tomcat.util.net.SSLHostConfigPreSharedKey;


/**
 * The Manager2 configuration API. Exposes the complete component tree of the Catalina {@code Server} (services,
 * engines, hosts, contexts, wrappers, valves, connectors, executors, listeners, host aliases, realms, TLS host
 * configurations, the upgrade protocols of a connector, the context sub components manager, session id generator,
 * resources, loader and cookie processor, and the JNDI naming resources of the server and of the contexts), allows
 * reading and updating the descriptor defined attributes of any component, adding and removing child components,
 * starting, stopping and restarting any component that implements {@code Lifecycle}, and persisting the live state to
 * {@code conf/server.xml} through the storeconfig mechanism.
 * <p>
 * Changes are applied to the running server immediately. Persisting (store) rewrites {@code conf/server.xml} from the
 * live state and keeps a timestamped backup of the previous file.
 * <p>
 * <b>Lifecycle operations.</b> Not every change takes effect until the affected component is restarted (which
 * attributes apply live is not documented), so the API offers explicit lifecycle operations: {@code start}, {@code
 * stop} and {@code restart} (a stop followed by a start) for every component that implements {@code Lifecycle}. A
 * {@code start} or {@code stop} of a component that would interrupt access to this web application (the server itself,
 * the service, engine, host or context that route it, the connector that serves it and the wrappers of the context it
 * runs in) is refused (403 {@code SELF_COMPONENT}): the request would lose its way back. A {@code restart} of such a
 * component is still allowed: the client's connection may be interrupted during the operation, but the component is
 * running again at the end and the client reconnects (re-logging in when the admin session was reset by the restart).
 * <p>
 * <b>Contexts keep their storage location</b> when the configuration is persisted, mirroring regular StoreConfig
 * behavior: a context that is backed by its own configuration file (its {@code META-INF/context.xml} or
 * {@code conf/Catalina/.../context.xml}) is written back to that file, and a context defined inline in
 * {@code server.xml} stays inline. A context restarts when its own file is (re)written, so a save that rewrites the
 * file of this web application restarts the manager and resets the admin session; the UI warns about this before the
 * save. The store preview is read-only: it reports the resulting {@code server.xml} and the external context files that
 * would be rewritten, without touching the configuration on disk.
 * <p>
 * <b>Node addressing.</b> Every node of the tree carries a path based {@code id} made of named segments (URL encoded,
 * where a {@code /} inside a value - e.g. in a context path - is written as {@code +}) and, for components that have no
 * name (valves, connectors, listeners), a positional index:
 *
 * <pre>
 *   server
 *   server/service/{name}
 *   server/service/{s}/engine/{name}
 *   server/service/{s}/engine/{e}/host/{name}
 *   server/service/{s}/engine/{e}/host/{h}/alias/{alias}
 *   server/service/{s}/engine/{e}/host/{h}/context/{path}
 *   server/service/{s}/engine/{e}/host/{h}/context/{p}/wrapper/{name}
 *   server/service/{s}/engine/{e}/host/{h}/context/{p}/valve/{index}
 *   server/service/{s}/engine/{e}/host/{h}/context/{p}/manager/0
 *   server/service/{s}/engine/{e}/host/{h}/context/{p}/manager/0/sessionIdGenerator/0
 *   server/service/{s}/engine/{e}/host/{h}/context/{p}/resources/0
 *   server/service/{s}/engine/{e}/host/{h}/context/{p}/loader/0
 *   server/service/{s}/engine/{e}/host/{h}/context/{p}/cookieProcessor/0
 *   server/service/{s}/engine/{e}/host/{h}/context/{p}/namingResources/0
 *   server/namingResources/0
 *   server/namingResources/0/resource/{name}
 *   server/namingResources/0/resourceLink/{name}
 *   server/namingResources/0/resourceEnvRef/{name}
 *   server/namingResources/0/environment/{name}
 *   server/namingResources/0/ejb/{name}
 *   server/namingResources/0/localEjb/{name}
 *   server/namingResources/0/serviceRef/{name}
 *   server/service/{s}/engine/{e}/host/{h}/context/{p}/namingResources/0/resource/{name}
 *   ...
 *   server/service/{s}/engine/{e}/host/{h}/valve/{index}
 *   server/service/{s}/engine/{e}/valve/{index}
 *   server/service/{s}/engine/{e}/listener/{index}
 *   server/service/{s}/connector/{index}
 *   server/service/{s}/connector/{i}/sslHostConfig/{hostName}
 *   server/service/{s}/connector/{i}/sslHostConfig/{h}/certificate/{index}
 *   server/service/{s}/connector/{i}/sslHostConfig/{h}/preSharedKey/{index}
 *   server/service/{s}/connector/{i}/upgradeProtocol/{index}
 *   server/service/{s}/executor/{name}
 *   server/service/{s}/valve/{index}
 *   server/service/{s}/listener/{index}
 *   server/valve/{index}
 *   server/listener/{index}
 * </pre>
 *
 * The tree endpoint generates these ids; clients only echo them back.
 * <p>
 * <b>Attributes.</b> The property list of a node is derived from the modeler MBean descriptor of the component's class
 * (the same contract that defines the {@code Catalina:*} MBeans). The TLS components ({@code SSLHostConfig},
 * {@code SSLHostConfigCertificate} and {@code SSLHostConfigPreSharedKey}), the context sub components
 * ({@code WebappLoader}, {@code CookieProcessorBase}
 * subclasses and {@code SessionIdGeneratorBase} subclasses), the HTTP/2 upgrade protocol
 * ({@code org.apache.coyote.http2.Http2Protocol}) and the JNDI entry nodes have no (complete) modeler descriptor; their
 * editable attribute list is defined explicitly by this servlet. Only attributes that map to a simple UI type (boolean,
 * integral, string, string array) are editable; everything else is reported read-only.
 * <p>
 * <b>Context sub components.</b> A running context always has exactly one manager, one resource root, one loader and
 * one cookie processor (the defaults are created at context start). Adding one of these to a context therefore replaces
 * the current instance with a new instance of the given class; the current instance is stopped first (where it has a
 * lifecycle). These components are required by the context and cannot be removed - only replaced. Replacing the
 * resources of a running context is refused (the context must be stopped first). The manager's session id generator is
 * replaced the same way.
 * <p>
 * <b>TLS.</b> Adding an {@code sslHostConfig} to a connector enables TLS on that connector. The TLS state of a running
 * connector only takes effect when the connector is restarted, so the operations that change it (add or remove an
 * {@code sslHostConfig}, add or remove a {@code certificate} or {@code preSharedKey}) restart the affected connector
 * and roll back the change
 * if the restart fails (for example because the keystore does not exist or the password is wrong). The connector that
 * hosts this web application itself is never touched.
 * <p>
 * <b>Upgrade protocols.</b> A connector whose protocol handler is the HTTP/1.1 variant can carry upgrade protocols (the
 * {@code UpgradeProtocol} interface; the only implementation shipped with Tomcat is the HTTP/2 one,
 * {@code org.apache.coyote.http2.Http2Protocol}). An upgrade protocol is only referenced when the connector is
 * initialised, so adding one does not change a running connector: it becomes active the next time the connector is
 * (re)started (see the lifecycle operations). No live activation is attempted.
 * <p>
 * <b>JNDI naming resources.</b> The server and every context have a {@code namingResources} node (a
 * {@code NamingResourcesImpl}) that holds the JNDI entries: {@code resource}, {@code resourceLink},
 * {@code resourceEnvRef}, {@code environment}, {@code ejb}, {@code localEjb} and {@code service}. A server level node
 * does not accept {@code resourceLink} entries (they only exist in the context JNDI environment and are not parsed from
 * {@code <GlobalNamingResources>}). Adding and removing an entry updates the live JNDI environment of the server (the
 * global context) or of the context ({@code java:comp/env}) immediately; the container's {@code NamingContextListener}
 * performs the JNDI bind/unbind on the property change event. Updating an attribute of an existing entry does not fire
 * such an event, so the update is implemented as a remove plus re-add of the entry (the change is rolled back if the
 * re-add fails). The factory specific options of a {@code resource} (e.g. {@code url}, {@code driverClassName},
 * {@code maxTotal}) are free form string parameters; for the first party JNDI {@code ObjectFactory} implementations
 * shipped with Tomcat ({@code BasicDataSourceFactory}, {@code MemoryUserDatabaseFactory},
 * {@code DataSourceUserDatabaseFactory} and the per user / shared pool data source factories) the parameters they
 * actually consume are listed in the node's property set, with validation.
 * <p>
 * Like the rest of the mutable API, this API requires the {@code manager-gui} role and, for the mutating methods, a
 * valid CSRF token.
 */
public class ConfigApiServlet extends ConfigApiNavigationServlet {

    @Serial
    private static final long serialVersionUID = 1L;

    // ------------------------------------------------- Attribute updating


    protected void updateAttribute(HttpServletResponse response, Map<String, Object> body) throws Exception {

        String id = string(body.get("id"));
        String name = string(body.get("name"));
        if (id == null || name == null || name.isEmpty()) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_ID",
                    Strings.sm().getString("manager2.configInvalidId"));
        }

        NodeRef ref = resolve(id);
        if (!"server".equals(ref.type) && ref.component == selfContext &&
                ("name".equals(name) || "path".equals(name))) {
            throw new ConfigException(HttpServletResponse.SC_FORBIDDEN, "SELF_COMPONENT",
                    Strings.sm().getString("manager2.configSelfComponent"));
        }

        // Components without a modeler descriptor take their attributes
        // from the explicit list.
        if (!explicitAttributes(ref.component, ref.type).attributes().isEmpty()) {
            updateExplicitAttribute(response, ref, name, body);
            return;
        }

        ManagedBean descriptor = descriptor(ref.component);
        AttributeInfo attribute = findAttribute(descriptor, name);
        if (attribute == null) {
            throw new ConfigException(HttpServletResponse.SC_NOT_FOUND, "ATTRIBUTE_NOT_FOUND",
                    Strings.sm().getString("manager2.configAttributeNotFound", name));
        }
        if (!attribute.isWriteable() || !EDITABLE_TYPES.contains(attribute.getType())) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "READ_ONLY",
                    Strings.sm().getString("manager2.configReadOnly", name));
        }

        if (RISKY_ATTRIBUTES.contains(name)) {
            String confirm = string(body.get("confirm"));
            // For a context the display name is the (possibly normalized)
            // context path, which is also what the UI asks to be confirmed.
            String expected = displayName(ref.component, ref.type);
            if (!expected.equals(confirm)) {
                throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "CONFIRM_REQUIRED",
                        Strings.sm().getString("manager2.configConfirmRequired", expected));
            }
        }

        Object value = convert(attribute.getType(), body.get("value"));
        setValue(ref.component, attribute, value);

        log(Strings.sm().getString("manager2.configAuditAttribute", name, displayName(ref.component, ref.type),
                String.valueOf(value)));
        Api.ok(response, Strings.sm().getString("manager2.configAttributeUpdated", name, displayName(ref.component, ref.type)));
    }


    /**
     * Update one attribute of a component without a modeler descriptor (the {@code sslHostConfig}, {@code certificate}
     * and {@code preSharedKey} node types, and the {@code loader}, {@code cookieProcessor} and {@code sessionIdGenerator}
     * node types of the standard implementations).
     */
    protected void updateExplicitAttribute(HttpServletResponse response, NodeRef ref, String name,
            Map<String, Object> body) throws Exception {

        Object component = ref.component;
        boolean namingEntry = isNamingEntry(ref.type);
        ResourceBase entry = namingEntry ? (ResourceBase) component : null;
        String oldEntryName = entry == null ? null : entry.getName();

        // Renaming a JNDI entry changes its JNDI name; require the same
        // type-to-confirm as the other renames.
        if (namingEntry && RISKY_ATTRIBUTES.contains(name)) {
            String confirm = string(body.get("confirm"));
            String expected = displayName(component, ref.type);
            if (!expected.equals(confirm)) {
                throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "CONFIRM_REQUIRED",
                        Strings.sm().getString("manager2.configConfirmRequired", expected));
            }
        }

        ExplicitAttribute attribute = findExplicitAttribute(component, ref.type, name);
        boolean param = false;
        String oldParamValue = null;
        if (attribute == null) {
            // A free form string parameter of a JNDI entry: any parameter
            // name is accepted, the JNDI factory decides which ones it
            // consumes.
            if (!namingEntry) {
                throw new ConfigException(HttpServletResponse.SC_NOT_FOUND, "ATTRIBUTE_NOT_FOUND",
                        Strings.sm().getString("manager2.configAttributeNotFound", name));
            }
            param = true;
            Object current = entry.getProperty(name);
            oldParamValue = current == null ? null : String.valueOf(current);
            attribute = new ExplicitAttribute(name, "java.lang.String", true, true);
        } else if (!attribute.isWritable()) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "READ_ONLY",
                    Strings.sm().getString("manager2.configReadOnly", name));
        }

        Object value;
        if (attribute.isParam()) {
            // Parameters (the free form parameters of a JNDI entry and
            // the typed factory options) are stored as string properties
            // of the entry: keep the raw string form (an empty value
            // removes the parameter, see setExplicitValue) and validate
            // it against the declared type of the option (free form
            // parameters are strings and always pass).
            String paramValue = string(body.get("value"));
            if (paramValue != null && !paramValue.isEmpty()) {
                validateParamValue(name, paramValue, attribute.getType());
            }
            value = paramValue;
        } else {
            value = convert(attribute.getType(), body.get("value"));
        }
        Object oldValue = param ? null : readExplicitValue(component, attribute);
        setExplicitValue(component, attribute, value);

        // A JNDI entry update is only visible to the live JNDI
        // environment (the NamingContextListener reacts to the property
        // change events) when the entry is removed and re-added.
        if (namingEntry) {
            try {
                rebindEntry(ref, oldEntryName);
            } catch (Exception e) {
                // Roll the change back and re-register the entry as it
                // was before the update.
                try {
                    if (param) {
                        if (oldParamValue == null) {
                            entry.removeProperty(name);
                        } else {
                            entry.setProperty(name, oldParamValue);
                        }
                    } else {
                        setExplicitValue(component, attribute, oldValue);
                    }
                    entry.setName(oldEntryName);
                    rebindEntry(ref, oldEntryName);
                } catch (Exception rollbackError) {
                    log(Strings.sm().getString("manager2.error.config"), rollbackError);
                }
                throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "UPDATE_FAILED",
                        Strings.sm().getString("manager2.configSetFailed", name, rootMessage(e)));
            }
        }

        // On a running, TLS enabled connector the change is applied (and
        // validated) at once by re-creating the SSL context of the
        // affected host configuration. When the new value does not
        // validate the previous value is restored.
        SSLHostConfig sslHostConfig = null;
        Connector connector = null;
        if ("sslHostConfig".equals(ref.type)) {
            sslHostConfig = (SSLHostConfig) ref.component;
            if (ref.parent instanceof Connector parent) {
                connector = parent;
            }
        } else if (("certificate".equals(ref.type) || "preSharedKey".equals(ref.type)) &&
                ref.parent instanceof SSLHostConfig parent) {
            sslHostConfig = parent;
            connector = connectorOfSsl(sslHostConfig);
        }
        if (connector != null && connector.getState().isAvailable() && isSslEnabled(connector)) {
            try {
                endpointOf(connector).reloadSslHostConfig(sslHostConfig.getHostName());
            } catch (Exception e) {
                setExplicitValue(ref.component, attribute, oldValue);
                throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "UPDATE_FAILED", Strings.sm().getString(
                        "manager2.configSslReloadFailed", name, displayName(ref.component, ref.type), rootMessage(e)));
            }
        }

        log(Strings.sm().getString("manager2.configAuditAttribute", name, displayName(ref.component, ref.type),
                String.valueOf(value)));
        Api.ok(response, Strings.sm().getString("manager2.configAttributeUpdated", name, displayName(ref.component, ref.type)));
    }


    /**
     * Re-register a JNDI entry whose state has changed in place: remove it (by the name it was registered under) and
     * add it again. The remove and add fire the property change events that the {@code NamingContextListener} uses to
     * update the live JNDI environment.
     */
    protected void rebindEntry(NodeRef ref, String registeredName) throws Exception {
        NamingResourcesImpl namingResources = (NamingResourcesImpl) ref.parent;
        ResourceBase entry = (ResourceBase) ref.component;
        String newName = entry.getName();
        boolean renamed = !registeredName.equals(newName);
        if (renamed) {
            // The removal event carries the entry and the NamingContextListener
            // unbinds the JNDI name the entry reports. Restore the name the
            // entry was registered under so the old binding (not the new one)
            // is the one that is removed.
            entry.setName(registeredName);
        }
        try {
            removeNamingEntry(namingResources, ref.type, registeredName);
        } finally {
            if (renamed) {
                entry.setName(newName);
            }
        }
        addNamingEntryTo(namingResources, ref.type, entry);
        // The add is a silent no-op when the (new) name is already used
        // by another entry; detect that so the caller can roll back.
        if (findNamingEntry(namingResources, ref.type, newName) != entry) {
            throw new IllegalStateException(Strings.sm().getString("manager2.configJndiNameTaken", newName));
        }
    }


    protected static ResourceBase findNamingEntry(NamingResourcesImpl namingResources, String type, String name) {
        return switch (type) {
            case "resource" -> namingResources.findResource(name);
            case "resourceLink" -> namingResources.findResourceLink(name);
            case "resourceEnvRef" -> namingResources.findResourceEnvRef(name);
            case "environment" -> namingResources.findEnvironment(name);
            case "ejb" -> namingResources.findEjb(name);
            case "localEjb" -> namingResources.findLocalEjb(name);
            case "serviceRef" -> namingResources.findService(name);
            default -> null;
        };
    }


    protected static void removeNamingEntry(NamingResourcesImpl namingResources, String type, String name) {
        switch (type) {
            case "resource" -> namingResources.removeResource(name);
            case "resourceLink" -> namingResources.removeResourceLink(name);
            case "resourceEnvRef" -> namingResources.removeResourceEnvRef(name);
            case "environment" -> namingResources.removeEnvironment(name);
            case "ejb" -> namingResources.removeEjb(name);
            case "localEjb" -> namingResources.removeLocalEjb(name);
            case "serviceRef" -> namingResources.removeService(name);
            default -> {
            }
        }
    }


    protected static void addNamingEntryTo(NamingResourcesImpl namingResources, String type, ResourceBase entry) {
        switch (type) {
            case "resource" -> namingResources.addResource((ContextResource) entry);
            case "resourceLink" -> namingResources.addResourceLink((ContextResourceLink) entry);
            case "resourceEnvRef" -> namingResources.addResourceEnvRef((ContextResourceEnvRef) entry);
            case "environment" -> namingResources.addEnvironment((ContextEnvironment) entry);
            case "ejb" -> namingResources.addEjb((ContextEjb) entry);
            case "localEjb" -> namingResources.addLocalEjb((ContextLocalEjb) entry);
            case "serviceRef" -> namingResources.addService((ContextService) entry);
            default -> {
            }
        }
    }


    /**
     * Convert a JSON value to the Java type of the attribute.
     */
    protected static Object convert(String type, Object json) throws ConfigException {
        String message = Strings.sm().getString("manager2.configInvalidValue", type);
        try {
            switch (type) {
                case "boolean":
                    if (json instanceof Boolean b) {
                        return b;
                    }
                    return Boolean.valueOf(Boolean.parseBoolean(String.valueOf(json)));
                case "int":
                    return Integer.valueOf(Integer.parseInt(String.valueOf(json).strip()));
                case "long":
                    return Long.valueOf(Long.parseLong(String.valueOf(json).strip()));
                case "short":
                    return Short.valueOf(Short.parseShort(String.valueOf(json).strip()));
                case "byte":
                    return Byte.valueOf(Byte.parseByte(String.valueOf(json).strip()));
                case "float":
                    return Float.valueOf(Float.parseFloat(String.valueOf(json).strip()));
                case "double":
                    return Double.valueOf(Double.parseDouble(String.valueOf(json).strip()));
                case "java.lang.String":
                    return String.valueOf(json);
                case "[Ljava.lang.String;":
                    if (json instanceof List<?> list) {
                        String[] result = new String[list.size()];
                        for (int i = 0; i < list.size(); i++) {
                            result[i] = String.valueOf(list.get(i));
                        }
                        return result;
                    }
                    return String.valueOf(json).split(",");
                default:
                    throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "UNSUPPORTED_TYPE", message);
            }
        } catch (NumberFormatException e) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_VALUE", message);
        }
    }


    protected static void setValue(Object component, AttributeInfo attribute, Object value) throws ConfigException {

        String method = attribute.getSetMethod();
        if (method == null) {
            method = "set" + capitalize(attribute.getName());
        }
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


    // ----------------------------------------------------- Structural ops


    /**
     * Register a new component (the {@code add} operation) and verify that it actually started. The register operations
     * of the core components report a failed start inconsistently: some throw, some log and leave a failed component
     * behind, the valve pipeline even cleans up silently. All cases are handled here: on a failed start the
     * {@code undo} operation is attempted and a controlled error is raised, so that no broken component is left
     * registered.
     */
    protected void addChecked(String label, Object component, Runnable add, Runnable undo) throws ConfigException {
        try {
            add.run();
        } catch (Exception e) {
            log(Strings.sm().getString("manager2.error.config"), e);
            rollback(label, undo);
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "START_FAILED",
                    Strings.sm().getString("manager2.configStartFailed", label, rootMessage(e)));
        }
        if (component instanceof Lifecycle lifecycle && !lifecycle.getState().isAvailable()) {
            rollback(label, undo);
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "START_FAILED",
                    Strings.sm().getString("manager2.configStartFailed", label, Strings.sm().getString("manager2.configNotStarted")));
        }
    }


    protected void rollback(String label, Runnable undo) {
        try {
            undo.run();
        } catch (Exception e) {
            log(Strings.sm().getString("manager2.configRollbackFailed", label,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        }
    }


    /**
     * The message of the deepest cause of the given exception.
     */
    protected static String rootMessage(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return (message == null || message.isEmpty()) ? current.getClass().getSimpleName() : message;
    }


    protected void addChild(HttpServletResponse response, Map<String, Object> body) throws Exception {

        String parentId = string(body.get("parent"));
        String type = string(body.get("type"));
        if (parentId == null || type == null) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_ID",
                    Strings.sm().getString("manager2.configInvalidId"));
        }
        NodeRef parent = resolve(parentId);

        // The children of an SSL host configuration are a certificate or a
        // pre-shared key. For a certificate the request body carries the
        // certificate type (RSA, DSA, ...) in the "type" field instead of a
        // child component type (a pre-shared key has no such type), so the
        // two are told apart by whether "type" names the pre-shared key
        // component type.
        if (parent.component instanceof SSLHostConfig) {
            if ("preSharedKey".equals(type)) {
                addPreSharedKey(response, parent, body);
            } else {
                addCertificate(response, parent, body);
            }
            return;
        }

        switch (type) {
            case "service" -> addService(response, parent, body);
            case "host" -> addHost(response, parent, body);
            case "context" -> addContext(response, parent, body);
            case "wrapper" -> addWrapper(response, parent, body);
            case "valve" -> addValve(response, parent, body);
            case "connector" -> addConnector(response, parent, body);
            case "executor" -> addExecutor(response, parent, body);
            case "alias" -> addAlias(response, parent, body);
            case "listener" -> addListener(response, parent, body);
            case "realm" -> addRealm(response, parent, body);
            case "manager" -> addContextComponent(response, parent, body, "manager");
            case "resources" -> addContextComponent(response, parent, body, "resources");
            case "loader" -> addContextComponent(response, parent, body, "loader");
            case "cookieProcessor" -> addContextComponent(response, parent, body, "cookieProcessor");
            case "sessionIdGenerator" -> addSessionIdGenerator(response, parent, body);
            case "resource" -> addNamingEntry(response, parent, body, "resource");
            case "resourceLink" -> addNamingEntry(response, parent, body, "resourceLink");
            case "resourceEnvRef" -> addNamingEntry(response, parent, body, "resourceEnvRef");
            case "environment" -> addNamingEntry(response, parent, body, "environment");
            case "ejb" -> addNamingEntry(response, parent, body, "ejb");
            case "localEjb" -> addNamingEntry(response, parent, body, "localEjb");
            case "serviceRef" -> addNamingEntry(response, parent, body, "serviceRef");
            case "sslHostConfig" -> addSslHostConfig(response, parent, body);
            case "upgradeProtocol" -> addUpgradeProtocol(response, parent, body);
            case "certificate" -> addCertificate(response, parent, body);
            case "preSharedKey" -> addPreSharedKey(response, parent, body);
            case "cluster" -> addCluster(response, parent, body);
            case "clusterValve" -> addClusterValve(response, parent, body);
            case "channel" -> addClusterChannel(response, parent, body);
            case "membership" -> addClusterMembership(response, parent, body);
            case "sender" -> addClusterSender(response, parent, body);
            case "receiver" -> addClusterReceiver(response, parent, body);
            case "interceptor" -> addInterceptor(response, parent, body);
            case "deployer" -> addClusterDeployer(response, parent, body);
            case "clusterManager" -> addClusterManager(response, parent, body);
            case "transport" -> addClusterTransport(response, parent, body);
            case "clusterListener" -> addClusterListener(response, parent, body);
            default -> throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "UNSUPPORTED_TYPE",
                    Strings.sm().getString("manager2.configTypeUnsupported", type));
        }
    }


    protected void addAlias(HttpServletResponse response, NodeRef parent, Map<String, Object> body) throws Exception {

        if (!(parent.component instanceof Host host)) {
            throw badParent("alias");
        }
        String alias = string(body.get("alias"));
        if (alias == null || alias.isEmpty() || alias.contains(" ") || alias.contains(",")) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_NAME",
                    Strings.sm().getString("manager2.configInvalidName", alias));
        }
        if (Arrays.asList(host.findAliases()).contains(alias)) {
            throw duplicate(alias);
        }
        host.addAlias(alias);
        log(Strings.sm().getString("manager2.configAuditAdd", "alias", alias));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", alias));
    }


    /**
     * A service in this Tomcat version holds exactly one engine (its {@code container}), so adding a service creates
     * the service together with a new engine of the same name.
     */
    protected void addService(HttpServletResponse response, NodeRef parent, Map<String, Object> body) throws Exception {

        if (!(parent.component instanceof Server)) {
            throw badParent("service");
        }
        String name = requiredName(body.get("name"));
        if (server.findService(name) != null) {
            throw duplicate(name);
        }
        StandardService service = new StandardService();
        service.setName(name);
        StandardEngine engine = new StandardEngine();
        engine.setName(name);
        engine.setRealm(new MemoryRealm());
        service.setContainer(engine);
        Server serverComponent = (Server) parent.component;
        addChecked(name, service, () -> serverComponent.addService(service),
                () -> serverComponent.removeService(service));
        log(Strings.sm().getString("manager2.configAuditAdd", "service", name));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", name));
    }


    protected void addHost(HttpServletResponse response, NodeRef parent, Map<String, Object> body) throws Exception {

        if (!(parent.component instanceof StandardEngine engine)) {
            throw badParent("host");
        }
        String name = requiredName(body.get("name"));
        if (engine.findChild(name) instanceof Host) {
            throw duplicate(name);
        }

        String appBase = string(body.get("appBase"));
        if (appBase == null || appBase.isEmpty()) {
            appBase = name;
        }
        File appBaseFile = new File(appBase);
        if (!appBaseFile.isAbsolute()) {
            appBaseFile = new File(engine.getCatalinaBase(), appBaseFile.getPath());
        }
        appBaseFile = appBaseFile.getCanonicalFile();
        if (!appBaseFile.getPath().startsWith(engine.getCatalinaBase().getPath() + File.separator) &&
                !appBaseFile.getPath().equals(engine.getCatalinaBase().getPath())) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_PATH",
                    Strings.sm().getString("manager2.configInvalidPath", appBase));
        }
        if (!appBaseFile.mkdirs() && !appBaseFile.isDirectory()) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_PATH",
                    Strings.sm().getString("manager2.configInvalidPath", appBase));
        }

        StandardHost host = new StandardHost();
        host.setName(name);
        host.setAppBase(appBase);
        host.addLifecycleListener(new HostConfig());
        for (String alias : stringList(body.get("aliases"))) {
            host.addAlias(alias);
        }
        host.setAutoDeploy(bool(body.get("autoDeploy"), true));
        host.setDeployOnStartup(bool(body.get("deployOnStartup"), true));
        host.setDeployXML(bool(body.get("deployXML"), true));
        host.setUnpackWARs(bool(body.get("unpackWARs"), true));
        host.setCopyXML(bool(body.get("copyXML"), false));
        addChecked(name, host, () -> engine.addChild(host), () -> engine.removeChild(host));
        log(Strings.sm().getString("manager2.configAuditAdd", "host", name));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", name));
    }


    protected void addContext(HttpServletResponse response, NodeRef parent, Map<String, Object> body) throws Exception {

        if (!(parent.component instanceof Host)) {
            throw badParent("context");
        }
        Host host = (Host) parent.component;
        String path = string(body.get("path"));
        if (path == null || !path.startsWith("/") || path.length() < 2 || path.contains("..") || path.contains(" ")) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_PATH",
                    Strings.sm().getString("manager2.configInvalidPath", path));
        }
        if (host.findChild(path) instanceof Context) {
            throw duplicate(path);
        }

        StandardContext context = new StandardContext();
        context.setPath(path);
        context.setName(path);
        // A docBase is interpreted exactly like in server.xml: a relative
        // value is resolved against the appBase of the host. The default
        // (as for HostConfig) is the context path below the appBase.
        String docBase = string(body.get("docBase"));
        if (docBase != null && !docBase.isEmpty()) {
            context.setDocBase(docBase);
        } else {
            context.setDocBase(path.length() > 1 ? path.substring(1) : "ROOT");
        }
        context.setParent(host);
        File docBaseFile = contextDocBaseFile(context);
        if (docBaseFile != null && !docBaseFile.getName().endsWith(".war") && !docBaseFile.isDirectory() &&
                !docBaseFile.mkdirs()) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_PATH",
                    Strings.sm().getString("manager2.configDocBaseMissing", docBaseFile.getPath()));
        }
        String displayName = string(body.get("displayName"));
        if (displayName != null && !displayName.isEmpty()) {
            context.setDisplayName(displayName);
        }
        context.addLifecycleListener(new ContextConfig());
        addChecked(path, context, () -> host.addChild(context), () -> host.removeChild(context));
        log(Strings.sm().getString("manager2.configAuditAdd", "context", path));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", path));
    }


    /**
     * The resolved location of the context document base (a relative {@code docBase} is resolved against the appBase of
     * the host).
     */
    protected static File contextDocBaseFile(StandardContext context) {
        File file = new File(context.getDocBase());
        if (!file.isAbsolute() && context.getParent() instanceof Host host) {
            file = new File(host.getAppBaseFile(), file.getPath());
        }
        return file;
    }


    protected void addWrapper(HttpServletResponse response, NodeRef parent, Map<String, Object> body) throws Exception {

        if (!(parent.component instanceof Context)) {
            throw badParent("wrapper");
        }
        Context context = (Context) parent.component;
        String servletClass = string(body.get("servletClass"));
        if (servletClass == null || servletClass.isEmpty()) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_NAME",
                    Strings.sm().getString("manager2.configInvalidName", "servletClass"));
        }
        String name = string(body.get("name"));
        if (name == null || name.isEmpty()) {
            name = servletClass;
        }
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_NAME",
                    Strings.sm().getString("manager2.configInvalidName", name));
        }
        if (context.findChild(name) instanceof Wrapper) {
            throw duplicate(name);
        }

        StandardWrapper wrapper = new StandardWrapper();
        wrapper.setName(name);
        wrapper.setServletClass(servletClass);
        wrapper.setLoadOnStartup(intValue(body.get("loadOnStartup"), 0));
        addChecked(name, wrapper, () -> context.addChild(wrapper), () -> context.removeChild(wrapper));
        for (String pattern : stringList(body.get("urlPatterns"))) {
            context.addServletMapping(pattern, name);
        }
        log(Strings.sm().getString("manager2.configAuditAdd", "wrapper", name));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", name));
    }


    protected void addValve(HttpServletResponse response, NodeRef parent, Map<String, Object> body) throws Exception {

        if (!(parent.component instanceof Container)) {
            throw badParent("valve");
        }
        String className = string(body.get("className"));
        if (className == null || className.isEmpty()) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_NAME",
                    Strings.sm().getString("manager2.configInvalidName", "className"));
        }
        Valve valve;
        try {
            // Valves are server level classes: never use the webapp class
            // loader.
            valve = (Valve) Class.forName(className, true, server.getClass().getClassLoader()).getConstructor()
                    .newInstance();
        } catch (Exception e) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_CLASS",
                    Strings.sm().getString("manager2.configInvalidClass", className));
        }
        Pipeline pipeline = ((Container) parent.component).getPipeline();
        addChecked(className, valve, () -> pipeline.addValve(valve), () -> pipeline.removeValve(valve));
        log(Strings.sm().getString("manager2.configAuditAdd", "valve", className));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", className));
    }


    protected void addListener(HttpServletResponse response, NodeRef parent, Map<String, Object> body) throws Exception {

        // Every component that implements Lifecycle accepts a lifecycle
        // listener (containers, valves, connectors, executors, ...). An
        // alias is excluded: it is just a name on the host, not a
        // component that holds listeners.
        if ("alias".equals(parent.type) || !(parent.component instanceof Lifecycle lifecycle)) {
            throw badParent("listener");
        }
        String className = string(body.get("className"));
        if (className == null || className.isEmpty()) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_NAME",
                    Strings.sm().getString("manager2.configInvalidName", "className"));
        }
        LifecycleListener listener;
        try {
            // Listeners are server level classes: never use the webapp
            // class loader.
            listener = (LifecycleListener) Class.forName(className, true, server.getClass().getClassLoader())
                    .getConstructor().newInstance();
        } catch (Exception e) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_CLASS",
                    Strings.sm().getString("manager2.configInvalidClass", className));
        }
        // A listener is registered, not started: the parent never drives
        // its lifecycle (a listener that is itself a Lifecycle, such as a
        // valve used as a listener, legitimately stays not started).
        try {
            lifecycle.addLifecycleListener(listener);
        } catch (Exception e) {
            log(Strings.sm().getString("manager2.error.config"), e);
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "ADD_FAILED",
                    Strings.sm().getString("manager2.configAddFailed", className, rootMessage(e)));
        }
        log(Strings.sm().getString("manager2.configAuditAdd", "listener", className));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", className));
    }


    // ------------------------------------------- Cluster components


    /**
     * Instantiate a cluster component (a class from the clustering jars) using the server class loader, or a sensible
     * default when no class name is given.
     */
    protected <T> T newClusterComponent(String className, String defaultClassName, Class<T> type) throws ConfigException {
        String name = (className == null || className.isEmpty()) ? defaultClassName : className;
        try {
            return type
                    .cast(Class.forName(name, true, server.getClass().getClassLoader()).getConstructor().newInstance());
        } catch (Exception e) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_CLASS",
                    Strings.sm().getString("manager2.configInvalidClass", name));
        }
    }


    /**
     * Add a cluster to a container (engine, host or context). The cluster is a lifecycle component: when the container
     * is running, {@code setCluster} starts it (which starts the channel and applies the cluster defaults), so the
     * start is verified and rolled back on failure.
     */
    protected void addCluster(HttpServletResponse response, NodeRef parent, Map<String, Object> body) throws Exception {
        if (!(parent.component instanceof Container container)) {
            throw badParent("cluster");
        }
        String className = string(body.get("className"));
        String label = (className == null || className.isEmpty()) ? SimpleTcpCluster.class.getName() : className;
        CatalinaCluster cluster = newClusterComponent(className, SimpleTcpCluster.class.getName(),
                CatalinaCluster.class);
        addChecked(label, cluster, () -> container.setCluster(cluster), () -> container.setCluster(null));
        log(Strings.sm().getString("manager2.configAuditAdd", "cluster", label));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", label));
    }


    /**
     * Add a cluster valve (a {@code Valve} that implements {@code ClusterValve}) to the cluster.
     */
    protected void addClusterValve(HttpServletResponse response, NodeRef parent, Map<String, Object> body)
            throws Exception {
        if (!(parent.component instanceof CatalinaCluster cluster)) {
            throw badParent("clusterValve");
        }
        String className = string(body.get("className"));
        if (className == null || className.isEmpty()) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_NAME",
                    Strings.sm().getString("manager2.configInvalidName", "className"));
        }
        Valve valve;
        try {
            valve = (Valve) Class.forName(className, true, server.getClass().getClassLoader()).getConstructor()
                    .newInstance();
        } catch (Exception e) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_CLASS",
                    Strings.sm().getString("manager2.configInvalidClass", className));
        }
        if (!(valve instanceof ClusterValve)) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_CLASS",
                    Strings.sm().getString("manager2.configInvalidClass", className));
        }
        try {
            cluster.addValve(valve);
        } catch (Exception e) {
            log(Strings.sm().getString("manager2.error.config"), e);
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "ADD_FAILED",
                    Strings.sm().getString("manager2.configAddFailed", className, rootMessage(e)));
        }
        log(Strings.sm().getString("manager2.configAuditAdd", "clusterValve", className));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", className));
    }


    /**
     * Replace the channel of the cluster.
     */
    protected void addClusterChannel(HttpServletResponse response, NodeRef parent, Map<String, Object> body)
            throws Exception {
        if (!(parent.component instanceof CatalinaCluster cluster)) {
            throw badParent("channel");
        }
        String className = string(body.get("className"));
        String label = (className == null || className.isEmpty()) ? GroupChannel.class.getName() : className;
        Channel channel = newClusterComponent(className, GroupChannel.class.getName(), Channel.class);
        Channel previous = cluster.getChannel();
        try {
            cluster.setChannel(channel);
        } catch (Exception e) {
            cluster.setChannel(previous);
            throw addFailed(label, e);
        }
        log(Strings.sm().getString("manager2.configAuditAdd", "channel", label));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", label));
    }


    /**
     * Replace the membership service of the channel.
     */
    protected void addClusterMembership(HttpServletResponse response, NodeRef parent, Map<String, Object> body)
            throws Exception {
        if (!(parent.component instanceof ManagedChannel managed)) {
            throw badParent("membership");
        }
        String className = string(body.get("className"));
        String label = (className == null || className.isEmpty()) ? McastService.class.getName() : className;
        MembershipService membership = newClusterComponent(className, McastService.class.getName(),
                MembershipService.class);
        MembershipService previous = managed.getMembershipService();
        try {
            managed.setMembershipService(membership);
        } catch (Exception e) {
            managed.setMembershipService(previous);
            throw addFailed(label, e);
        }
        log(Strings.sm().getString("manager2.configAuditAdd", "membership", label));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", label));
    }


    /**
     * Replace the sender of the channel.
     */
    protected void addClusterSender(HttpServletResponse response, NodeRef parent, Map<String, Object> body)
            throws Exception {
        if (!(parent.component instanceof ManagedChannel managed)) {
            throw badParent("sender");
        }
        String className = string(body.get("className"));
        String label = (className == null || className.isEmpty()) ? ReplicationTransmitter.class.getName() : className;
        ChannelSender sender = newClusterComponent(className, ReplicationTransmitter.class.getName(),
                ChannelSender.class);
        ChannelSender previous = managed.getChannelSender();
        try {
            managed.setChannelSender(sender);
        } catch (Exception e) {
            managed.setChannelSender(previous);
            throw addFailed(label, e);
        }
        log(Strings.sm().getString("manager2.configAuditAdd", "sender", label));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", label));
    }


    /**
     * Replace the receiver of the channel.
     */
    protected void addClusterReceiver(HttpServletResponse response, NodeRef parent, Map<String, Object> body)
            throws Exception {
        if (!(parent.component instanceof ManagedChannel managed)) {
            throw badParent("receiver");
        }
        String className = string(body.get("className"));
        String label = (className == null || className.isEmpty())
                ? "org.apache.catalina.tribes.transport.nio.NioReceiver"
                : className;
        ChannelReceiver receiver = newClusterComponent(className,
                "org.apache.catalina.tribes.transport.nio.NioReceiver", ChannelReceiver.class);
        ChannelReceiver previous = managed.getChannelReceiver();
        try {
            managed.setChannelReceiver(receiver);
        } catch (Exception e) {
            managed.setChannelReceiver(previous);
            throw addFailed(label, e);
        }
        log(Strings.sm().getString("manager2.configAuditAdd", "receiver", label));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", label));
    }


    /**
     * Add an interceptor to the channel.
     */
    protected void addInterceptor(HttpServletResponse response, NodeRef parent, Map<String, Object> body)
            throws Exception {
        if (!(parent.component instanceof ManagedChannel managed)) {
            throw badParent("interceptor");
        }
        String className = string(body.get("className"));
        if (className == null || className.isEmpty()) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_NAME",
                    Strings.sm().getString("manager2.configInvalidName", "className"));
        }
        ChannelInterceptor interceptor = newClusterComponent(className, null, ChannelInterceptor.class);
        try {
            managed.addInterceptor(interceptor);
        } catch (Exception e) {
            log(Strings.sm().getString("manager2.error.config"), e);
            throw addFailed(className, e);
        }
        log(Strings.sm().getString("manager2.configAuditAdd", "interceptor", className));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", className));
    }


    /**
     * Replace the deployer of the cluster.
     */
    protected void addClusterDeployer(HttpServletResponse response, NodeRef parent, Map<String, Object> body)
            throws Exception {
        if (!(parent.component instanceof CatalinaCluster cluster)) {
            throw badParent("deployer");
        }
        String className = string(body.get("className"));
        String label = (className == null || className.isEmpty()) ? "org.apache.catalina.ha.deploy.FarmWarDeployer"
                : className;
        ClusterDeployer deployer = newClusterComponent(className, "org.apache.catalina.ha.deploy.FarmWarDeployer",
                ClusterDeployer.class);
        ClusterDeployer previous = cluster.getClusterDeployer();
        try {
            cluster.setClusterDeployer(deployer);
        } catch (Exception e) {
            cluster.setClusterDeployer(previous);
            throw addFailed(label, e);
        }
        log(Strings.sm().getString("manager2.configAuditAdd", "deployer", label));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", label));
    }


    /**
     * Replace the manager template of the cluster.
     */
    protected void addClusterManager(HttpServletResponse response, NodeRef parent, Map<String, Object> body)
            throws Exception {
        if (!(parent.component instanceof SimpleTcpCluster tcp)) {
            throw badParent("clusterManager");
        }
        String className = string(body.get("className"));
        String label = (className == null || className.isEmpty()) ? "org.apache.catalina.ha.session.DeltaManager"
                : className;
        ClusterManager manager = newClusterComponent(className, "org.apache.catalina.ha.session.DeltaManager",
                ClusterManager.class);
        ClusterManager previous = tcp.getManagerTemplate();
        try {
            tcp.setManagerTemplate(manager);
        } catch (Exception e) {
            tcp.setManagerTemplate(previous);
            throw addFailed(label, e);
        }
        log(Strings.sm().getString("manager2.configAuditAdd", "clusterManager", label));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", label));
    }


    /**
     * Replace the transport of the sender (a replication transmitter).
     */
    protected void addClusterTransport(HttpServletResponse response, NodeRef parent, Map<String, Object> body)
            throws Exception {
        if (!(parent.component instanceof ReplicationTransmitter transmitter)) {
            throw badParent("transport");
        }
        String className = string(body.get("className"));
        String label = (className == null || className.isEmpty())
                ? "org.apache.catalina.tribes.transport.nio.PooledParallelSender"
                : className;
        MultiPointSender transport = newClusterComponent(className,
                "org.apache.catalina.tribes.transport.nio.PooledParallelSender", MultiPointSender.class);
        MultiPointSender previous = transmitter.getTransport();
        try {
            transmitter.setTransport(transport);
        } catch (Exception e) {
            transmitter.setTransport(previous);
            throw addFailed(label, e);
        }
        log(Strings.sm().getString("manager2.configAuditAdd", "transport", label));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", label));
    }


    /**
     * Add a cluster listener to the cluster.
     */
    protected void addClusterListener(HttpServletResponse response, NodeRef parent, Map<String, Object> body)
            throws Exception {
        if (!(parent.component instanceof CatalinaCluster cluster)) {
            throw badParent("clusterListener");
        }
        String className = string(body.get("className"));
        if (className == null || className.isEmpty()) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_NAME",
                    Strings.sm().getString("manager2.configInvalidName", "className"));
        }
        ClusterListener listener = newClusterComponent(className, null, ClusterListener.class);
        try {
            cluster.addClusterListener(listener);
        } catch (Exception e) {
            log(Strings.sm().getString("manager2.error.config"), e);
            throw addFailed(className, e);
        }
        log(Strings.sm().getString("manager2.configAuditAdd", "clusterListener", className));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", className));
    }


    protected static ConfigException addFailed(String label, Exception e) {
        return new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "ADD_FAILED",
                Strings.sm().getString("manager2.configAddFailed", label, rootMessage(e)));
    }


    /**
     * Add a realm: to a container (the container's own realm) or to a combined realm (a sub realm). The realm class is
     * a server level class and is identified by its class name.
     */
    protected void addRealm(HttpServletResponse response, NodeRef parent, Map<String, Object> body) throws Exception {

        String className = string(body.get("className"));
        if (className == null || className.isEmpty()) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_NAME",
                    Strings.sm().getString("manager2.configInvalidName", "className"));
        }
        Realm realm;
        try {
            // Realms are server level classes: never use the webapp class
            // loader.
            realm = (Realm) Class.forName(className, true, server.getClass().getClassLoader()).getConstructor()
                    .newInstance();
        } catch (Exception e) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_CLASS",
                    Strings.sm().getString("manager2.configInvalidClass", className));
        }

        if (parent.component instanceof Container container) {
            addContainerRealm(response, container, realm, className);
        } else if (parent.component instanceof CombinedRealm combined) {
            addSubRealm(response, combined, realm, className);
        } else {
            throw badParent("realm");
        }
    }


    protected void addContainerRealm(HttpServletResponse response, Container container, Realm realm, String className)
            throws Exception {

        if (ownRealm(container) != null) {
            throw duplicate(className);
        }
        Realm oldRealm = container.getRealm();
        boolean running = container.getState().isAvailable();
        try {
            // setRealm wires the realm to the container and, on a running
            // container, starts it.
            container.setRealm(realm);
        } catch (Exception e) {
            log(Strings.sm().getString("manager2.error.config"), e);
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "ADD_FAILED",
                    Strings.sm().getString("manager2.configAddFailed", className, rootMessage(e)));
        }
        // A realm that does not start on a running container is not
        // useful; roll the change back. On a stopped container the realm
        // is started when the container starts, so no check is needed.
        if (running && realm instanceof Lifecycle lifecycle && !lifecycle.getState().isAvailable()) {
            container.setRealm(oldRealm);
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "START_FAILED",
                    Strings.sm().getString("manager2.configStartFailed", className, Strings.sm().getString("manager2.configNotStarted")));
        }
        log(Strings.sm().getString("manager2.configAuditAdd", "realm", className));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", className));
    }


    protected void addSubRealm(HttpServletResponse response, CombinedRealm combined, Realm realm, String className)
            throws Exception {

        // The XML parser accepts at most this many nested Realm elements;
        // keep runtime additions within the same bound so that the state
        // still round trips through server.xml.
        if (nestedRealmDepth(combined) >= MAX_NESTED_REALM_LEVELS) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_VALUE",
                    Strings.sm().getString("manager2.configMaxNestedRealms"));
        }
        boolean running = combined.getState().isAvailable();
        try {
            combined.addRealm(realm);
            // Mirror what the XML flow achieves when the container
            // attaches the combined realm: give the sub realm a container
            // (for logging) and a distinct realm path (for JMX naming),
            // and start it while the combined realm is running (the
            // combined realm only starts sub realms at its own start).
            Container owner = combined.getContainer();
            if (owner != null) {
                realm.setContainer(owner);
                if (realm instanceof RealmBase realmBase) {
                    realmBase
                            .setRealmPath(combined.getRealmPath() + "/realm" + (combined.getNestedRealms().length - 1));
                }
            }
            if (running && realm instanceof Lifecycle lifecycle) {
                lifecycle.start();
            }
        } catch (Exception e) {
            log(Strings.sm().getString("manager2.error.config"), e);
            combined.removeRealm(realm);
            if (realm instanceof Lifecycle lifecycle && lifecycle.getState().isAvailable()) {
                try {
                    lifecycle.stop();
                } catch (Exception ignored) {
                    // Best effort; the removal failure is already logged.
                }
            }
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "ADD_FAILED",
                    Strings.sm().getString("manager2.configAddFailed", className, rootMessage(e)));
        }
        log(Strings.sm().getString("manager2.configAuditAdd", "realm", className));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", className));
    }


    /**
     * The number of realm levels in the chain starting at the given realm (the realm itself counts as one), following
     * the first sub realm of each combined realm.
     */
    protected static int nestedRealmDepth(Realm realm) {
        int depth = 0;
        for (Realm current = realm; current != null; current = firstSubRealm(current)) {
            depth++;
        }
        return depth;
    }


    protected static Realm firstSubRealm(Realm realm) {
        if (!(realm instanceof CombinedRealm combined)) {
            return null;
        }
        Realm[] nested = combined.getNestedRealms();
        return nested.length > 0 ? nested[0] : null;
    }


    /**
     * Add (replace) one of the sub components that a context holds exactly one of: the manager, the resources, the
     * loader or the cookie processor. A context always has one of each (the defaults are created at context start), so
     * the current instance is replaced by a new instance of the given class. The component class is a server level
     * class and is identified by its class name.
     */
    protected void addContextComponent(HttpServletResponse response, NodeRef parent, Map<String, Object> body,
            String type) throws Exception {

        if (!(parent.component instanceof Context context)) {
            throw badParent(type);
        }
        // Replacing the session manager (or the class loader) of this
        // web application's own context would destroy the admin session
        // (or the classes of the running application) mid-request.
        if (context == selfContext && ("manager".equals(type) || "loader".equals(type))) {
            throw self();
        }
        String className = string(body.get("className"));
        if (className == null || className.isEmpty()) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_NAME",
                    Strings.sm().getString("manager2.configInvalidName", "className"));
        }
        Object component;
        try {
            // These are server level classes: never use the webapp class
            // loader.
            Object instance = Class.forName(className, true, server.getClass().getClassLoader()).getConstructor()
                    .newInstance();
            switch (type) {
                case "manager" -> component = (Manager) instance;
                case "resources" -> component = (WebResourceRoot) instance;
                case "loader" -> component = (Loader) instance;
                default -> component = (CookieProcessor) instance;
            }
        } catch (Exception e) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_CLASS",
                    Strings.sm().getString("manager2.configInvalidClass", className));
        }

        boolean running = context.getState().isAvailable();
        switch (type) {
            case "manager" -> {
                Manager oldManager = context.getManager();
                try {
                    // setManager wires the manager to the context and, on
                    // a running context, stops the old manager and starts
                    // the new one.
                    context.setManager((Manager) component);
                } catch (Exception e) {
                    log(Strings.sm().getString("manager2.error.config"), e);
                    throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "ADD_FAILED",
                            Strings.sm().getString("manager2.configAddFailed", className, rootMessage(e)));
                }
                // A manager that does not start on a running context is
                // not useful; roll the change back. On a stopped context
                // the manager is started when the context starts.
                if (running && component instanceof Lifecycle lifecycle && !lifecycle.getState().isAvailable()) {
                    context.setManager(oldManager);
                    throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "START_FAILED",
                            Strings.sm().getString("manager2.configStartFailed", className, Strings.sm().getString("manager2.configNotStarted")));
                }
            }
            case "loader" -> {
                Loader oldLoader = context.getLoader();
                try {
                    // setLoader wires the loader to the context and, on a
                    // running context, stops the old loader and starts
                    // the new one.
                    context.setLoader((Loader) component);
                } catch (Exception e) {
                    log(Strings.sm().getString("manager2.error.config"), e);
                    throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "ADD_FAILED",
                            Strings.sm().getString("manager2.configAddFailed", className, rootMessage(e)));
                }
                if (running && component instanceof Lifecycle lifecycle && !lifecycle.getState().isAvailable()) {
                    context.setLoader(oldLoader);
                    throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "START_FAILED",
                            Strings.sm().getString("manager2.configStartFailed", className, Strings.sm().getString("manager2.configNotStarted")));
                }
            }
            case "resources" -> {
                // The context refuses to change its resources while it
                // is running: the resource tree of a live web
                // application cannot be swapped out from under it.
                if (running) {
                    throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "CONTEXT_RUNNING",
                            Strings.sm().getString("manager2.configContextMustBeStopped", displayName(context, "context")));
                }
                try {
                    // setResources wires the resources to the context.
                    // Their lifecycle is driven by the context (start).
                    context.setResources((WebResourceRoot) component);
                } catch (Exception e) {
                    log(Strings.sm().getString("manager2.error.config"), e);
                    throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "ADD_FAILED",
                            Strings.sm().getString("manager2.configAddFailed", className, rootMessage(e)));
                }
            }
            default -> {
                // The cookie processor has no lifecycle; the context
                // uses it from the moment it is set.
                try {
                    context.setCookieProcessor((CookieProcessor) component);
                } catch (Exception e) {
                    log(Strings.sm().getString("manager2.error.config"), e);
                    throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "ADD_FAILED",
                            Strings.sm().getString("manager2.configAddFailed", className, rootMessage(e)));
                }
            }
        }
        log(Strings.sm().getString("manager2.configAuditAdd", type, className));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", className));
    }


    /**
     * Add (replace) the session id generator of a manager. The manager uses it from the moment it is set; on a running
     * manager the old generator is stopped and the new one started.
     */
    protected void addSessionIdGenerator(HttpServletResponse response, NodeRef parent, Map<String, Object> body)
            throws Exception {

        if (!(parent.component instanceof Manager manager)) {
            throw badParent("sessionIdGenerator");
        }
        String className = string(body.get("className"));
        if (className == null || className.isEmpty()) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_NAME",
                    Strings.sm().getString("manager2.configInvalidName", "className"));
        }
        SessionIdGenerator generator;
        try {
            // Session id generators are server level classes: never use
            // the webapp class loader.
            generator = (SessionIdGenerator) Class.forName(className, true, server.getClass().getClassLoader())
                    .getConstructor().newInstance();
        } catch (Exception e) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_CLASS",
                    Strings.sm().getString("manager2.configInvalidClass", className));
        }

        // The Manager interface does not extend Lifecycle; all standard
        // managers do (ManagerBase), so the state is read conditionally.
        boolean running = manager instanceof Lifecycle && ((Lifecycle) manager).getState().isAvailable();
        SessionIdGenerator old = manager.getSessionIdGenerator();
        boolean oldRunning = running && old instanceof Lifecycle && ((Lifecycle) old).getState().isAvailable();
        try {
            if (oldRunning) {
                ((Lifecycle) old).stop();
            }
            manager.setSessionIdGenerator(generator);
            // The manager stamps its jvm route onto the generator at
            // start; mirror that for a live replacement.
            if (manager instanceof ManagerBase managerBase) {
                generator.setJvmRoute(managerBase.getJvmRoute());
            }
            if (running && generator instanceof Lifecycle lifecycle) {
                lifecycle.start();
            }
        } catch (Exception e) {
            log(Strings.sm().getString("manager2.error.config"), e);
            // Best effort rollback: stop the new generator (if it
            // started) and restore the previous one (the setter rejects
            // null, so "none" cannot be restored).
            try {
                if (generator instanceof Lifecycle lifecycle && lifecycle.getState().isAvailable()) {
                    lifecycle.stop();
                }
                if (manager.getSessionIdGenerator() == generator && old != null) {
                    manager.setSessionIdGenerator(old);
                }
                if (oldRunning && old instanceof Lifecycle lifecycle && !lifecycle.getState().isAvailable()) {
                    lifecycle.start();
                }
            } catch (Exception ignored) {
                // Best effort; the failure is already logged.
            }
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "ADD_FAILED",
                    Strings.sm().getString("manager2.configAddFailed", className, rootMessage(e)));
        }
        log(Strings.sm().getString("manager2.configAuditAdd", "sessionIdGenerator", className));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", className));
    }


    /**
     * Add one JNDI entry to a {@code NamingResourcesImpl}. The entry takes effect in the live JNDI environment of the
     * server (the global context) or of the context ({@code java:comp/env}) immediately: the add fires the property
     * change event the {@code NamingContextListener} reacts to.
     * <p>
     * The request body carries the entry fields: {@code name} (the JNDI name), {@code jndiType} (the type of the
     * object) and the type specific fields ({@code auth}, {@code factory}, ...). A nested {@code params} object is
     * applied as the free form string parameters of the entry; for the first party JNDI factories with a closed set of
     * parameters the values are validated against the type the factory parses them as.
     */
    protected void addNamingEntry(HttpServletResponse response, NodeRef parent, Map<String, Object> body, String type)
            throws Exception {

        if (!(parent.component instanceof NamingResourcesImpl namingResources)) {
            throw badParent(type);
        }
        // Resource links are only part of a context JNDI environment;
        // they are not parsed from <GlobalNamingResources>.
        if ("resourceLink".equals(type) && namingResources.getContainer() instanceof Server) {
            throw badParent(type);
        }
        String name = string(body.get("name"));
        if (name == null || name.isEmpty()) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_ID",
                    Strings.sm().getString("manager2.configInvalidId"));
        }
        if (findNamingEntry(namingResources, type, name) != null) {
            throw duplicate(name);
        }
        String jndiType = string(body.get("jndiType"));
        if (jndiType == null || jndiType.isEmpty()) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "MISSING_FIELD",
                    Strings.sm().getString("manager2.configJndiTypeRequired", type));
        }

        ResourceBase entry;
        switch (type) {
            case "resource" -> {
                ContextResource resource = new ContextResource();
                resource.setName(name);
                resource.setType(jndiType);
                String auth = string(body.get("auth"));
                if (auth != null) {
                    resource.setAuth(auth);
                }
                resource.setSingleton(bool(body.get("singleton"), true));
                String closeMethod = string(body.get("closeMethod"));
                if (closeMethod != null) {
                    resource.setCloseMethod(closeMethod);
                }
                String lookupName = string(body.get("lookupName"));
                if (lookupName != null) {
                    resource.setLookupName(lookupName);
                }
                String description = string(body.get("description"));
                if (description != null) {
                    resource.setDescription(description);
                }
                entry = resource;
            }
            case "resourceLink" -> {
                ContextResourceLink link = new ContextResourceLink();
                link.setName(name);
                link.setType(jndiType);
                String global = string(body.get("global"));
                if (global == null || global.isEmpty()) {
                    throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "MISSING_FIELD",
                            Strings.sm().getString("manager2.configJndiGlobalRequired"));
                }
                link.setGlobal(global);
                String description = string(body.get("description"));
                if (description != null) {
                    link.setDescription(description);
                }
                entry = link;
            }
            case "resourceEnvRef" -> {
                ContextResourceEnvRef resourceEnvRef = new ContextResourceEnvRef();
                resourceEnvRef.setName(name);
                resourceEnvRef.setType(jndiType);
                resourceEnvRef.setOverride(bool(body.get("override"), true));
                String description = string(body.get("description"));
                if (description != null) {
                    resourceEnvRef.setDescription(description);
                }
                entry = resourceEnvRef;
            }
            case "environment" -> {
                ContextEnvironment environment = new ContextEnvironment();
                environment.setName(name);
                environment.setType(jndiType);
                String value = string(body.get("value"));
                if (value != null) {
                    environment.setValue(value);
                }
                environment.setOverride(bool(body.get("override"), true));
                String description = string(body.get("description"));
                if (description != null) {
                    environment.setDescription(description);
                }
                entry = environment;
            }
            case "ejb" -> {
                ContextEjb ejb = new ContextEjb();
                ejb.setName(name);
                ejb.setType(jndiType);
                String home = string(body.get("home"));
                if (home != null) {
                    ejb.setHome(home);
                }
                String link = string(body.get("link"));
                if (link != null) {
                    ejb.setLink(link);
                }
                String remote = string(body.get("remote"));
                if (remote != null) {
                    ejb.setRemote(remote);
                }
                String description = string(body.get("description"));
                if (description != null) {
                    ejb.setDescription(description);
                }
                entry = ejb;
            }
            case "localEjb" -> {
                ContextLocalEjb localEjb = new ContextLocalEjb();
                localEjb.setName(name);
                localEjb.setType(jndiType);
                String local = string(body.get("local"));
                if (local != null) {
                    localEjb.setLocal(local);
                }
                String home = string(body.get("home"));
                if (home != null) {
                    localEjb.setHome(home);
                }
                String link = string(body.get("link"));
                if (link != null) {
                    localEjb.setLink(link);
                }
                String description = string(body.get("description"));
                if (description != null) {
                    localEjb.setDescription(description);
                }
                entry = localEjb;
            }
            default -> {
                ContextService service = new ContextService();
                service.setName(name);
                service.setType(jndiType);
                String serviceInterface = string(body.get("interface"));
                if (serviceInterface != null) {
                    service.setInterface(serviceInterface);
                }
                String displayname = string(body.get("displayname"));
                if (displayname != null) {
                    service.setDisplayname(displayname);
                }
                String wsdlfile = string(body.get("wsdlfile"));
                if (wsdlfile != null) {
                    service.setWsdlfile(wsdlfile);
                }
                String description = string(body.get("description"));
                if (description != null) {
                    service.setDescription(description);
                }
                entry = service;
            }
        }

        // The factory: a string parameter of a resource, a first class
        // attribute of a resource link.
        String factory = string(body.get("factory"));
        if (factory != null && !factory.isEmpty()) {
            checkFactoryLoadable(factory);
            if (entry instanceof ContextResourceLink link) {
                link.setFactory(factory);
            } else if (entry instanceof ContextResource) {
                entry.setProperty("factory", factory);
            }
        }
        // The generic string parameters of the entry (the ResourceBase
        // property map). For the types whose factory is one of the first
        // party factories with a closed set of options the values are
        // validated against that set; for the others (and for open set
        // factories) they are stored as-is.
        applyNamingParams(entry, factory, body.get("params"));

        try {
            addNamingEntryTo(namingResources, type, entry);
        } catch (Exception e) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "ADD_FAILED",
                    Strings.sm().getString("manager2.configAddFailed", name, rootMessage(e)));
        }
        log(Strings.sm().getString("manager2.configAuditAdd", type, name));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", name));
    }


    /**
     * Apply the free form string parameters of a new JNDI entry, validating the values against the closed parameter set
     * of the first party factory (the factories parse them at JNDI lookup time, which is too late for a useful error
     * message).
     */
    protected void applyNamingParams(ResourceBase entry, String factory, Object params) throws ConfigException {
        if (!(params instanceof Map<?, ?> map) || map.isEmpty()) {
            return;
        }
        List<ExplicitAttribute> options = factoryOptions(factory);
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey());
            String value = string(e.getValue());
            if (value == null) {
                continue;
            }
            if (options != null) {
                for (ExplicitAttribute option : options) {
                    if (option.getName().equals(key)) {
                        validateParamValue(key, value, option.getType());
                        break;
                    }
                }
            }
            entry.setProperty(key, value);
        }
    }


    protected static void validateParamValue(String name, String value, String type) throws ConfigException {
        try {
            String trimmed = value.strip();
            switch (type) {
                case "int" -> Integer.parseInt(trimmed);
                case "long" -> Long.parseLong(trimmed);
                case "boolean" -> {
                    String v = trimmed.toLowerCase(Locale.ROOT);
                    if (!"true".equals(v) && !"false".equals(v)) {
                        throw new NumberFormatException(trimmed);
                    }
                }
                default -> {
                    // A string.
                }
            }
        } catch (NumberFormatException e) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "SET_FAILED",
                    Strings.sm().getString("manager2.configSetFailed", name, Strings.sm().getString("manager2.configInvalidType", type)));
        }
    }


    protected void checkFactoryLoadable(String factory) throws ConfigException {
        try {
            // The factory is instantiated with the container class
            // loader at JNDI lookup time.
            Class.forName(factory, false, server.getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_CLASS",
                    Strings.sm().getString("manager2.configFactoryInvalid", factory));
        }
    }


    protected void addConnector(HttpServletResponse response, NodeRef parent, Map<String, Object> body) throws Exception {

        if (!(parent.component instanceof StandardService service)) {
            throw badParent("connector");
        }
        String protocol = string(body.get("protocol"));
        if (protocol == null || protocol.isEmpty()) {
            protocol = "HTTP/1.1";
        }
        Integer port = intValue(body.get("port"), -1);
        if (port < 1 || port > 65535) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_VALUE",
                    Strings.sm().getString("manager2.configInvalidValue", "port"));
        }
        Connector connector = new Connector(protocol);
        connector.setPort(port);
        addChecked(connectorLabel(connector), connector, () -> service.addConnector(connector),
                () -> service.removeConnector(connector));
        log(Strings.sm().getString("manager2.configAuditAdd", "connector", protocol + " (port " + port + ")"));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", protocol + " (port " + port + ")"));
    }


    protected void addExecutor(HttpServletResponse response, NodeRef parent, Map<String, Object> body) throws Exception {

        if (!(parent.component instanceof StandardService service)) {
            throw badParent("executor");
        }
        String name = requiredName(body.get("name"));
        for (Executor executor : service.findExecutors()) {
            if (executor.getName().equals(name)) {
                throw duplicate(name);
            }
        }
        StandardThreadExecutor executor = new StandardThreadExecutor();
        executor.setName(name);
        Integer maxThreads = intValue(body.get("maxThreads"), -1);
        if (maxThreads > 0) {
            executor.setMaxThreads(maxThreads);
        }
        Integer minSpare = intValue(body.get("minSpareThreads"), -1);
        if (minSpare >= 0) {
            executor.setMinSpareThreads(minSpare);
        }
        // The thread pool refuses to start when the minimum number of
        // threads exceeds the maximum (the defaults are 25 and 200, so an
        // explicit maxThreads below the default minimum must be rejected).
        if (executor.getMinSpareThreads() > executor.getMaxThreads()) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_VALUE",
                    Strings.sm().getString("manager2.configExecutorInvalid"));
        }
        addChecked(name, executor, () -> service.addExecutor(executor), () -> service.removeExecutor(executor));
        log(Strings.sm().getString("manager2.configAuditAdd", "executor", name));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", name));
    }


    /**
     * Add an SSL host configuration (one TLS virtual host) to a connector. Adding the first SSL host configuration
     * enables TLS on the connector. A running connector is restarted so that the TLS configuration takes effect; when
     * the restart fails (typically because no valid certificate is configured) the change is rolled back and the
     * connector is left running as it was.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    protected void addSslHostConfig(HttpServletResponse response, NodeRef parent, Map<String, Object> body)
            throws Exception {

        if (!(parent.component instanceof Connector connector)) {
            throw badParent("sslHostConfig");
        }
        AbstractHttp11Protocol http11 = sslProtocolHandler(connector);
        if (http11 == null) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "BAD_PARENT",
                    Strings.sm().getString("manager2.configSslUnsupported", connector.getProtocolHandlerClassName()));
        }
        if (isSelfConnector(connector)) {
            throw self();
        }
        String hostName = string(body.get("hostName"));
        if (hostName == null || hostName.isEmpty()) {
            // The default host configuration name ("_default_") is not
            // exposed by a public constant.
            hostName = "_default_";
        }
        if (!SAFE_NAME.matcher(hostName).matches()) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_NAME",
                    Strings.sm().getString("manager2.configInvalidName", hostName));
        }
        String hostNameLower = hostName.toLowerCase(Locale.ENGLISH);
        SSLHostConfig[] existing = connector.findSslHostConfigs();
        if (existing != null) {
            for (SSLHostConfig hostConfig : existing) {
                if (hostConfig.getHostName().equals(hostNameLower)) {
                    throw duplicate(hostNameLower);
                }
            }
        }

        boolean wasSslEnabled = http11.isSSLEnabled();
        boolean wasRunning = connector.getState().isAvailable();

        SSLHostConfig sslHostConfig = new SSLHostConfig();
        sslHostConfig.setHostName(hostNameLower);
        // An optional initial certificate. On a running connector that
        // is not TLS enabled yet it is required: a connector without
        // any certificate cannot complete a single TLS handshake.
        boolean certificateProvided = body.get("certificate") instanceof Map;
        if (wasRunning && !wasSslEnabled && !certificateProvided) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_VALUE",
                    Strings.sm().getString("manager2.configSslCertificateRequired"));
        }
        if (certificateProvided) {
            SSLHostConfigCertificate certificate = buildCertificate(sslHostConfig,
                    (Map<String, Object>) body.get("certificate"));
            try {
                sslHostConfig.addCertificate(certificate);
            } catch (IllegalArgumentException e) {
                throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_VALUE",
                        Strings.sm().getString("manager2.configInvalidValue", "certificate"));
            }
        }

        try {
            connector.addSslHostConfig(sslHostConfig);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "ADD_FAILED",
                    Strings.sm().getString("manager2.configAddFailed", hostNameLower, rootMessage(e)));
        }

        if (wasRunning && !wasSslEnabled) {
            // The endpoint is already bound, so its TLS configuration
            // (SSL implementation and the SSL contexts, which is where
            // the keystores are loaded) is initialized here instead of
            // at bind time. This validates the new TLS configuration
            // without interrupting the connector: from this point on,
            // new connections are served over TLS.
            try {
                endpointOf(connector).initialiseSsl();
            } catch (Exception e) {
                // TLS is switched off first: the endpoint refuses to
                // remove its default host configuration while it is
                // still serving TLS.
                http11.setSSLEnabled(false);
                try {
                    endpointOf(connector).removeSslHostConfig(hostNameLower);
                } catch (Exception e2) {
                    log(Strings.sm().getString("manager2.configRollbackFailed", hostNameLower, rootMessage(e2)), e2);
                }
                throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "ADD_FAILED",
                        Strings.sm().getString("manager2.configAddFailed", hostNameLower, rootMessage(e)));
            }
        }

        log(Strings.sm().getString("manager2.configAuditAdd", "sslHostConfig", hostNameLower));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", hostNameLower));
    }


    /**
     * Add an upgrade protocol (e.g. the HTTP/2 one) to a connector through {@code AbstractHttp11Protocol
     * .addUpgradeProtocol}. An upgrade protocol is only referenced when the connector is initialised, so the new
     * instance does not take effect on a running connector: it becomes active the next time the connector is
     * (re)started (see the lifecycle operations). No live activation is attempted.
     */
    protected void addUpgradeProtocol(HttpServletResponse response, NodeRef parent, Map<String, Object> body)
            throws Exception {

        if (!(parent.component instanceof Connector connector)) {
            throw badParent("upgradeProtocol");
        }
        if (http11ProtocolHandler(connector) == null) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "BAD_PARENT",
                    Strings.sm().getString("manager2.configUpgradeUnsupported", connector.getProtocolHandlerClassName()));
        }
        String className = string(body.get("className"));
        if (className == null || className.isEmpty()) {
            // The only UpgradeProtocol shipped with Tomcat is the HTTP/2 one.
            className = "org.apache.coyote.http2.Http2Protocol";
        }
        UpgradeProtocol upgradeProtocol;
        try {
            // Upgrade protocols are server level classes: never use the webapp
            // class loader.
            upgradeProtocol = (UpgradeProtocol) Class.forName(className, true, server.getClass().getClassLoader())
                    .getConstructor().newInstance();
        } catch (Exception e) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_CLASS",
                    Strings.sm().getString("manager2.configInvalidClass", className));
        }
        // The protocol handler keeps the protocols in a plain list without
        // checking for duplicates; a second protocol with the same name would
        // be silently shadowed when the connector is next initialised, so
        // reject it here.
        String label = upgradeProtocolLabel(upgradeProtocol);
        for (UpgradeProtocol existing : connector.findUpgradeProtocols()) {
            if (label.equals(upgradeProtocolLabel(existing))) {
                throw duplicate(label);
            }
        }
        connector.addUpgradeProtocol(upgradeProtocol);
        log(Strings.sm().getString("manager2.configAuditAdd", "upgradeProtocol", label));
        Api.ok(response, Strings.sm().getString("manager2.configUpgradeProtocolAdded", label));
    }


    /**
     * Add a certificate configuration to an SSL host configuration. On a running, TLS enabled connector the new
     * certificate is applied at once (the SSL context of the virtual host is re-created, which also validates the
     * keystore); when that fails the certificate is rolled back.
     */
    @SuppressWarnings("rawtypes")
    protected void addCertificate(HttpServletResponse response, NodeRef parent, Map<String, Object> body)
            throws Exception {

        if (!(parent.component instanceof SSLHostConfig sslHostConfig)) {
            throw badParent("certificate");
        }
        SSLHostConfigCertificate certificate = buildCertificate(sslHostConfig, body);
        try {
            sslHostConfig.addCertificate(certificate);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "ADD_FAILED",
                    Strings.sm().getString("manager2.configAddFailed", certificateLabel(certificate), rootMessage(e)));
        }

        Connector connector = connectorOfSsl(sslHostConfig);
        AbstractHttp11Protocol http11 = (connector != null) ? sslProtocolHandler(connector) : null;
        if (connector != null && connector.getState().isAvailable() && http11 != null && http11.isSSLEnabled()) {
            try {
                endpointOf(connector).reloadSslHostConfig(sslHostConfig.getHostName());
            } catch (Exception e) {
                sslHostConfig.getCertificates().remove(certificate);
                throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "ADD_FAILED",
                        Strings.sm().getString("manager2.configAddFailed", certificateLabel(certificate), rootMessage(e)));
            }
        }

        log(Strings.sm().getString("manager2.configAuditAdd", "certificate", certificateLabel(certificate)));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", certificateLabel(certificate)));
    }


    /**
     * Build a (not yet registered) certificate configuration from a JSON object. The same builder is used for the
     * certificate of a new SSL host configuration and for standalone certificates.
     */
    protected static SSLHostConfigCertificate buildCertificate(SSLHostConfig sslHostConfig, Map<String, Object> body)
            throws ConfigException {
        String typeName = string(body.get("type"));
        SSLHostConfigCertificate.Type type;
        if (typeName == null || typeName.isEmpty()) {
            type = SSLHostConfigCertificate.Type.UNDEFINED;
        } else {
            try {
                type = SSLHostConfigCertificate.Type.valueOf(typeName.strip().toUpperCase(Locale.ENGLISH));
            } catch (IllegalArgumentException e) {
                throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_NAME",
                        Strings.sm().getString("manager2.configInvalidName", typeName));
            }
        }
        SSLHostConfigCertificate certificate = new SSLHostConfigCertificate(sslHostConfig, type);
        setIfPresent(certificate, "certificateKeystoreFile", body);
        setIfPresent(certificate, "certificateKeystorePassword", body);
        setIfPresent(certificate, "certificateKeystorePasswordFile", body);
        setIfPresent(certificate, "certificateKeystoreType", body);
        setIfPresent(certificate, "certificateKeystoreProvider", body);
        setIfPresent(certificate, "certificateKeyAlias", body);
        setIfPresent(certificate, "certificateKeyPassword", body);
        setIfPresent(certificate, "certificateKeyPasswordFile", body);
        setIfPresent(certificate, "certificateFile", body);
        setIfPresent(certificate, "certificateChainFile", body);
        setIfPresent(certificate, "certificateKeyFile", body);
        return certificate;
    }


    /**
     * Add a pre-shared key configuration to an SSL host configuration. On a running, TLS enabled connector the new key
     * is applied at once (the SSL context of the virtual host is re-created, which with an OpenSSL based connector also
     * validates the key set); when that fails the key is rolled back.
     */
    protected void addPreSharedKey(HttpServletResponse response, NodeRef parent, Map<String, Object> body)
            throws Exception {

        if (!(parent.component instanceof SSLHostConfig sslHostConfig)) {
            throw badParent("preSharedKey");
        }
        SSLHostConfigPreSharedKey preSharedKey = buildPreSharedKey(sslHostConfig, body);
        String label = preSharedKeyLabel(preSharedKey);
        // The pre-shared keys are selected by identity; a second key with
        // the same identity would be silently shadowed (and, with an
        // OpenSSL based connector, make the SSL context creation fail), so
        // reject it here.
        for (SSLHostConfigPreSharedKey existing : sslHostConfig.getPreSharedKeys()) {
            if (label.equals(preSharedKeyLabel(existing))) {
                throw duplicate(label);
            }
        }
        sslHostConfig.addPreSharedKey(preSharedKey);

        Connector connector = connectorOfSsl(sslHostConfig);
        AbstractHttp11Protocol<?> http11 = (connector != null) ? sslProtocolHandler(connector) : null;
        if (connector != null && connector.getState().isAvailable() && http11 != null && http11.isSSLEnabled()) {
            try {
                endpointOf(connector).reloadSslHostConfig(sslHostConfig.getHostName());
            } catch (Exception e) {
                sslHostConfig.getPreSharedKeys().remove(preSharedKey);
                throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "ADD_FAILED",
                        Strings.sm().getString("manager2.configAddFailed", label, rootMessage(e)));
            }
        }

        log(Strings.sm().getString("manager2.configAuditAdd", "preSharedKey", label));
        Api.ok(response, Strings.sm().getString("manager2.configAdded", label));
    }


    /**
     * Build a (not yet registered) pre-shared key configuration from a JSON object. The identity (the name a client
     * presents to select the key) and the hexadecimal key are required; the message digest is optional (SHA256, the
     * default of the configuration class, or SHA384).
     */
    protected static SSLHostConfigPreSharedKey buildPreSharedKey(SSLHostConfig sslHostConfig, Map<String, Object> body)
            throws ConfigException {

        String identity = string(body.get("identity"));
        if (identity == null || identity.isEmpty()) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "MISSING_FIELD",
                    Strings.sm().getString("manager2.configPskIdentityRequired"));
        }
        String key = string(body.get("key"));
        if (key == null || key.isEmpty()) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "MISSING_FIELD",
                    Strings.sm().getString("manager2.configPskKeyRequired"));
        }
        SSLHostConfigPreSharedKey preSharedKey = new SSLHostConfigPreSharedKey(sslHostConfig);
        preSharedKey.setIdentity(identity);
        setIfPresent(preSharedKey, "key", body);
        setIfPresent(preSharedKey, "digest", body);
        return preSharedKey;
    }


    /**
     * Call one string setter on the target when the body carries a non-empty value for the property.
     */
    protected static void setIfPresent(Object target, String name, Map<String, Object> body) throws ConfigException {
        Object value = body.get(name);
        if (value == null) {
            return;
        }
        String s = String.valueOf(value);
        if (s.isEmpty()) {
            return;
        }
        try {
            target.getClass().getMethod("set" + capitalize(name), String.class).invoke(target, s);
        } catch (Exception e) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_VALUE",
                    Strings.sm().getString("manager2.configInvalidValue", name));
        }
    }


    /**
     * The protocol handler of the connector when it is the HTTP/1.1 variant, otherwise {@code null} (for example an AJP
     * connector).
     */
    @SuppressWarnings("rawtypes")
    protected static AbstractHttp11Protocol http11ProtocolHandler(Connector connector) {
        if (connector.getProtocolHandler() instanceof AbstractHttp11Protocol http11) {
            return http11;
        }
        return null;
    }


    /**
     * The protocol handler of the connector when it is the HTTP/1.1 variant that supports TLS, otherwise {@code null}
     * (for example an AJP connector).
     */
    @SuppressWarnings("rawtypes")
    protected static AbstractHttp11Protocol sslProtocolHandler(Connector connector) {
        return http11ProtocolHandler(connector);
    }


    /**
     * Whether the connector's protocol handler has TLS enabled.
     */
    @SuppressWarnings("rawtypes")
    protected boolean isSslEnabled(Connector connector) {
        AbstractHttp11Protocol http11 = sslProtocolHandler(connector);
        return http11 != null && http11.isSSLEnabled();
    }


    /**
     * The connector that owns the given SSL host configuration, or {@code null} when it is not attached to any
     * connector.
     */
    protected Connector connectorOfSsl(SSLHostConfig sslHostConfig) {
        for (Service service : server.findServices()) {
            for (Connector connector : service.findConnectors()) {
                SSLHostConfig[] hostConfigs = connector.findSslHostConfigs();
                if (hostConfigs == null) {
                    continue;
                }
                for (SSLHostConfig candidate : hostConfigs) {
                    if (candidate == sslHostConfig) {
                        return connector;
                    }
                }
            }
        }
        return null;
    }


    /**
     * The endpoint of the connector's protocol handler. The endpoint (and with it the SSL host configuration operations
     * that are not on the {@code ProtocolHandler} interface, such as {@code removeSslHostConfig} and
     * {@code reloadSslHostConfig}) is only reachable through the protected accessor of {@code AbstractProtocol}, so it
     * is invoked reflectively.
     */
    protected AbstractEndpoint<?, ?> endpointOf(Connector connector) throws ConfigException {
        try {
            Method m = AbstractProtocol.class.getDeclaredMethod("getEndpoint");
            m.setAccessible(true);
            return (AbstractEndpoint<?, ?>) m.invoke(connector.getProtocolHandler());
        } catch (Exception e) {
            log(Strings.sm().getString("manager2.error.config"), e);
            throw new ConfigException(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "SERVER_UNAVAILABLE",
                    Strings.sm().getString("manager2.configServerUnavailable"));
        }
    }


    /**
     * Whether the connector routes requests for the host that deploys this web application (the connector must never be
     * stopped or restarted, as that would destroy the admin session mid-request).
     */
    protected boolean isSelfConnector(Connector connector) {
        Service service = connector.getService();
        return service != null && service.getContainer() != null && selfHost.getParent() == service.getContainer();
    }


    @SuppressWarnings("rawtypes")
    protected void removeChild(HttpServletResponse response, Map<String, Object> body) throws Exception {

        String id = string(body.get("id"));
        if (id == null) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_ID",
                    Strings.sm().getString("manager2.configInvalidId"));
        }
        NodeRef ref = resolve(id);

        // Guards against removing the components that host this web
        // application (the session would be destroyed mid-request). An
        // alias is excluded: it is not a container, so removing one cannot
        // change where this web application is deployed, even on the
        // manager's own host.
        if (!"alias".equals(ref.type) && ref.component == selfContext) {
            throw self();
        }
        if (!"alias".equals(ref.type) && ref.component == selfHost) {
            throw self();
        }
        // The last service cannot be removed. Checked before the service
        // self-host check: with a single service both apply and the
        // structural invariant is the more informative answer.
        if (ref.type.equals("service") && server.findServices().length <= 1) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "LAST_SERVICE",
                    Strings.sm().getString("manager2.configLastService"));
        }
        if (ref.type.equals("service") && containsSelf((StandardService) ref.component)) {
            throw self();
        }
        if (ref.type.equals("engine") && ref.component instanceof Engine engine &&
                engine.findChild(selfHost.getName()) instanceof Host) {
            throw self();
        }

        if (ref.type.equals("valve") && isBasicValve(ref)) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "BASIC_COMPONENT",
                    Strings.sm().getString("manager2.configBasicValve"));
        }
        // The sub components a context holds exactly one of (and the
        // manager's session id generator) are required: they cannot be
        // removed, only replaced.
        if (Set.of("manager", "resources", "loader", "cookieProcessor", "sessionIdGenerator").contains(ref.type)) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "REQUIRED_COMPONENT",
                    Strings.sm().getString("manager2.configRequiredComponent", ref.type));
        }
        // The JNDI naming resources of the server and of a context are
        // always present and required: they cannot be removed.
        if (ref.type.equals("namingResources")) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "REQUIRED_COMPONENT",
                    Strings.sm().getString("manager2.configRequiredNamingResources"));
        }
        // Cluster valves and channel interceptors are repeatable sub
        // components that have no removal API on a running cluster; only
        // the cluster itself and its single-valued sub components can be
        // detached.
        if (ref.type.equals("clusterValve") || ref.type.equals("interceptor")) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "REMOVE_NOT_SUPPORTED",
                    Strings.sm().getString("manager2.configRemoveNotSupported", ref.type));
        }
        // A service holds exactly one engine (its container); it cannot be
        // removed while it still contains hosts.
        if (ref.type.equals("engine") && ref.component instanceof Engine engine && countHosts(engine) > 0) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "NOT_EMPTY",
                    Strings.sm().getString("manager2.configNotEmpty", "engine"));
        }
        // A directly attached realm can only be removed when the container
        // falls back to a parent realm afterwards; otherwise the container
        // (and everything below it) would have no realm at all.
        if (ref.type.equals("realm") && ref.parent instanceof Container container && fallbackRealm(container) == null) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "LAST_REALM",
                    Strings.sm().getString("manager2.configLastRealm"));
        }

        String label = ref.aliasValue != null ? ref.aliasValue : displayName(ref.component, ref.type);
        if (Set.of("host", "context", "service", "engine", "connector", "executor", "wrapper", "valve", "sslHostConfig",
                "upgradeProtocol", "realm", "cluster", "resource", "resourceLink", "resourceEnvRef", "environment",
                "ejb", "localEjb", "serviceRef").contains(ref.type)) {
            String confirm = string(body.get("confirm"));
            if (!label.equals(confirm)) {
                throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "CONFIRM_REQUIRED",
                        Strings.sm().getString("manager2.configConfirmRequired", label));
            }
        }

        if ("sslHostConfig".equals(ref.type)) {
            removeSslHostConfig(response, ref, label);
            return;
        }
        if ("certificate".equals(ref.type)) {
            removeCertificate(response, ref);
            return;
        }
        if ("preSharedKey".equals(ref.type)) {
            removePreSharedKey(response, ref);
            return;
        }

        try {
            switch (ref.type) {
                case "host" -> ((Container) ref.parent).removeChild((Host) ref.component);
                case "context" -> ((Container) ref.parent).removeChild((Context) ref.component);
                case "wrapper" -> ((Container) ref.parent).removeChild((Wrapper) ref.component);
                case "valve" -> ((Container) ref.parent).getPipeline().removeValve((Valve) ref.component);
                case "connector" -> ((StandardService) ref.parent).removeConnector((Connector) ref.component);
                case "executor" -> ((StandardService) ref.parent).removeExecutor((Executor) ref.component);
                case "alias" -> ((Host) ref.component).removeAlias(ref.aliasValue);
                case "listener" ->
                        ((LifecycleBase) ref.parent).removeLifecycleListener((LifecycleListener) ref.component);
                case "upgradeProtocol" -> {
                    AbstractHttp11Protocol http11 = http11ProtocolHandler((Connector) ref.parent);
                    if (http11 == null) {
                        throw notFound();
                    }
                    http11.removeUpgradeProtocol((UpgradeProtocol) ref.component);
                }
                case "realm" -> removeRealm((Realm) ref.component, ref.parent);
                case "engine" -> ((StandardService) ref.parent).setContainer(null);
                case "service" -> server.removeService((Service) ref.component);
                case "resource" -> removeNamingEntry((NamingResourcesImpl) ref.parent, "resource",
                        ((ResourceBase) ref.component).getName());
                case "resourceLink" -> removeNamingEntry((NamingResourcesImpl) ref.parent, "resourceLink",
                        ((ResourceBase) ref.component).getName());
                case "resourceEnvRef" -> removeNamingEntry((NamingResourcesImpl) ref.parent, "resourceEnvRef",
                        ((ResourceBase) ref.component).getName());
                case "environment" -> removeNamingEntry((NamingResourcesImpl) ref.parent, "environment",
                        ((ResourceBase) ref.component).getName());
                case "ejb" -> removeNamingEntry((NamingResourcesImpl) ref.parent, "ejb",
                        ((ResourceBase) ref.component).getName());
                case "localEjb" -> removeNamingEntry((NamingResourcesImpl) ref.parent, "localEjb",
                        ((ResourceBase) ref.component).getName());
                case "serviceRef" -> removeNamingEntry((NamingResourcesImpl) ref.parent, "serviceRef",
                        ((ResourceBase) ref.component).getName());
                case "cluster" -> ((Container) ref.parent).setCluster(null);
                case "channel" -> ((CatalinaCluster) ref.parent).setChannel(null);
                case "membership" -> ((ManagedChannel) ref.parent).setMembershipService(null);
                case "sender" -> ((ManagedChannel) ref.parent).setChannelSender(null);
                case "receiver" -> ((ManagedChannel) ref.parent).setChannelReceiver(null);
                case "deployer" -> ((CatalinaCluster) ref.parent).setClusterDeployer(null);
                case "clusterManager" -> ((SimpleTcpCluster) ref.parent).setManagerTemplate(null);
                case "transport" -> ((ReplicationTransmitter) ref.parent).setTransport(null);
                case "clusterListener" ->
                        ((CatalinaCluster) ref.parent).removeClusterListener((ClusterListener) ref.component);
                case "member" -> removeClusterMember(ref);
                default -> throw notFound();
            }
        } catch (ConfigException e) {
            throw e;
        } catch (Exception e) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "REMOVE_FAILED",
                    Strings.sm().getString("manager2.configRemoveFailed",
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }

        log(Strings.sm().getString("manager2.configAuditRemove", ref.type, label));
        Api.ok(response, Strings.sm().getString("manager2.configRemoved", label));
    }


    /**
     * Remove a static member (the local member or a static member) from a static membership service or interceptor.
     */
    protected void removeClusterMember(NodeRef ref) {
        Member member = (Member) ref.component;
        if (ref.parent instanceof StaticMembershipService sms) {
            if (sms.getLocalMember(false) == member) {
                sms.setLocalMember(null);
            } else {
                sms.removeStaticMember((StaticMember) member);
            }
        } else if (ref.parent instanceof StaticMembershipInterceptor smi) {
            if (smi.getLocalMember(false) == member) {
                smi.setLocalMember(null);
            } else {
                smi.removeStaticMember(member);
            }
        }
    }


    /**
     * Remove a realm. A directly attached realm is detached from its container (the container then falls back to the
     * parent realm) and a sub realm is removed from its combined realm and stopped.
     */
    protected static void removeRealm(Realm realm, Object parent) throws Exception {
        if (parent instanceof CombinedRealm combined) {
            if (!combined.removeRealm(realm)) {
                throw notFound();
            }
            if (realm instanceof Lifecycle lifecycle && lifecycle.getState().isAvailable()) {
                lifecycle.stop();
            }
        } else if (parent instanceof Container container) {
            // Detach: the container falls back to the parent realm.
            container.setRealm(null);
        } else {
            throw notFound();
        }
    }


    /**
     * Remove an SSL host configuration from its connector. When this disables TLS on the connector (no configurations
     * left) and the connector is running, it is restarted so that it serves plain HTTP again, with a rollback that
     * restores the configuration.
     */
    @SuppressWarnings("rawtypes")
    protected void removeSslHostConfig(HttpServletResponse response, NodeRef ref, String label) throws Exception {

        if (!(ref.component instanceof SSLHostConfig sslHostConfig) || !(ref.parent instanceof Connector connector)) {
            throw notFound();
        }
        if (isSelfConnector(connector)) {
            throw self();
        }
        AbstractHttp11Protocol http11 = sslProtocolHandler(connector);
        boolean sslEnabled = isSslEnabled(connector);
        boolean running = connector.getState().isAvailable();
        int remaining = (connector.findSslHostConfigs() == null) ? 0 : connector.findSslHostConfigs().length - 1;
        if (sslEnabled && running && remaining > 0 &&
                sslHostConfig.getHostName().equalsIgnoreCase(endpointOf(connector).getDefaultSSLHostConfigName())) {
            // The default host configuration is the fallback for
            // handshakes without a matching SNI name; it cannot be
            // removed from a running, TLS enabled connector while other
            // configurations remain.
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "SSL_DEFAULT",
                    Strings.sm().getString("manager2.configSslDefault"));
        }

        boolean disablesSsl = sslEnabled && remaining == 0;
        if (disablesSsl) {
            // TLS is disabled for this connector from now on. The flag
            // is switched off before the removal because the endpoint
            // refuses to remove its default host configuration while it
            // is still serving TLS.
            http11.setSSLEnabled(false);
        }
        try {
            endpointOf(connector).removeSslHostConfig(sslHostConfig.getHostName());
        } catch (IllegalArgumentException e) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "REMOVE_FAILED",
                    Strings.sm().getString("manager2.configRemoveFailed", rootMessage(e)));
        }
        // When the removal disabled TLS no further action is needed: the
        // endpoint decides per accepted connection whether it is TLS, so
        // new connections are served over plain HTTP from this point on
        // (in-flight TLS connections complete normally).

        log(Strings.sm().getString("manager2.configAuditRemove", ref.type, label));
        Api.ok(response, Strings.sm().getString("manager2.configRemoved", label));
    }


    /**
     * Remove a certificate configuration from its SSL host configuration. On a running, TLS enabled connector the
     * change is applied at once (the SSL context of the virtual host is re-created) and rolled back when the remaining
     * configuration does not validate. The last certificate of such a connector cannot be removed: the connector would
     * no longer be able to start.
     */
    protected void removeCertificate(HttpServletResponse response, NodeRef ref) throws Exception {

        if (!(ref.component instanceof SSLHostConfigCertificate certificate) ||
                !(ref.parent instanceof SSLHostConfig sslHostConfig)) {
            throw notFound();
        }
        Connector connector = connectorOfSsl(sslHostConfig);
        boolean live = connector != null && connector.getState().isAvailable() && isSslEnabled(connector);
        if (live && sslHostConfig.getCertificates().size() <= 1) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "SSL_LAST_CERTIFICATE",
                    Strings.sm().getString("manager2.configSslLastCertificate", connectorLabel(connector)));
        }

        Set<SSLHostConfigCertificate> certificates = sslHostConfig.getCertificates();
        certificates.remove(certificate);
        if (live) {
            try {
                endpointOf(connector).reloadSslHostConfig(sslHostConfig.getHostName());
            } catch (Exception e) {
                certificates.add(certificate);
                throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "REMOVE_FAILED",
                        Strings.sm().getString("manager2.configRemoveFailed", rootMessage(e)));
            }
        }

        String label = displayName(certificate, "certificate");
        log(Strings.sm().getString("manager2.configAuditRemove", "certificate", label));
        Api.ok(response, Strings.sm().getString("manager2.configRemoved", label));
    }


    /**
     * Remove a pre-shared key configuration from its SSL host configuration. Unlike a certificate a pre-shared key is
     * never required (a key-less host configuration is a valid, if unusual, TLS configuration), so removing the last
     * one is allowed. On a running, TLS enabled connector the change is applied at once (the SSL context of the virtual
     * host is re-created) and rolled back when the remaining configuration does not validate.
     */
    protected void removePreSharedKey(HttpServletResponse response, NodeRef ref) throws Exception {

        if (!(ref.component instanceof SSLHostConfigPreSharedKey preSharedKey) ||
                !(ref.parent instanceof SSLHostConfig sslHostConfig)) {
            throw notFound();
        }
        Connector connector = connectorOfSsl(sslHostConfig);
        boolean live = connector != null && connector.getState().isAvailable() && isSslEnabled(connector);

        Set<SSLHostConfigPreSharedKey> preSharedKeys = sslHostConfig.getPreSharedKeys();
        preSharedKeys.remove(preSharedKey);
        if (live) {
            try {
                endpointOf(connector).reloadSslHostConfig(sslHostConfig.getHostName());
            } catch (Exception e) {
                preSharedKeys.add(preSharedKey);
                throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "REMOVE_FAILED",
                        Strings.sm().getString("manager2.configRemoveFailed", rootMessage(e)));
            }
        }

        String label = displayName(preSharedKey, "preSharedKey");
        log(Strings.sm().getString("manager2.configAuditRemove", "preSharedKey", label));
        Api.ok(response, Strings.sm().getString("manager2.configRemoved", label));
    }


    protected boolean containsSelf(StandardService service) {
        Engine engine = service.getContainer();
        return engine != null && engine.findChild(selfHost.getName()) instanceof Host;
    }


    protected static int countHosts(Engine engine) {
        int count = 0;
        for (Container child : engine.findChildren()) {
            if (child instanceof Host) {
                count++;
            }
        }
        return count;
    }


    protected boolean isBasicValve(NodeRef ref) {
        // The first valve of a pipeline is the basic valve and cannot be
        // removed.
        if (ref.parent instanceof Container container) {
            Valve[] valves = container.getPipeline().getValves();
            return valves.length > 0 && valves[0] == ref.component;
        }
        return false;
    }


    protected static ConfigException self() {
        return new ConfigException(HttpServletResponse.SC_FORBIDDEN, "SELF_COMPONENT",
                Strings.sm().getString("manager2.configSelfComponent"));
    }


    protected static ConfigException duplicate(String name) {
        return new ConfigException(HttpServletResponse.SC_CONFLICT, "DUPLICATE",
                Strings.sm().getString("manager2.configDuplicate", name));
    }


    protected static ConfigException badParent(String type) {
        return new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "BAD_PARENT",
                Strings.sm().getString("manager2.configParentInvalid", type));
    }


    // -------------------------------------------------------- Lifecycle ops


    /**
     * Start, stop or restart one component of the server tree.
     * <p>
     * A {@code start} or {@code stop} of a component that would interrupt access to this web application itself (see
     * {@link #affectsSelf(NodeRef)}) is refused (403 {@code SELF_COMPONENT}): the request would lose its way back. A
     * {@code restart} (a stop, when the component is running, followed by a start) remains possible for those
     * components: the client's connection may be interrupted during the operation, but the component is running again
     * at the end and the client reconnects (re-logging in when the admin session was reset by the restart).
     */
    protected void lifecycle(HttpServletResponse response, Map<String, Object> body) throws Exception {

        String id = string(body.get("id"));
        String op = string(body.get("op"));
        if (id == null || op == null || op.isEmpty()) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_ID",
                    Strings.sm().getString("manager2.configInvalidId"));
        }
        boolean start = "start".equals(op);
        boolean stop = "stop".equals(op);
        boolean restart = "restart".equals(op);
        if (!start && !stop && !restart) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_OP",
                    Strings.sm().getString("manager2.configInvalidOp", op));
        }

        NodeRef ref = resolve(id);
        if (!(ref.component instanceof Lifecycle lifecycle)) {
            String label = ref.aliasValue != null ? ref.aliasValue : displayName(ref.component, ref.type);
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "NOT_A_LIFECYCLE",
                    Strings.sm().getString("manager2.configNotALifecycle", label));
        }
        if (!restart && affectsSelf(ref)) {
            throw self();
        }
        String label = ref.aliasValue != null ? ref.aliasValue : displayName(ref.component, ref.type);

        if (stop) {
            if (!lifecycle.getState().isAvailable()) {
                Api.ok(response, Strings.sm().getString("manager2.configAlreadyStopped", label));
                return;
            }
            stopChecked(lifecycle, label);
            log(Strings.sm().getString("manager2.configAuditLifecycle", "stopped", label));
            Api.ok(response, Strings.sm().getString("manager2.configStopped", label));
            return;
        }

        if (restart && lifecycle.getState().isAvailable()) {
            stopChecked(lifecycle, label);
        }
        if (start && lifecycle.getState().isAvailable()) {
            Api.ok(response, Strings.sm().getString("manager2.configAlreadyRunning", label));
            return;
        }
        startChecked(lifecycle, label);
        log(Strings.sm().getString("manager2.configAuditLifecycle", restart ? "restarted" : "started", label));
        Api.ok(response, Strings.sm().getString(restart ? "manager2.configRestarted" : "manager2.configStarted", label));
    }


    /**
     * Stop the component, raising a controlled error when it cannot be stopped or does not stop.
     */
    protected void stopChecked(Lifecycle lifecycle, String label) throws ConfigException {
        try {
            lifecycle.stop();
        } catch (Exception e) {
            log(Strings.sm().getString("manager2.error.config"), e);
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "STOP_FAILED",
                    Strings.sm().getString("manager2.configStopFailed", label, rootMessage(e)));
        }
        if (lifecycle.getState().isAvailable()) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "STOP_FAILED",
                    Strings.sm().getString("manager2.configStopFailed", label, Strings.sm().getString("manager2.configNotStopped")));
        }
    }


    /**
     * Start the component, raising a controlled error when it cannot be started or does not start.
     */
    protected void startChecked(Lifecycle lifecycle, String label) throws ConfigException {
        try {
            lifecycle.start();
        } catch (Exception e) {
            log(Strings.sm().getString("manager2.error.config"), e);
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "START_FAILED",
                    Strings.sm().getString("manager2.configStartFailed", label, rootMessage(e)));
        }
        if (!lifecycle.getState().isAvailable()) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "START_FAILED",
                    Strings.sm().getString("manager2.configStartFailed", label, Strings.sm().getString("manager2.configNotStarted")));
        }
    }


    /**
     * Whether stopping or starting this component would interrupt access to this web application: the component is the
     * server itself, or the service, engine, host, context, connector or wrapper that route or host the requests of
     * this web application. A {@code restart} of such a component remains possible (the client reconnects at the end of
     * the operation).
     */
    protected boolean affectsSelf(NodeRef ref) {
        Object component = ref.component;
        return switch (ref.type) {
            case "server" -> true;
            case "service" -> component instanceof StandardService service && containsSelf(service);
            case "engine" -> component instanceof Engine engine && engine.findChild(selfHost.getName()) instanceof Host;
            case "host" -> component == selfHost;
            case "context" -> component == selfContext;
            case "connector" -> component instanceof Connector connector && isSelfConnector(connector);
            case "wrapper" -> component instanceof Wrapper wrapper && wrapper.getParent() == selfContext;
            default -> false;
        };
    }


    // ------------------------------------------------------------- Store


    /**
     * Build a ready to use {@link StoreConfig} that writes the live state of this server. The storeconfig MBean is not
     * required (and is not registered unless the {@code StoreConfigLifecycleListener} is configured in server.xml), so
     * the instance is created on demand from the default classpath registry.
     */
    protected StoreConfig buildStoreConfig() throws Exception {
        StoreLoader loader = new StoreLoader();
        loader.load(null);
        StoreConfig storeConfig = new StoreConfig();
        storeConfig.setRegistry(loader.getRegistry());
        storeConfig.setServer(server);
        return storeConfig;
    }


    protected void store(HttpServletResponse response) throws Exception {

        File confDir = new File(storeBase(), "conf");
        Set<String> before = serverXmlBackups(confDir);

        StoreConfig storeConfig = buildStoreConfig();
        // Same sequence as StoreConfig.store(Server), but keeping each
        // context in its current storage location (see
        // storeServerPreservingContexts).
        StoreFileMover mover = new StoreFileMover(storeBase(), storeConfig.getServerFilename(),
                storeConfig.getRegistry().getEncoding());
        try (PrintWriter writer = mover.getWriter()) {
            storeServerPreservingContexts(storeConfig, writer);
        } catch (Exception e) {
            log(Strings.sm().getString("manager2.error.config"), e);
            throw new ConfigException(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "STORE_FAILED",
                    Strings.sm().getString("manager2.configStoreFailed"));
        }
        mover.move();

        Set<String> after = serverXmlBackups(confDir);
        after.removeAll(before);
        String backup = after.isEmpty() ? null : after.iterator().next();

        log(Strings.sm().getString("manager2.configAuditStore", backup));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", Boolean.TRUE);
        payload.put("message", Strings.sm().getString("manager2.configStored", "conf/server.xml", backup == null ? "-" : backup));
        payload.put("file", "conf/server.xml");
        payload.put("backup", backup);
        Api.json(response, payload);
    }


    /**
     * The Catalina base directory that the store writes to. Normally the base of the running server. The
     * {@code manager2.store.base} system property overrides it (used by the integration tests to redirect the write to
     * a throw-away directory).
     */
    protected static String storeBase() {
        String base = System.getProperty("manager2.store.base");
        return (base != null && !base.isEmpty()) ? base : Bootstrap.getCatalinaBase();
    }


    /**
     * The names of the storeconfig backup files ({@code server.xml.*}) in the conf directory.
     */
    protected static Set<String> serverXmlBackups(File confDir) {
        Set<String> result = new HashSet<>();
        File[] files = confDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().startsWith("server.xml.")) {
                    result.add(file.getName());
                }
            }
        }
        return result;
    }


    protected void preview(HttpServletResponse response) throws Exception {
        StoreConfig storeConfig = buildStoreConfig();
        StringWriter writer = new StringWriter();
        CapturingContextSF capturing = previewServerPreservingContexts(storeConfig, writer);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("xml", writer.toString());
        payload.put("files", new ArrayList<String>(capturing.getCaptured().keySet()));
        // The save restarts the manager (and resets the admin session) only
        // when the file of the context this manager runs in is rewritten.
        payload.put("restartsManager", selfContext != null && selfContext.getConfigFile() != null);
        Api.json(response, payload);
    }


    /**
     * Store the live server state to the given writer, keeping every context in its current storage location, as
     * regular StoreConfig does. The external context files are written to disk.
     */
    protected void storeServerPreservingContexts(StoreConfig storeConfig, PrintWriter writer) throws Exception {
        storeServer(storeConfig, writer, null);
    }


    /**
     * Read-only variant of {@link #storeServerPreservingContexts} used by the store preview: the external context files
     * are captured in memory (see {@link CapturingContextSF}) instead of being written to disk, so the preview never
     * modifies the configuration on disk.
     */
    protected CapturingContextSF previewServerPreservingContexts(StoreConfig storeConfig, StringWriter writer)
            throws Exception {
        CapturingContextSF capturing = new CapturingContextSF();
        storeServer(storeConfig, new PrintWriter(writer), capturing);
        return capturing;
    }


    /**
     * Store the live server state to the given writer, keeping each context in the location it currently lives in, as
     * regular StoreConfig does: a context backed by its own configuration file is written back to that file, a context
     * defined inline stays inline in {@code server.xml}.
     * <p>
     * The flags are set so that external contexts are written to their file ({@code storeSeparate=true},
     * {@code externalAllowed=true}) while inline contexts are not turned into new external files
     * ({@code externalOnly=false}). The previous flag values (and the store factory, when {@code capturing} is used)
     * are restored afterwards. When {@code capturing} is non-null (the read-only preview), the external files are
     * captured in memory by that factory instead of being written.
     */
    protected void storeServer(StoreConfig storeConfig, PrintWriter writer, CapturingContextSF capturing)
            throws Exception {
        StoreFileMover mover =
                new StoreFileMover(Bootstrap.getCatalinaBase(), storeConfig.getServerFilename(), storeConfig.getRegistry().getEncoding());

        StoreDescription desc = storeConfig.getRegistry().findDescription(StandardContext.class);
        boolean oldSeparate = desc.isStoreSeparate();
        boolean oldAllowed = desc.isExternalAllowed();
        boolean oldOnly = desc.isExternalOnly();
        IStoreFactory oldFactory = desc.getStoreFactory();
        try {
            desc.setStoreSeparate(true);
            desc.setExternalAllowed(true);
            desc.setExternalOnly(false);
            if (capturing != null) {
                capturing.setRegistry(storeConfig.getRegistry());
                if (oldFactory != null) {
                    capturing.setStoreAppender(oldFactory.getStoreAppender());
                }
                desc.setStoreFactory(capturing);
            }
            StringWriter buffer = new StringWriter();
            storeConfig.store(new PrintWriter(buffer), -2, server);
            writer.write(XMLFormatPreserver.preserve(mover.getConfigOld(), buffer.toString(),
                    storeConfig.getRegistry().getEncoding()));
        } finally {
            desc.setStoreSeparate(oldSeparate);
            desc.setExternalAllowed(oldAllowed);
            desc.setExternalOnly(oldOnly);
            desc.setStoreFactory(oldFactory);
        }
    }


    /**
     * A {@link StandardContextSF} that stores external context files into in-memory buffers instead of on disk, for the
     * read-only store preview. Writing the (watched) context files would restart the running contexts, so during a
     * preview they are only captured.
     */
    protected static final class CapturingContextSF extends StandardContextSF {

        protected final Map<String, String> captured = new LinkedHashMap<>();

        public Map<String, String> getCaptured() {
            return captured;
        }

        @Override
        public void store(PrintWriter aWriter, int indent, Object aContext) throws Exception {
            if (aContext instanceof StandardContext context && getRegistry() != null) {
                StoreDescription desc = getRegistry().findDescription(context.getClass());
                if (desc != null && desc.isStoreSeparate() && desc.isExternalAllowed()) {
                    URL configFile = context.getConfigFile();
                    if (configFile == null && !desc.isExternalOnly() && !context.getDeployedFromServerXml()) {
                        // The store creates a new configuration file for a context that was not deployed from
                        // server.xml. Compute the path that file would get, without setting it on the context.
                        Host host = (Host) context.getParent();
                        ContextName cn = new ContextName(context.getName(), false);
                        File config = new File(host.getConfigBaseFile(), cn.getBaseName() + ".xml");
                        configFile = config.toURI().toURL();
                    }
                    if (configFile != null) {
                        // Capture the (new) external context file in memory instead of writing it. The element is
                        // written inline (storeSeparate is temporarily off) so the separate-file branch of
                        // StandardContextSF.store is not re-entered.
                        StringWriter buffer = new StringWriter();
                        PrintWriter w = new PrintWriter(buffer);
                        storeXMLHead(w);
                        boolean savedSeparate = desc.isStoreSeparate();
                        desc.setStoreSeparate(false);
                        try {
                            super.store(w, -2, aContext);
                        } finally {
                            desc.setStoreSeparate(savedSeparate);
                        }
                        w.flush();
                        captured.put(displayPath(configFile), buffer.toString());
                        return;
                    }
                }
            }
            super.store(aWriter, indent, aContext);
        }
    }


    /**
     * A context configuration file path for display: relative to the Catalina base where possible, otherwise the
     * absolute path.
     */
    protected static String displayPath(URL configFile) {
        try {
            File file = new File(configFile.toURI());
            String base = Bootstrap.getCatalinaBase();
            if (base != null && !base.isEmpty()) {
                String rel = new File(base).toPath().relativize(file.toPath()).toString();
                if (!rel.startsWith("..")) {
                    return rel.replace(File.separatorChar, '/');
                }
            }
            return file.getPath().replace(File.separatorChar, '/');
        } catch (Exception e) {
            return String.valueOf(configFile);
        }
    }


}
