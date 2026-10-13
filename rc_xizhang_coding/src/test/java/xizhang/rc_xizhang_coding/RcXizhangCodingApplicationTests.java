package xizhang.rc_xizhang_coding;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import xizhang.rc_xizhang_coding.dto.RequestData;
import xizhang.rc_xizhang_coding.entity.NotificationTask;
import xizhang.rc_xizhang_coding.enums.DeliveryStatus;
import xizhang.rc_xizhang_coding.job.CompensationJob;
import xizhang.rc_xizhang_coding.lock.MysqlTaskLock;
import xizhang.rc_xizhang_coding.mapper.TaskMapper;
import xizhang.rc_xizhang_coding.mapper.AdvertisingMapper;
import xizhang.rc_xizhang_coding.mapper.CrmMapper;
import xizhang.rc_xizhang_coding.mapper.InventoryMapper;

import static org.assertj.core.api.Assertions.*;

/** Uses a dedicated disposable MySQL database; never point MYSQL_TEST_URL at real data. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class RcXizhangCodingApplicationTests {
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    static final JsonMapper JSON = new JsonMapper();
    static final AtomicInteger REQUESTS = new AtomicInteger();
    static final AtomicReference<String> MODE = new AtomicReference<>("success");
    static final AtomicReference<String> BODY = new AtomicReference<>();
    static final AtomicReference<String> CONTENT_TYPE = new AtomicReference<>();
    static final AtomicInteger CALLBACK_STATUS = new AtomicInteger();
    static final List<Long> TIMES = new CopyOnWriteArrayList<>();
    static final List<String> TASK_IDS = new CopyOnWriteArrayList<>();
    static final List<String> REQUEST_IDS = new CopyOnWriteArrayList<>();
    static volatile int appPort;
    static final ExecutorService SUPPLIER_THREADS = Executors.newCachedThreadPool();
    static final HttpServer SUPPLIER = startSupplier();

    @Autowired AdvertisingMapper advertisingMapper;
    @Autowired CrmMapper crmMapper;
    @Autowired InventoryMapper inventoryMapper;
    @Autowired List<TaskMapper> mappers;
    TaskMapper mapper;
    static String business;
    static final AtomicReference<String> METHOD = new AtomicReference<>();
    static final AtomicReference<String> TARGET = new AtomicReference<>();

    private void selectBusiness(String name) {
        business = name;
        mapper = switch (name) {
            case "advertising" -> advertisingMapper;
            case "crm" -> crmMapper;
            case "inventory" -> inventoryMapper;
            default -> throw new IllegalArgumentException(name);
        };
    }

    private String table() { return business + "_notification_task"; }
    private static String tasksPath() { return "/api/" + business + "/tasks"; }
    private static String callbackPath() { return "/api/" + business + "/callback"; }
    @Autowired MysqlTaskLock locks;
    @Autowired CompensationJob job;
    @Autowired JdbcTemplate jdbc;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("MYSQL_TEST_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("MYSQL_TEST_USER", "notification"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", "notification-local"));
        registry.add("notification.api-key", () -> "internal-test");
        registry.add("notification.suppliers.demo.origin", () -> "http://localhost:" + SUPPLIER.getAddress().getPort());
        registry.add("notification.suppliers.demo.callback-key", () -> "callback-test");
        registry.add("notification.scheduler-enabled", () -> false);
        registry.add("notification.http-timeout", () -> "1s");
        registry.add("notification.batch-size", () -> 2);
    }

    @BeforeEach void reset() {
        appPort = port;
        for (String name : List.of("advertising", "crm", "inventory")) {
            jdbc.update("DELETE FROM " + name + "_notification_task");
        }
        selectBusiness("advertising");
        METHOD.set(null);
        TARGET.set(null);
        REQUESTS.set(0);
        CALLBACK_STATUS.set(0);
        BODY.set(null);
        CONTENT_TYPE.set(null);
        TIMES.clear(); TASK_IDS.clear(); REQUEST_IDS.clear();
        MODE.set("success");
    }

    @AfterAll static void closeSupplier() { SUPPLIER.stop(0); SUPPLIER_THREADS.shutdownNow(); }

    @ParameterizedTest
    @ValueSource(strings = {"advertising", "crm", "inventory"})
    void persistsThenDeliversAndCallbacksAreAuthenticatedAndMonotonic(String scenario) throws Exception {
        selectBusiness(scenario);
        var response = submit("normal", "{\"orderId\":1}", "application/json");
        assertThat(response.statusCode()).isEqualTo(202);
        String taskId = taskId(response);
        await(() -> mapper.findByTaskId(taskId).isTransferSuccess());
        var task = mapper.findByTaskId(taskId);
        assertThat(task.getId()).isPositive();
        assertThat(task.isProcessed()).isFalse();
        assertThat(jdbc.queryForObject("SELECT JSON_VALID(request_data) FROM " + table() + " WHERE task_id=?",
                Integer.class, taskId)).isEqualTo(1);
        assertThat(callback(taskId, "normal", true, "wrong").statusCode()).isEqualTo(401);
        assertThat(callback(taskId, "wrong-request", true, "callback-test").statusCode()).isEqualTo(409);
        assertThat(callback(taskId, "normal", true, "callback-test").statusCode()).isEqualTo(204);
        assertThat(callback(taskId, "normal", true, "callback-test").statusCode()).isEqualTo(204);
        assertThat(callback(taskId, "normal", false, "callback-test").statusCode()).isEqualTo(204);
        assertThat(mapper.findByTaskId(taskId).isProcessed()).isTrue();
        job.runOnce();
        assertThat(REQUESTS.get()).isEqualTo(1);
        assertThat(call("GET", tasksPath() + "/" + taskId, null, "X-Api-Key", "internal-test").statusCode()).isEqualTo(200);
        assertThat(call("GET", tasksPath() + "/" + taskId, null, "X-Api-Key", "bad").statusCode()).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"advertising", "crm", "inventory"})
    void concurrentIdenticalRequestsCreateOneTaskAndConflictDoesNotOverwrite(String scenario) throws Exception {
        selectBusiness(scenario);
        ExecutorService callers = Executors.newFixedThreadPool(8);
        try {
            List<Future<HttpResponse<String>>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(callers.submit(() -> submit("same", "body", "text/plain")));
            Set<String> ids = new HashSet<>();
            int created = 0;
            for (var result : results) {
                var response = result.get(5, TimeUnit.SECONDS);
                assertThat(response.statusCode()).isIn(200, 202);
                if (response.statusCode() == 202) created++;
                ids.add(taskId(response));
            }
            assertThat(created).isEqualTo(1);
            assertThat(ids).hasSize(1);
            String taskId = ids.iterator().next();
            await(() -> mapper.findByTaskId(taskId).isTransferSuccess());
            assertThat(submit("same", "different", "text/plain").statusCode()).isEqualTo(409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table(), Integer.class)).isEqualTo(1);
            assertThat(REQUESTS.get()).isEqualTo(1);
        } finally { callers.shutdownNow(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"advertising", "crm", "inventory"})
    void retryUsesSameKeysAndThreeBackoffs(String scenario) throws Exception {
        selectBusiness(scenario);
        MODE.set("retry-then-success");
        String taskId = taskId(submit("retry", "body", "text/plain"));
        await(() -> mapper.findByTaskId(taskId).isTransferSuccess());
        assertThat(REQUESTS.get()).isEqualTo(4);
        assertThat(new HashSet<>(TASK_IDS)).containsExactly(taskId);
        assertThat(new HashSet<>(REQUEST_IDS)).containsExactly("retry");
        assertThat(TIMES.get(1) - TIMES.get(0)).isGreaterThanOrEqualTo(90_000_000L);
        assertThat(TIMES.get(2) - TIMES.get(1)).isGreaterThanOrEqualTo(190_000_000L);
        assertThat(TIMES.get(3) - TIMES.get(2)).isGreaterThanOrEqualTo(390_000_000L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"advertising", "crm", "inventory"})
    void exhaustedRoundRemainsPendingAndJobStartsNewRound(String scenario) throws Exception {
        selectBusiness(scenario);
        MODE.set("retry");
        String taskId = taskId(submit("exhausted", "body", "text/plain"));
        await(() -> REQUESTS.get() == 4);
        awaitUnlocked(taskId);
        assertThat(mapper.findByTaskId(taskId).pending()).isTrue();
        MODE.set("success");
        job.runOnce();
        assertThat(mapper.findByTaskId(taskId).isTransferSuccess()).isTrue();
        assertThat(REQUESTS.get()).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"advertising", "crm", "inventory"})
    void duplicateDoesNotMeanTransferSuccessAndJobDoesNotResend(String scenario) throws Exception {
        selectBusiness(scenario);
        MODE.set("duplicate");
        String taskId = taskId(submit("duplicate", "body", "text/plain"));
        await(() -> mapper.findByTaskId(taskId).getDeliveryStatus() == DeliveryStatus.DUPLICATE);
        job.runOnce();
        assertThat(REQUESTS.get()).isEqualTo(1);
        assertThat(mapper.findByTaskId(taskId).isTransferSuccess()).isFalse();
        callback(taskId, "duplicate", true, "callback-test");
        assertThat(mapper.findByTaskId(taskId).isProcessed()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"advertising", "crm", "inventory"})
    void callbackCanArriveWhileOutgoingRequestHoldsLock(String scenario) throws Exception {
        selectBusiness(scenario);
        MODE.set("early-callback");
        String taskId = taskId(submit("early", "body", "text/plain"));
        await(() -> mapper.findByTaskId(taskId).isTransferSuccess());
        assertThat(CALLBACK_STATUS.get()).isEqualTo(204);
        assertThat(mapper.findByTaskId(taskId).isProcessed()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"advertising", "crm", "inventory"})
    void nonRetryableAndMalformedResponsesStopAutomaticDelivery(String scenario) throws Exception {
        selectBusiness(scenario);
        for (String mode : List.of("failed", "malformed", "redirect")) {
            MODE.set(mode);
            String taskId = taskId(submit(mode, "body", "text/plain"));
            await(() -> mapper.findByTaskId(taskId).getDeliveryStatus() == DeliveryStatus.FAILED);
            awaitUnlocked(taskId);
        }
        job.runOnce();
        assertThat(REQUESTS.get()).isEqualTo(3); // redirect was not followed
    }

    @ParameterizedTest
    @ValueSource(strings = {"advertising", "crm", "inventory"})
    void preservesXmlBodyAndRejectsUnapprovedTargetBeforeInsertion(String scenario) throws Exception {
        selectBusiness(scenario);
        String taskId = taskId(submit("xml", "<item> 1 </item>", "application/xml"));
        await(() -> mapper.findByTaskId(taskId).isTransferSuccess());
        assertThat(METHOD.get()).isEqualTo("POST");
        assertThat(TARGET.get()).isEqualTo("/" + business + "/notify");
        assertThat(BODY.get()).isEqualTo("<item> 1 </item>");
        assertThat(CONTENT_TYPE.get()).isEqualTo("application/xml");
        String bad = JSON.writeValueAsString(Map.of("requestId", "bad", "url", "http://unapproved.example.com/path",
                "method", "POST", "body", ""));
        assertThat(call("POST", tasksPath(), bad, "X-Api-Key", "internal-test").statusCode()).isEqualTo(400);
        assertThat(mapper.findByRequestId("bad")).isNull();
    }

    @Test void mysqlLockIsExclusiveAndCanBeReacquiredAfterRelease() throws Exception {
        String key = UUID.randomUUID().toString();
        try (var first = locks.tryAcquire(key)) {
            assertThat(first).isNotNull();
            first.checkOwned();
            assertThat(locks.tryAcquire(key)).isNull();
        }
        try (var second = locks.tryAcquire(key)) {
            assertThat(second).isNotNull();
            second.checkOwned();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"advertising", "crm", "inventory"})
    void jobRecoversPersistedTasksAndUsesIdPagination(String scenario) {
        selectBusiness(scenario);
        for (int i = 0; i < 5; i++) {
            NotificationTask task = new NotificationTask();
            task.setTaskId(UUID.randomUUID().toString());
            task.setRequestId("recovery-" + i);
            task.setRequestData(JSON.writeValueAsString(new RequestData(
                    "http://localhost:" + SUPPLIER.getAddress().getPort() + "/" + business + "/notify", "POST", Map.of(), "body")));
            mapper.insert(task); // Simulates a committed insert followed by process exit before dispatch.
        }
        job.runOnce();
        assertThat(REQUESTS.get()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table() + " WHERE transfer_success=TRUE",
                Integer.class)).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"advertising", "crm", "inventory"})
    void supportsOutgoingGetAndQueryDoesNotSendAgain(String scenario) throws Exception {
        selectBusiness(scenario);
        String target = "/" + business + "/notify?event=registered&userId=u%201";
        String request = JSON.writeValueAsString(Map.of("requestId", "get-request",
                "url", "http://localhost:" + SUPPLIER.getAddress().getPort() + target,
                "method", "GET", "headers", Map.of("X-Supplier-Token", "demo"), "body", ""));
        var response = call("POST", tasksPath(), request, "X-Api-Key", "internal-test");
        assertThat(response.statusCode()).isEqualTo(202);
        String id = taskId(response);
        await(() -> mapper.findByTaskId(id).isTransferSuccess());
        assertThat(METHOD.get()).isEqualTo("GET");
        assertThat(TARGET.get()).isEqualTo(target);
        assertThat(BODY.get()).isEmpty();
        var status = call("GET", tasksPath() + "/" + id, null, "X-Api-Key", "internal-test");
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(status.body()).get("transferSuccess").booleanValue()).isTrue();
        assertThat(REQUESTS.get()).isEqualTo(1);
        assertThat(callback(id, "get-request", true, "callback-test").statusCode()).isEqualTo(204);
        assertThat(mapper.findByTaskId(id).isProcessed()).isTrue();
    }

    @Test void threeBusinessesHaveSeparateStorageAndCallbacksCannotCrossBusinesses() throws Exception {
        assertThat(mappers).hasSize(3);
        Set<String> taskIds = new HashSet<>();
        for (String name : List.of("advertising", "crm", "inventory")) {
            selectBusiness(name);
            var response = submit("shared-request-id", "body", "text/plain");
            assertThat(response.statusCode()).isEqualTo(202);
            String id = taskId(response);
            taskIds.add(id);
            await(() -> mapper.findByTaskId(id).isTransferSuccess());
            awaitUnlocked(id);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table(), Integer.class)).isEqualTo(1);
            String other = name.equals("advertising") ? "crm" : "advertising";
            assertThat(call("GET", "/api/" + other + "/tasks/" + id,
                    null, "X-Api-Key", "internal-test").statusCode()).isEqualTo(404);
            String callbackBody = JSON.writeValueAsString(Map.of(
                    "taskId", id, "requestId", "shared-request-id", "success", true));
            assertThat(call("POST", "/api/" + other + "/callback", callbackBody,
                    "X-Callback-Key", "callback-test").statusCode()).isEqualTo(404);
            assertThat(mapper.findByTaskId(id).isProcessed()).isFalse();
            assertThat(callback(id, "shared-request-id", true, "callback-test").statusCode()).isEqualTo(204);
        }
        assertThat(taskIds).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"advertising", "crm", "inventory"})
    void submissionRequiresCallerKeyAndMalformedCallbackDoesNotUpdate(String scenario) throws Exception {
        selectBusiness(scenario);
        assertThat(call("POST", tasksPath(), "{}", "X-Api-Key", "wrong").statusCode()).isEqualTo(401);
        assertThat(call("POST", tasksPath(), "{}", "X-Api-Key", "internal-test").statusCode()).isEqualTo(400);
        assertThat(call("POST", callbackPath(), "{}", "X-Callback-Key", "callback-test").statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table(), Integer.class)).isZero();
    }

    private HttpResponse<String> submit(String requestId, String body, String type) throws Exception {
        String request = JSON.writeValueAsString(Map.of("requestId", requestId,
                "url", "http://localhost:" + SUPPLIER.getAddress().getPort() + "/" + business + "/notify", "method", "POST",
                "headers", Map.of("Content-Type", type), "body", body));
        return call("POST", tasksPath(), request, "X-Api-Key", "internal-test");
    }

    private static HttpResponse<String> callback(String taskId, String requestId, boolean success, String key) throws Exception {
        return call("POST", callbackPath(), JSON.writeValueAsString(Map.of(
                "taskId", taskId, "requestId", requestId, "success", success)), "X-Callback-Key", key);
    }

    private static HttpResponse<String> call(String method, String path, String body, String header, String key) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + appPort + path))
                .timeout(Duration.ofSeconds(5)).header(header, key).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String taskId(HttpResponse<String> response) {
        JsonNode root = JSON.readTree(response.body());
        assertThat(root.has("taskId")).as(response.body()).isTrue();
        return root.get("taskId").stringValue();
    }

    private void awaitUnlocked(String taskId) throws Exception {
        await(() -> {
            try (var lease = locks.tryAcquire(taskId)) { return lease != null; }
            catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("Timed out awaiting task state");
            Thread.sleep(20);
        }
    }

    private static HttpServer startSupplier() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(SUPPLIER_THREADS);
            server.createContext("/", exchange -> {
                try {
                    METHOD.set(exchange.getRequestMethod());
                    TARGET.set(exchange.getRequestURI().toASCIIString());
                    int count = REQUESTS.incrementAndGet();
                    TIMES.add(System.nanoTime());
                    String taskId = exchange.getRequestHeaders().getFirst("X-Task-Id");
                    String requestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
                    TASK_IDS.add(taskId); REQUEST_IDS.add(requestId);
                    BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    CONTENT_TYPE.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                    String mode = MODE.get();
                    if (mode.equals("early-callback")) {
                        CALLBACK_STATUS.set(callback(taskId, requestId, true, "callback-test").statusCode());
                    }
                    String response = switch (mode) {
                        case "retry" -> flags(true, false, false);
                        case "retry-then-success" -> flags(count < 4, false, count >= 4);
                        case "duplicate" -> flags(false, true, false);
                        case "failed" -> flags(false, false, false);
                        case "malformed", "redirect" -> "{}";
                        default -> flags(false, false, true);
                    };
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    if (mode.equals("redirect")) exchange.getResponseHeaders().set("Location", "/notify");
                    exchange.sendResponseHeaders(mode.equals("redirect") ? 302 : 200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (Exception e) { throw new RuntimeException(e); }
                finally { exchange.close(); }
            });
            server.start();
            return server;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    private static String flags(boolean retry, boolean duplicate, boolean success) {
        return "{\"retryable\":" + retry + ",\"repeatRequest\":" + duplicate + ",\"success\":" + success + "}";
    }
}
