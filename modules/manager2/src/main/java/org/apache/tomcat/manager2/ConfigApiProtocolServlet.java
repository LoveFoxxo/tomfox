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

import java.io.IOException;
import java.io.Serial;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.catalina.ContainerServlet;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.Realm;
import org.apache.catalina.Server;
import org.apache.catalina.Service;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.SSLHostConfig;


/** HTTP protocol, shared server state and common helpers for the configuration API. */
abstract class ConfigApiProtocolServlet extends HttpServlet implements ContainerServlet {


    @Serial
    protected static final long serialVersionUID = 1L;


    /**
     * A component name that may be used for new hosts, wrappers, executors and services (and that is accepted for
     * engines).
     */
    protected static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    /**
     * The attribute types that can be edited through the API.
     */
    protected static final Set<String> EDITABLE_TYPES = Set.of("boolean", "int", "long", "short", "byte", "float",
            "double", "java.lang.String", "[Ljava.lang.String;");

    /**
     * Attribute names whose update requires a type to confirm (renames and the like).
     */
    protected static final Set<String> RISKY_ATTRIBUTES = Set.of("name", "path", "defaultHost");

    /**
     * The cluster sub component types. They are excluded from the generic "accepts a lifecycle listener" flag: only the
     * cluster itself (not its channel, manager, ...) accepts a {@code <Listener>} in the cluster model, even when the
     * sub component is itself a lifecycle.
     */
    protected static final Set<String> CLUSTER_SUB_TYPES = Set.of("channel", "membership", "sender", "receiver",
            "transport", "interceptor", "deployer", "clusterManager", "clusterValve", "clusterListener", "member");

    /**
     * The maximum number of nested Realm levels, the same bound the XML parser applies to Realm elements
     * (org.apache.catalina.startup.RealmRuleSet).
     */
    protected static final int MAX_NESTED_REALM_LEVELS = 3;


    protected transient Wrapper wrapper = null;

    protected transient Context selfContext = null;

    protected transient Host selfHost = null;

    protected transient Server server = null;


    // ------------------------------------------------ ContainerServlet API


    @Override
    public Wrapper getWrapper() {
        return wrapper;
    }


    @Override
    public void setWrapper(Wrapper wrapper) {
        this.wrapper = wrapper;
        if (wrapper == null) {
            selfContext = null;
            selfHost = null;
            server = null;
        } else {
            selfContext = (Context) wrapper.getParent();
            selfHost = (Host) selfContext.getParent();
            Engine engine = (Engine) selfHost.getParent();
            Service service = engine.getService();
            server = (service != null) ? service.getServer() : null;
        }
    }


    // ------------------------------------------------------------ Request API


    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        String path = path(request);

