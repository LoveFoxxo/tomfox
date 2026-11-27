# tomfox · Apache Tomcat 加固与 JSON 化魔改分支

> 基于官方 Apache Tomcat 源码的定制分支（本地代号 tomfox）。自研方向：**管理端/日志/错误响应全面 JSON 化**、HTTP/2 HPack 原生 Huffman 实现、PEM 密钥加载收敛、连接池与用户域加固，并引入原子文件支持与本地 native 辅助层。

## 一、基线与状态

- 上游远端：`https://github.com/apache/tomcat.git`（`origin`）。
- 本地为未提交工作区改动：`git status` 含 51 个已修改跟踪文件 + 一批新增自研 Java 类与 native 目录。
- 改动规模：`git diff --stat` 约 51 个跟踪文件，`+3996 / -7482`（大量为 manager2 配置 Servlet 的重写精简）。

## 二、自研 JSON 体系

### 1. 底层 JSON（`java/org/apache/tomcat/util/json/`）
- `JSONParser.java`（重写约 843 行）、`JSONParser.jjt`（JavaCC 文法约 468 行）、`JSONFilter.java`、`ParseException.java`：解析器/过滤器/异常体系收敛，新增边界与容错处理。

### 2. 日志 JSON 化（`java/org/apache/juli/`）
- `JsonFormatter.java`（重写约 231 行）：日志 JSON 格式化器。
- 新增 `JsonSupport.java`：JSON 编解码公共支撑。
- 新增 `LogHub.java`：日志汇聚/分发中枢（配套 `test/.../TestLogHub.java`）。
- `ClassLoaderLogManager.java`、`FileHandler.java`、`logging/DirectJDKLog.java`：日志管理器、文件句柄与直连日志接入新体系。
- 配置 `conf/logging.properties` 同步调整。

### 3. 错误响应与健康检查（`java/org/apache/catalina/valves/`）
- `JsonErrorReportValve.java`（重写约 130 行）：错误页改为 JSON 报告。
- `HealthCheckValve.java`：健康检查阀门调整。

### 4. manager2 管理端 JSON API（`modules/manager2/`）
- 重写 `ConfigApiServlet.java`（约 8548 行精简重写），以及 `Api.java`、`AppsApiServlet.java`、`HostsApiServlet.java`、`I18nServlet.java`、`Json.java`、`AccessLogSupport.java`、`LogConfigApiServlet.java`、`StatusApiServlet.java`、`StatusSnapshot.java`、`UsersApiServlet.java`。
- 新增自研 Servlet / 过滤器：
  - `JsonInput.java`：请求 JSON 绑定与校验；
  - `JsonRequestFilter.java`：JSON 请求前置过滤；
  - `ReadOnlyManagementFilter.java`：只读管理保护过滤器；
  - `ConfigApiNavigationServlet.java`、`ConfigApiProtocolServlet.java`：配置导航与协议接口。
- 测试：`TestManager2JsonRequestBoundaries.java`（JSON 请求边界）、`TestManager2ReadOnlyManagement.java`（只读管理隔离）。
- `web.xml`、`LocalStrings.properties` 随新接口更新。

## 三、HTTP/2 HPack（`java/org/apache/coyote/http2/`）

- 新增 `NativeHPackHuffman.java`：原生 Huffman 编码/解码实现。
- `HpackDecoder.java`、`HpackEncoder.java`、`HPackHuffman.java`、`Http2UpgradeHandler.java`：HPack 解码/编码与升级处理器接入新实现；`LocalStrings.properties` 补充提示。
- 测试 `TestHPackHuffman.java` 扩充 Huffman 用例。

## 四、JSSE / PEM 密钥（`java/org/apache/tomcat/util/net/jsse/`）

- `PEMFile.java`（重写约 219 行）：PEM 文件解析收敛。
- 新增 `PemKeySupport.java`：PEM 密钥加载公共支撑；配套 `TestPemKeySupport.java`。
- `AbstractEndpoint.java` 与 JSSE 本地化字符串同步调整。

## 五、连接池与用户域加固

- DBCP2 / pool2：`BasicDataSourceFactory.java`、`CPDSConnectionFactory.java`、`KeyedCPDSConnectionFactory.java`、`DataSourceXAConnectionFactory.java`、`pool2/impl/BaseGenericObjectPool.java`：连接获取/失效与对象池边界收敛。
- 用户域：`AbstractGroup.java`、`AbstractRole.java`、`AbstractUser.java`、`MemoryUserDatabase.java`：组/角色/用户与内存库校验加固。

## 六、通用与本地辅助

- 新增 `java/org/apache/tomcat/util/file/AtomicFileSupport.java`：原子文件写入/替换支撑。
- `native/`：本地代码辅助层；`output/`：构建/运行输出目录。
- 构建：`build.xml`、`build.properties.default`；资源清单 `res/META-INF/tomcat-juli.jar.license`、`.notice`。

## 七、校验与测试

- 测试覆盖：JSON 边界、只读管理、HPack Huffman、PEM 密钥、LogHub。
- 遗留 `hs_err_pid281616.log` 为一次 JVM 崩溃转存记录，不纳入交付源码。

## 八、安全说明

- manager2 新增只读管理过滤器，写接口需经 JSON 请求过滤与边界校验。
- 本分支为实验性源码魔改，生产部署前需复核 manager2 鉴权与连接池行为。
