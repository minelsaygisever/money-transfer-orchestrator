package com.minelsaygisever.transfer.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minelsaygisever.common.domain.enums.EventType;
import com.minelsaygisever.common.event.credit.AccountCreditedEvent;
import com.minelsaygisever.common.event.debit.AccountDebitedEvent;
import com.minelsaygisever.transfer.domain.enums.TransferState;
import com.minelsaygisever.transfer.dto.TransferApiRequest;
import com.minelsaygisever.transfer.dto.TransferResponse;
import com.minelsaygisever.transfer.repository.TransferRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "transfer.outbox.polling-interval=100ms",
        "transfer.outbox.initial-delay=0ms",
        "spring.cloud.stream.kafka.binder.configuration.auto.offset.reset=earliest"
})
class TransferSagaE2ETest extends AbstractIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private ReactiveRedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private Consumer<String, String> testConsumer;
    private Producer<String, String> testProducer;

    @BeforeEach
    void setup() {
        transferRepository.deleteAll().block();
        redisTemplate.execute(conn -> conn.serverCommands().flushAll()).blockLast();
        setupKafkaClient();
    }

    @AfterEach
    void tearDown() {
        if (testConsumer != null) {
            testConsumer.unsubscribe();
            testConsumer.close();
        }
        if (testProducer != null) testProducer.close();
    }

    @Test
    @DisplayName("FULL SAGA FLOW: Init -> Debit -> Credit -> Completed")
    void shouldCompleteFullSagaFlow() throws Exception {
        // --- STEP 1: START TRANSFER ---
        String idempotencyKey = UUID.randomUUID().toString();
        TransferApiRequest request = new TransferApiRequest("ACC-A", "ACC-B", new BigDecimal("100.00"), "EUR");

        TransferResponse initialResponse = webTestClient.post()
                .uri("/api/v1/transfers")
                .header("x-idempotency-key", idempotencyKey)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .returnResult(TransferResponse.class)
                .getResponseBody()
                .blockFirst();

        assertThat(initialResponse).isNotNull();
        UUID transactionId = initialResponse.transactionId();
        assertThat(initialResponse.state()).isEqualTo(TransferState.STARTED);

        // --- STEP 2: HAS THE TRANSFER SERVICE ISSUED A "DEBIT" ORDER? ---
        ConsumerRecord<String, String> debitRecord = waitForRecord(testConsumer, "transfer-debit-events");

        assertThat(debitRecord).isNotNull();
        assertThat(debitRecord.value()).contains(transactionId.toString());
        assertThat(debitRecord.value()).contains("100.00");
        System.out.println("[TEST] Debit Command Received: " + debitRecord.value());

        // --- STEP 3: SIMULATE ACCOUNT SERVICE (DEBIT SUCCESS) ---
        AccountDebitedEvent debitSuccessEvent = new AccountDebitedEvent(transactionId, "ACC-A", new BigDecimal("100.00"), "EUR");

        sendToAccountTopic(EventType.ACCOUNT_DEBITED, debitSuccessEvent);
        System.out.println("[TEST] Sent ACCOUNT_DEBITED event");

        // --- STEP 4: HAS THE TRANSFER SERVICE UPDATED THE STATUS AND ISSUED A "CREDIT" ORDER? ---
        ConsumerRecord<String, String> creditRecord = waitForRecord(testConsumer, "transfer-credit-events");

        assertThat(creditRecord).isNotNull();
        assertThat(creditRecord.value()).contains(transactionId.toString());
        System.out.println("[TEST] Credit Command Received: " + creditRecord.value());

        // DB check: Status should be DEBITED
        TransferState currentState = Objects.requireNonNull(transferRepository.findByTransactionId(transactionId).block()).getState();
        assertThat(currentState).isEqualTo(TransferState.DEBITED);

        // --- STEP 5: SIMULATE ACCOUNT SERVICE (CREDIT SUCCESS) ---
        AccountCreditedEvent creditSuccessEvent = new AccountCreditedEvent(transactionId, "ACC-B", new BigDecimal("100.00"), "EUR");

        sendToAccountTopic(EventType.ACCOUNT_CREDITED, creditSuccessEvent);
        System.out.println("[TEST] Sent ACCOUNT_CREDITED event");

        // --- STEP 6: FINAL CHECK (COMPLETED) ---
        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var transfer = transferRepository.findByTransactionId(transactionId).block();
                    assertThat(transfer.getState()).isEqualTo(TransferState.COMPLETED);
                });

        System.out.println("[TEST] SAGA COMPLETED SUCCESSFULLY!");
    }

    @Test
    @DisplayName("SAGA ROLLBACK: Debit OK -> Credit FAIL -> Refund Triggered -> Refund OK")
    void shouldRollbackTransfer_WhenCreditFails() throws Exception {
        // 1. START TRANSFER
        String idempotencyKey = UUID.randomUUID().toString();
        TransferApiRequest request = new TransferApiRequest("ACC-A", "ACC-B", new BigDecimal("50.00"), "EUR");

        TransferResponse response = webTestClient.post()
                .uri("/api/v1/transfers")
                .header("x-idempotency-key", idempotencyKey)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .returnResult(TransferResponse.class)
                .getResponseBody()
                .blockFirst();

        Assertions.assertNotNull(response);
        UUID transactionId = response.transactionId();
        // 2. WAS THE DEBIT ORDER SENT? (Transfer -> Kafka)

        waitForRecord(testConsumer, "transfer-debit-events", "transfer-debit-out-0");

        // 3. (MOCK) ACCOUNT
        AccountDebitedEvent debitEvent = new AccountDebitedEvent(transactionId, "ACC-A", new BigDecimal("50.00"), "EUR");
        sendToAccountTopic(EventType.ACCOUNT_DEBITED, debitEvent);

        // 4. WAS THE CREDIT ORDER SENT? (Transfer -> Kafka)
        waitForRecord(testConsumer, "transfer-credit-events", "transfer-credit-out-0");

        // 5. (MOCK) ACCOUNT: ERROR
        var creditFailedEvent = new com.minelsaygisever.common.event.credit.AccountCreditFailedEvent(
                transactionId, "ACC-B", new BigDecimal("50.00"), "EUR", "Currency Mismatch");

        sendToAccountTopic(EventType.ACCOUNT_CREDIT_FAILED, creditFailedEvent);

        // 6. RECEIVED THE REFUND ORDER?
        System.out.println("[TEST] Refund Command pending");
        ConsumerRecord<String, String> refundRecord = waitForRecord(testConsumer, "transfer-refund-events", "transfer-refund-out-0");

        assertThat(refundRecord).isNotNull();
        System.out.println("[TEST] Refund Command Received: " + refundRecord.value());

        // 7. DB CHECK
        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    var transfer = transferRepository.findByTransactionId(transactionId).block();
                    Assertions.assertNotNull(transfer);
                    assertThat(transfer.getState().name()).isIn("REFUND_INITIATED", "FAILED", "COMPENSATING");
                });
    }


    private void sendToAccountTopic(EventType type, Object event) throws Exception {
        String payload = objectMapper.writeValueAsString(event);
        ProducerRecord<String, String> record = new ProducerRecord<>("account-events", payload);
        record.headers().add("eventType", type.name().getBytes());

        testProducer.send(record).get();
    }

    private void setupKafkaClient() {
        try (var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            var topics = List.of(
                    new NewTopic("account-events", 1, (short) 1),
                    new NewTopic("transfer-debit-events", 1, (short) 1),
                    new NewTopic("transfer-credit-events", 1, (short) 1),
                    new NewTopic("transfer-refund-events", 1, (short) 1)
            );
            admin.createTopics(topics).all().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (!(e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) {
                System.err.println("Topic creation warning (might be safe to ignore if re-running): " + e.getMessage());
            }
        }

        String randomGroupId = "test-verification-group-" + UUID.randomUUID();

        Map<String, Object> props = KafkaTestUtils.consumerProps(kafka.getBootstrapServers(), randomGroupId, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        DefaultKafkaConsumerFactory<String, String> cf = new DefaultKafkaConsumerFactory<>(props);
        testConsumer = cf.createConsumer();

        testConsumer.subscribe(List.of(
                "transfer-debit-events",
                "transfer-credit-events",
                "transfer-refund-events"
        ));

        testConsumer.poll(Duration.ofSeconds(1));

        Map<String, Object> prodProps = KafkaTestUtils.producerProps(kafka.getBootstrapServers());
        DefaultKafkaProducerFactory<String, String> pf = new DefaultKafkaProducerFactory<>(prodProps, new StringSerializer(), new StringSerializer());
        testProducer = pf.createProducer();
    }

    private ConsumerRecord<String, String> waitForRecord(Consumer<String, String> consumer, String... topics) {
        AtomicReference<ConsumerRecord<String, String>> foundRecord = new AtomicReference<>();

        Awaitility.await()
                .atMost(20, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> {
                    var records = consumer.poll(Duration.ofMillis(100));
                    for (var record : records) {
                        for (String expectedTopic : topics) {
                            if (record.topic().equals(expectedTopic)) {
                                foundRecord.set(record);
                                return true;
                            }
                        }
                    }
                    return false;
                });

        return foundRecord.get();
    }
}