        try {
            requireServer();
            if ("/api/config/tree".equals(path)) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("tree", build(server, "server", "server"));
                Api.json(response, payload);
            } else if (path.startsWith("/api/config/node/")) {
                NodeRef ref = resolve(path.substring("/api/config/node/".length()));
                Api.json(response, node(ref));
            } else if ("/api/config/store/preview".equals(path)) {
                preview(response);
            } else {
                Api.notFound(response);
            }
        } catch (ConfigException e) {
            Api.error(response, e.status, e.code, e.getMessage());
        } catch (Exception e) {
            log(Strings.sm().getString("manager2.error.config"), e);
            throw new ServletException(e);
        }
    }


    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        String path = path(request);
        Map<String, Object> body = readJson(request);

        try {
            requireServer();
            if ("/api/config/attribute".equals(path)) {
                updateAttribute(response, body);
            } else if ("/api/config/child".equals(path)) {
                addChild(response, body);
            } else if ("/api/config/store".equals(path)) {
                store(response);
            } else if ("/api/config/lifecycle".equals(path)) {
                lifecycle(response, body);
            } else {
                Api.notFound(response);
            }
        } catch (ConfigException e) {
            Api.error(response, e.status, e.code, e.getMessage());
        } catch (IllegalArgumentException e) {
            Api.error(response, HttpServletResponse.SC_BAD_REQUEST, "INVALID_JSON", e.getMessage());
        } catch (Exception e) {
            log(Strings.sm().getString("manager2.error.config"), e);
            throw new ServletException(e);
        }
    }


    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        String path = path(request);

        try {
            requireServer();
            if ("/api/config/child".equals(path)) {
                Map<String, Object> body = readJson(request);
                removeChild(response, body);
            } else {
                Api.notFound(response);
            }
        } catch (ConfigException e) {
            Api.error(response, e.status, e.code, e.getMessage());
        } catch (IllegalArgumentException e) {
            Api.error(response, HttpServletResponse.SC_BAD_REQUEST, "INVALID_JSON", e.getMessage());
        } catch (Exception e) {
            log(Strings.sm().getString("manager2.error.config"), e);
            throw new ServletException(e);
        }
    }



    // ---------------------------------------------------- Collaboration API

    protected abstract Map<String,Object> build(Object component, String id, String type);

    protected abstract Map<String,Object> node(NodeRef ref) throws ConfigException;

    protected abstract NodeRef resolve(String id) throws ConfigException;

    protected abstract boolean affectsSelf(NodeRef ref);

    protected abstract boolean isSslEnabled(Connector connector);

    protected abstract Connector connectorOfSsl(SSLHostConfig sslHostConfig);

    protected abstract AbstractEndpoint<?,?> endpointOf(Connector connector) throws ConfigException;

    protected abstract void updateAttribute(HttpServletResponse response, Map<String,Object> body) throws Exception;

    protected abstract void addChild(HttpServletResponse response, Map<String,Object> body) throws Exception;

    protected abstract void removeChild(HttpServletResponse response, Map<String,Object> body) throws Exception;

    protected abstract void lifecycle(HttpServletResponse response, Map<String,Object> body) throws Exception;

    protected abstract void store(HttpServletResponse response) throws Exception;

    protected abstract void preview(HttpServletResponse response) throws Exception;

    // ------------------------------------------------------------ Helpers

    protected static Class<?> classForType(String type) {
        switch (type) {
            case "boolean":
                return boolean.class;
            case "int":
                return int.class;
            case "long":
                return long.class;
            case "short":
                return short.class;
            case "byte":
                return byte.class;
            case "float":
                return float.class;
            case "double":
                return double.class;
            case "java.lang.String":
                return String.class;
            case "[Ljava.lang.String;":
                return String[].class;
            default:
                try {
                    return Class.forName(type);
                } catch (ClassNotFoundException e) {
                    return Object.class;
                }
        }
    }


    protected void requireServer() throws ConfigException {
        if (server == null) {
            throw new ConfigException(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "SERVER_UNAVAILABLE",
                    Strings.sm().getString("manager2.configServerUnavailable"));
        }
    }


    protected static String path(HttpServletRequest request) {
        String path = request.getServletPath();
        String info = request.getPathInfo();
        if (info != null && !info.isEmpty()) {
            path = path + info;
        }
        if (path == null || path.isEmpty()) {
            path = "/api/config";
        }
        return path;
    }


    protected static Map<String, Object> readJson(HttpServletRequest request) throws IOException {
        return JsonInput.readObject(request);
    }


    protected static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }


    protected static boolean bool(Object value, boolean defaultValue) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return defaultValue;
    }


    protected static Integer intValue(Object value, int defaultValue) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isEmpty()) {
            try {
                return Integer.parseInt(s.strip());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }


    protected static List<String> stringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String s = String.valueOf(item).strip();
                if (!s.isEmpty()) {
                    result.add(s);
                }
            }
        } else if (value instanceof String s && !s.isEmpty()) {
            for (String part : s.split(",")) {
                String trimmed = part.strip();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }


    protected static String requiredName(Object value) throws ConfigException {
        String name = string(value);
        if (name == null || name.isEmpty() || !SAFE_NAME.matcher(name).matches()) {
            throw new ConfigException(HttpServletResponse.SC_BAD_REQUEST, "INVALID_NAME",
                    Strings.sm().getString("manager2.configInvalidName", name));
        }
        return name;
    }


    protected static String capitalize(String name) {
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }


    /**
     * Encode a value for use as a node id segment. The value is URL encoded and the {@code %2F} sequences (originating
     * from {@code /} characters, e.g. in context paths) are replaced with a bare {@code +}, since Tomcat rejects
     * {@code %2F} in the request URI. A literal {@code +} in the value is percent encoded, so the replacement is
     * unambiguous.
     */
    protected static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("%2F", "+");
    }


    /**
     * Encode a context path for use as a node id segment. The root context (whose path is the empty string) is encoded
     * as a bare {@code +}, the same segment that a path of {@code /} would produce, since {@link #dec(String)} turns a
     * bare {@code +} back into a context path.
     */
    protected static String encContextPath(String path) {
        if (path.isEmpty()) {
            return "+";
        }
        return enc(path);
    }


    /**
     * The inverse of {@link #enc(String)}.
     */
    protected static String dec(String value) {
        return URLDecoder.decode(value.replace("+", "%2F"), StandardCharsets.UTF_8);
    }



    /**
     * A resolved tree node: the component, its parent (if it has one), the node type and, for alias nodes, the alias
     * value.
     */
    protected static final class NodeRef {

        protected final Object component;
        protected final Object parent;
        protected final String type;
        protected final String aliasValue;
        protected final String id;


        NodeRef(Object component, Object parent, String type, String aliasValue, String id) {
            this.component = component;
            this.parent = parent;
            this.type = type;
            this.aliasValue = aliasValue;
            this.id = id;
        }
    }



    /**
     * A controlled failure of one API operation, carrying the HTTP status, the machine readable code and the (already
     * localized) message.
     */
    protected static final class ConfigException extends Exception {

        @Serial
        protected static final long serialVersionUID = 1L;

        protected final int status;

        protected final String code;


        ConfigException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }
}
